package pro.liliya.core.persistence

import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

internal sealed interface PersistentRecordTransitionResult {
    data class Committed(val ownership: PersistentRecordOwnership) : PersistentRecordTransitionResult
    data class Rejected(val reason: String) : PersistentRecordTransitionResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentRecordTransitionResult
}

class PersistentRecordStore private constructor(
    private val foundation: FoundationComposition,
    val storeId: PersistentStoreId,
    private val backend: PersistentRecordBackend,
    initialRevision: Long,
    initialState: PersistentBackendState
) {
    private var revision: Long = initialRevision
    private var state: PersistentBackendState = initialState.detached()

    @Synchronized
    fun install(record: PersistentRecord): PersistentInstallResult {
        if (state.entries.containsKey(record.id)) {
            return rejectInstall(record, "persistent entity is already live")
        }

        val nextValue = state.highWatermark + 1
        if (nextValue <= 0) return PersistentInstallResult.Failed("persistent generation overflow")
        val generation = PersistentGeneration(nextValue)
        val candidate = state.copy(
            highWatermark = nextValue,
            entries = state.entries + (record.id to PersistentBackendEntry(generation, record.detached()))
        )

        return when (val committed = backend.commit(storeId, revision, candidate.detached())) {
            is PersistentBackendCommitResult.Committed -> {
                if (committed.revision <= revision) {
                    observe(
                        DiagnosticSeverity.ERROR,
                        "PERSISTENT_RECORD_COMMIT_FAILED",
                        "persistent backend returned non-monotonic commit revision",
                        metadata(record, generation) + ("failureCategory" to "backend-revision")
                    )
                    PersistentInstallResult.Failed("persistent backend returned non-monotonic commit revision")
                } else {
                    revision = committed.revision
                    state = candidate.detached()
                    observe(
                        DiagnosticSeverity.INFO,
                        "PERSISTENT_RECORD_COMMITTED",
                        "persistent record committed",
                        metadata(record, generation)
                    )
                    PersistentInstallResult.Installed(ownership(record.detached(), generation))
                }
            }

            PersistentBackendCommitResult.Conflict ->
                rejectInstall(record, "persistent backend revision changed")

            is PersistentBackendCommitResult.Failed -> {
                observe(
                    DiagnosticSeverity.ERROR,
                    "PERSISTENT_RECORD_COMMIT_FAILED",
                    "persistent record commit failed",
                    metadata(record, generation) + ("failureCategory" to "backend-commit")
                )
                PersistentInstallResult.Failed(committed.reason, committed.throwable)
            }
        }
    }

    @Synchronized
    fun find(id: PersistentEntityId): PersistentRecord? = state.entries[id]?.record?.detached()

    @Synchronized
    fun inspect(id: PersistentEntityId): PersistentRecordSnapshot? = state.entries[id]?.let {
        PersistentRecordSnapshot(it.record.detached(), it.generation)
    }

    @Synchronized
    fun contains(id: PersistentEntityId): Boolean = state.entries.containsKey(id)

    @Synchronized
    fun snapshot(): List<PersistentRecord> = snapshotEntries().map { it.record }

    @Synchronized
    fun snapshotEntries(): List<PersistentRecordSnapshot> = state.entries.values
        .map { PersistentRecordSnapshot(it.record.detached(), it.generation) }
        .sortedWith(compareBy({ it.record.createdAt }, { it.record.id.value }))

    @Synchronized
    internal fun generationHighWatermark(): Long = state.highWatermark

    /**
     * Atomically replaces one exact live record with another record in a single backend revision,
     * preserving the source generation and store high-watermark. This is intentionally internal:
     * callers must already own the domain transition semantics that justify the replacement.
     */
    @Synchronized
    internal fun transitionExact(
        sourceId: PersistentEntityId,
        sourceGeneration: PersistentGeneration,
        replacement: PersistentRecord
    ): PersistentRecordTransitionResult {
        val current = state.entries[sourceId]
            ?: return PersistentRecordTransitionResult.Rejected("persistent transition source is not live")
        if (current.generation != sourceGeneration) {
            return PersistentRecordTransitionResult.Rejected("persistent transition source generation is stale")
        }
        if (replacement.id != sourceId && state.entries.containsKey(replacement.id)) {
            return PersistentRecordTransitionResult.Rejected("persistent transition replacement entity is already live")
        }

        val replacementEntry = PersistentBackendEntry(sourceGeneration, replacement.detached())
        val candidateEntries = state.entries.toMutableMap().apply {
            remove(sourceId)
            put(replacement.id, replacementEntry)
        }.toMap()
        val candidate = state.copy(entries = candidateEntries)

        return when (val committed = backend.commit(storeId, revision, candidate.detached())) {
            is PersistentBackendCommitResult.Committed -> {
                if (committed.revision <= revision) {
                    observe(
                        DiagnosticSeverity.ERROR,
                        "PERSISTENT_RECORD_TRANSITION_FAILED",
                        "persistent backend returned non-monotonic commit revision",
                        metadata(current.record, sourceGeneration) +
                            ("persistentReplacementEntityId" to replacement.id.value) +
                            ("failureCategory" to "backend-revision")
                    )
                    PersistentRecordTransitionResult.Failed(
                        "persistent backend returned non-monotonic commit revision"
                    )
                } else {
                    revision = committed.revision
                    state = candidate.detached()
                    observe(
                        DiagnosticSeverity.INFO,
                        "PERSISTENT_RECORD_TRANSITIONED",
                        "persistent record transitioned",
                        metadata(replacement, sourceGeneration) +
                            ("persistentSourceEntityId" to sourceId.value)
                    )
                    PersistentRecordTransitionResult.Committed(
                        ownership(replacement.detached(), sourceGeneration)
                    )
                }
            }

            PersistentBackendCommitResult.Conflict ->
                PersistentRecordTransitionResult.Rejected("persistent backend revision changed")

            is PersistentBackendCommitResult.Failed -> {
                observe(
                    DiagnosticSeverity.ERROR,
                    "PERSISTENT_RECORD_TRANSITION_FAILED",
                    "persistent record transition commit failed",
                    metadata(current.record, sourceGeneration) +
                        ("persistentReplacementEntityId" to replacement.id.value) +
                        ("failureCategory" to "backend-commit")
                )
                PersistentRecordTransitionResult.Failed(committed.reason, committed.throwable)
            }
        }
    }

    private fun ownership(
        record: PersistentRecord,
        generation: PersistentGeneration
    ): PersistentRecordOwnership = object : PersistentRecordOwnership {
        override val record: PersistentRecord = record.detached()
        override val generation: PersistentGeneration = generation
        override fun remove(): PersistentMutationResult = removeExact(record.id, generation)
    }

    @Synchronized
    private fun removeExact(
        id: PersistentEntityId,
        generation: PersistentGeneration
    ): PersistentMutationResult {
        val current = state.entries[id]
            ?: return PersistentMutationResult.Rejected("persistent entity is not live")
        if (current.generation != generation) {
            return PersistentMutationResult.Rejected("persistent ownership generation is stale")
        }

        val candidate = state.copy(entries = state.entries - id)
        return when (val committed = backend.commit(storeId, revision, candidate.detached())) {
            is PersistentBackendCommitResult.Committed -> {
                if (committed.revision <= revision) {
                    observe(
                        DiagnosticSeverity.ERROR,
                        "PERSISTENT_RECORD_REMOVE_FAILED",
                        "persistent backend returned non-monotonic commit revision",
                        metadata(current.record, generation) + ("failureCategory" to "backend-revision")
                    )
                    PersistentMutationResult.Failed("persistent backend returned non-monotonic commit revision")
                } else {
                    revision = committed.revision
                    state = candidate.detached()
                    observe(
                        DiagnosticSeverity.INFO,
                        "PERSISTENT_RECORD_REMOVED",
                        "persistent record removed",
                        metadata(current.record, generation)
                    )
                    PersistentMutationResult.Committed
                }
            }

            PersistentBackendCommitResult.Conflict ->
                PersistentMutationResult.Rejected("persistent backend revision changed")

            is PersistentBackendCommitResult.Failed -> {
                observe(
                    DiagnosticSeverity.ERROR,
                    "PERSISTENT_RECORD_REMOVE_FAILED",
                    "persistent record removal commit failed",
                    metadata(current.record, generation) + ("failureCategory" to "backend-commit")
                )
                PersistentMutationResult.Failed(committed.reason, committed.throwable)
            }
        }
    }

    private fun rejectInstall(
        record: PersistentRecord,
        reason: String
    ): PersistentInstallResult.Rejected {
        observe(
            DiagnosticSeverity.WARNING,
            "PERSISTENT_RECORD_REJECTED",
            reason,
            metadata(record, null) + ("rejectionReason" to reason)
        )
        return PersistentInstallResult.Rejected(reason)
    }

    private fun metadata(
        record: PersistentRecord,
        generation: PersistentGeneration?
    ): Map<String, String> = buildMap {
        put("persistentStoreId", storeId.value)
        put("persistentEntityId", record.id.value)
        put("persistentSchemaId", record.schemaId.value)
        put("persistentSchemaVersion", record.schemaVersion.value.toString())
        put("persistentPayloadBytes", record.payload.size.toString())
        generation?.let { put("persistentGeneration", it.value.toString()) }
        put("createdAt", record.createdAt.toString())
    }

    private fun observe(
        severity: DiagnosticSeverity,
        code: String,
        message: String,
        metadata: Map<String, String>
    ) {
        foundation.observability.record(
            severity = severity,
            code = code,
            message = message,
            context = foundation.rootContext(
                operation = "persistentRecordStore",
                component = "Persistence",
                metadata = metadata
            ),
            metadata = metadata
        )
    }

    private fun PersistentBackendState.detached(): PersistentBackendState = copy(
        entries = entries.mapValues { (_, entry) ->
            entry.copy(record = entry.record.detached())
        }.toMap()
    )

    private fun PersistentRecord.detached(): PersistentRecord = copy(
        payload = PersistentPayload(payload.copyBytes())
    )

    companion object {
        fun open(
            foundation: FoundationComposition,
            storeId: PersistentStoreId,
            backend: PersistentRecordBackend
        ): PersistentStoreOpenResult = when (val loaded = backend.load(storeId)) {
            PersistentBackendLoadResult.Missing -> PersistentStoreOpenResult.Opened(
                PersistentRecordStore(
                    foundation,
                    storeId,
                    backend,
                    0,
                    PersistentBackendState(storeId, 0, emptyMap())
                )
            )

            is PersistentBackendLoadResult.Loaded -> {
                val loadedState = loaded.state
                when {
                    loadedState.storeId != storeId ->
                        PersistentStoreOpenResult.Incompatible("persistent backend store id mismatch")

                    loadedState.entries.any { (id, entry) -> id != entry.record.id } ->
                        PersistentStoreOpenResult.Corrupt

                    loadedState.entries.values.any { it.generation.value > loadedState.highWatermark } ->
                        PersistentStoreOpenResult.Corrupt

                    loadedState.entries.values
                        .map { it.generation }
                        .toSet()
                        .size != loadedState.entries.size ->
                        PersistentStoreOpenResult.Corrupt

                    else -> PersistentStoreOpenResult.Opened(
                        PersistentRecordStore(
                            foundation,
                            storeId,
                            backend,
                            loaded.revision,
                            loadedState
                        )
                    )
                }
            }

            PersistentBackendLoadResult.Corrupt -> PersistentStoreOpenResult.Corrupt
            is PersistentBackendLoadResult.Incompatible ->
                PersistentStoreOpenResult.Incompatible(loaded.reason)
            is PersistentBackendLoadResult.Failed ->
                PersistentStoreOpenResult.Failed(loaded.reason, loaded.throwable)
        }
    }
}
