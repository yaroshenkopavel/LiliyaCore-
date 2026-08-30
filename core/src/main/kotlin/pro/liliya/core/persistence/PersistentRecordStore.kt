package pro.liliya.core.persistence

import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

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
        if (nextValue <= 0) {
            return PersistentInstallResult.Failed("persistent generation overflow")
        }
        val generation = PersistentGeneration(nextValue)
        val candidate = state.copy(
            highWatermark = nextValue,
            entries = state.entries + (
                record.id to PersistentBackendEntry(generation, record.detached())
            )
        )

        return when (val committed = backend.commit(storeId, revision, candidate.detached())) {
            is PersistentBackendCommitResult.Committed -> {
                revision = committed.revision
                state = candidate.detached()
                record(
                    severity = DiagnosticSeverity.INFO,
                    code = "PERSISTENT_RECORD_COMMITTED",
                    message = "persistent record committed",
                    metadata = metadata(record, generation)
                )
                PersistentInstallResult.Installed(
                    ownership = ownership(record.detached(), generation)
                )
            }

            PersistentBackendCommitResult.Conflict ->
                rejectInstall(record, "persistent backend revision changed")

            is PersistentBackendCommitResult.Failed -> {
                record(
                    severity = DiagnosticSeverity.ERROR,
                    code = "PERSISTENT_RECORD_COMMIT_FAILED",
                    message = "persistent record commit failed",
                    metadata = metadata(record, generation) +
                        ("failureCategory" to "backend-commit")
                )
                PersistentInstallResult.Failed(committed.reason, committed.throwable)
            }
        }
    }

    @Synchronized
    fun find(id: PersistentEntityId): PersistentRecord? =
        state.entries[id]?.record?.detached()

    @Synchronized
    fun inspect(id: PersistentEntityId): PersistentRecordSnapshot? =
        state.entries[id]?.let { entry ->
            PersistentRecordSnapshot(entry.record.detached(), entry.generation)
        }

    @Synchronized
    fun contains(id: PersistentEntityId): Boolean = state.entries.containsKey(id)

    @Synchronized
    fun snapshot(): List<PersistentRecord> = snapshotEntries().map { it.record }

    @Synchronized
    fun snapshotEntries(): List<PersistentRecordSnapshot> = state.entries.values
        .map { entry -> PersistentRecordSnapshot(entry.record.detached(), entry.generation) }
        .sortedWith(compareBy({ it.record.createdAt }, { it.record.id.value }))

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
                revision = committed.revision
                state = candidate.detached()
                record(
                    severity = DiagnosticSeverity.INFO,
                    code = "PERSISTENT_RECORD_REMOVED",
                    message = "persistent record removed",
                    metadata = metadata(current.record, generation)
                )
                PersistentMutationResult.Committed
            }

            PersistentBackendCommitResult.Conflict ->
                PersistentMutationResult.Rejected("persistent backend revision changed")

            is PersistentBackendCommitResult.Failed -> {
                record(
                    severity = DiagnosticSeverity.ERROR,
                    code = "PERSISTENT_RECORD_REMOVE_FAILED",
                    message = "persistent record removal commit failed",
                    metadata = metadata(current.record, generation) +
                        ("failureCategory" to "backend-commit")
                )
                PersistentMutationResult.Failed(committed.reason, committed.throwable)
            }
        }
    }

    private fun rejectInstall(record: PersistentRecord, reason: String): PersistentInstallResult.Rejected {
        record(
            severity = DiagnosticSeverity.WARNING,
            code = "PERSISTENT_RECORD_REJECTED",
            message = reason,
            metadata = metadata(record, null) + ("rejectionReason" to reason)
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

    private fun record(
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
                    foundation = foundation,
                    storeId = storeId,
                    backend = backend,
                    initialRevision = 0,
                    initialState = PersistentBackendState(storeId, 0, emptyMap())
                )
            )

            is PersistentBackendLoadResult.Loaded -> {
                val state = loaded.state
                when {
                    state.storeId != storeId ->
                        PersistentStoreOpenResult.Incompatible("persistent backend store id mismatch")

                    state.entries.values.any { it.generation.value > state.highWatermark } ->
                        PersistentStoreOpenResult.Corrupt

                    else -> PersistentStoreOpenResult.Opened(
                        PersistentRecordStore(
                            foundation = foundation,
                            storeId = storeId,
                            backend = backend,
                            initialRevision = loaded.revision,
                            initialState = state
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
