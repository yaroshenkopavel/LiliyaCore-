package pro.liliya.core.persistence

import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

internal sealed interface PersistentRecordTransitionResult {
    data class Committed(val ownership: PersistentRecordOwnership) : PersistentRecordTransitionResult
    data class Rejected(val reason: String) : PersistentRecordTransitionResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentRecordTransitionResult
}

/** Fail-closed signal for indexed reads that cannot be represented by legacy nullable APIs. */
internal class PersistentRecordAccessException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

class PersistentRecordStore private constructor(
    private val foundation: FoundationComposition,
    val storeId: PersistentStoreId,
    private val backend: PersistentRecordBackend,
    initialRevision: Long,
    initialHighWatermark: Long,
    initialEntryCount: Long,
    initialLegacyState: PersistentBackendState?
) {
    private val indexedBackend: IndexedPersistentRecordMutationBackend? =
        backend as? IndexedPersistentRecordMutationBackend
    private var revision: Long = initialRevision
    private var highWatermark: Long = initialHighWatermark
    private var entryCount: Long = initialEntryCount
    private var legacyState: PersistentBackendState? = initialLegacyState?.detached()

    private val indexedMode: Boolean
        get() = indexedBackend != null && legacyState == null

    @Synchronized
    fun install(record: PersistentRecord): PersistentInstallResult {
        if (indexedMode) return installIndexed(record)
        val state = requireLegacyState()

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
                    commitRevisionFailure(record, generation)
                } else {
                    revision = committed.revision
                    highWatermark = candidate.highWatermark
                    entryCount = candidate.entries.size.toLong()
                    legacyState = candidate.detached()
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

    private fun installIndexed(record: PersistentRecord): PersistentInstallResult {
        val indexed = requireNotNull(indexedBackend)
        when (val existing = indexed.loadEntry(storeId, record.id)) {
            is PersistentBackendEntryLoadResult.Loaded ->
                return rejectInstall(record, "persistent entity is already live")
            PersistentBackendEntryLoadResult.Missing -> Unit
            PersistentBackendEntryLoadResult.Corrupt ->
                return PersistentInstallResult.Failed("indexed persistent store is corrupt")
            is PersistentBackendEntryLoadResult.Incompatible ->
                return PersistentInstallResult.Failed(existing.reason)
            is PersistentBackendEntryLoadResult.Failed ->
                return PersistentInstallResult.Failed(existing.reason, existing.throwable)
        }

        val nextValue = highWatermark + 1L
        if (nextValue <= 0L) return PersistentInstallResult.Failed("persistent generation overflow")
        val generation = PersistentGeneration(nextValue)
        val entry = PersistentBackendEntry(generation, record.detached())
        return when (
            val result = indexed.installEntry(
                storeId = storeId,
                expectedRevision = revision,
                expectedHighWatermark = highWatermark,
                entry = entry
            )
        ) {
            is PersistentBackendMutationResult.Committed -> {
                val metadata = result.metadata
                if (metadata.revision <= revision ||
                    metadata.highWatermark != nextValue ||
                    metadata.entryCount != entryCount + 1L
                ) {
                    commitRevisionFailure(record, generation)
                } else {
                    applyIndexedMetadata(metadata)
                    observe(
                        DiagnosticSeverity.INFO,
                        "PERSISTENT_RECORD_COMMITTED",
                        "persistent indexed record committed",
                        metadata(record, generation)
                    )
                    PersistentInstallResult.Installed(ownership(record.detached(), generation))
                }
            }
            PersistentBackendMutationResult.Conflict ->
                rejectInstall(record, "persistent backend revision changed")
            is PersistentBackendMutationResult.Rejected ->
                rejectInstall(record, result.reason)
            PersistentBackendMutationResult.Corrupt ->
                PersistentInstallResult.Failed("indexed persistent store is corrupt")
            is PersistentBackendMutationResult.Incompatible ->
                PersistentInstallResult.Failed(result.reason)
            is PersistentBackendMutationResult.Failed ->
                PersistentInstallResult.Failed(result.reason, result.throwable)
        }
    }

    @Synchronized
    fun find(id: PersistentEntityId): PersistentRecord? = inspect(id)?.record

    @Synchronized
    fun inspect(id: PersistentEntityId): PersistentRecordSnapshot? {
        if (!indexedMode) {
            return requireLegacyState().entries[id]?.let {
                PersistentRecordSnapshot(it.record.detached(), it.generation)
            }
        }
        val indexed = requireNotNull(indexedBackend)
        return when (val loaded = indexed.loadEntry(storeId, id)) {
            PersistentBackendEntryLoadResult.Missing -> null
            is PersistentBackendEntryLoadResult.Loaded ->
                PersistentRecordSnapshot(loaded.snapshot.record.detached(), loaded.snapshot.generation)
            PersistentBackendEntryLoadResult.Corrupt ->
                throw PersistentRecordAccessException("indexed persistent exact read is corrupt")
            is PersistentBackendEntryLoadResult.Incompatible ->
                throw PersistentRecordAccessException(loaded.reason)
            is PersistentBackendEntryLoadResult.Failed ->
                throw PersistentRecordAccessException(loaded.reason, loaded.throwable)
        }
    }

    @Synchronized
    fun contains(id: PersistentEntityId): Boolean = inspect(id) != null

    @Synchronized
    fun snapshot(): List<PersistentRecord> = snapshotEntries().map { it.record }

    @Synchronized
    fun snapshotEntries(): List<PersistentRecordSnapshot> {
        if (!indexedMode) {
            return requireLegacyState().entries.values
                .map { PersistentRecordSnapshot(it.record.detached(), it.generation) }
                .sortedWith(compareBy({ it.record.createdAt }, { it.record.id.value }))
        }
        if (entryCount == 0L) return emptyList()
        val indexed = requireNotNull(indexedBackend)
        val result = ArrayList<PersistentRecordSnapshot>()
        var cursor: PersistentBackendPageCursor? = null
        do {
            when (
                val loaded = indexed.loadPage(
                    storeId,
                    PersistentBackendPageRequest(
                        limit = PersistentBackendPageRequest.MAX_PAGE_SIZE,
                        cursorExclusive = cursor
                    )
                )
            ) {
                PersistentBackendPageLoadResult.Missing ->
                    throw PersistentRecordAccessException("indexed persistent store disappeared during page read")
                is PersistentBackendPageLoadResult.Loaded -> {
                    result += loaded.page.entries.map {
                        PersistentRecordSnapshot(it.record.detached(), it.generation)
                    }
                    cursor = loaded.page.nextCursor
                }
                PersistentBackendPageLoadResult.Corrupt ->
                    throw PersistentRecordAccessException("indexed persistent page read is corrupt")
                is PersistentBackendPageLoadResult.Incompatible ->
                    throw PersistentRecordAccessException(loaded.reason)
                is PersistentBackendPageLoadResult.Failed ->
                    throw PersistentRecordAccessException(loaded.reason, loaded.throwable)
            }
        } while (cursor != null)

        if (result.size.toLong() != entryCount) {
            throw PersistentRecordAccessException("indexed persistent page count changed during read")
        }
        return result
    }

    @Synchronized
    internal fun generationHighWatermark(): Long = highWatermark

    @Synchronized
    internal fun transitionExact(
        sourceId: PersistentEntityId,
        sourceGeneration: PersistentGeneration,
        replacement: PersistentRecord
    ): PersistentRecordTransitionResult {
        if (indexedMode) return transitionIndexed(sourceId, sourceGeneration, replacement)
        val state = requireLegacyState()
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
                    transitionRevisionFailure(current.record, sourceGeneration, replacement)
                } else {
                    revision = committed.revision
                    legacyState = candidate.detached()
                    highWatermark = candidate.highWatermark
                    entryCount = candidate.entries.size.toLong()
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

    private fun transitionIndexed(
        sourceId: PersistentEntityId,
        sourceGeneration: PersistentGeneration,
        replacement: PersistentRecord
    ): PersistentRecordTransitionResult {
        val indexed = requireNotNull(indexedBackend)
        val current = try {
            inspect(sourceId)
        } catch (e: PersistentRecordAccessException) {
            return PersistentRecordTransitionResult.Failed(e.message ?: "indexed persistent read failed", e)
        } ?: return PersistentRecordTransitionResult.Rejected("persistent transition source is not live")

        if (current.generation != sourceGeneration) {
            return PersistentRecordTransitionResult.Rejected("persistent transition source generation is stale")
        }
        if (replacement.id != sourceId) {
            val replacementExists = try {
                contains(replacement.id)
            } catch (e: PersistentRecordAccessException) {
                return PersistentRecordTransitionResult.Failed(e.message ?: "indexed persistent read failed", e)
            }
            if (replacementExists) {
                return PersistentRecordTransitionResult.Rejected(
                    "persistent transition replacement entity is already live"
                )
            }
        }

        return when (
            val result = indexed.transitionEntry(
                storeId = storeId,
                expectedRevision = revision,
                expectedHighWatermark = highWatermark,
                sourceId = sourceId,
                sourceGeneration = sourceGeneration,
                replacement = PersistentBackendEntry(sourceGeneration, replacement.detached())
            )
        ) {
            is PersistentBackendMutationResult.Committed -> {
                val metadata = result.metadata
                if (metadata.revision <= revision ||
                    metadata.highWatermark != highWatermark ||
                    metadata.entryCount != entryCount
                ) {
                    transitionRevisionFailure(current.record, sourceGeneration, replacement)
                } else {
                    applyIndexedMetadata(metadata)
                    observe(
                        DiagnosticSeverity.INFO,
                        "PERSISTENT_RECORD_TRANSITIONED",
                        "persistent indexed record transitioned",
                        metadata(replacement, sourceGeneration) +
                            ("persistentSourceEntityId" to sourceId.value)
                    )
                    PersistentRecordTransitionResult.Committed(
                        ownership(replacement.detached(), sourceGeneration)
                    )
                }
            }
            PersistentBackendMutationResult.Conflict ->
                PersistentRecordTransitionResult.Rejected("persistent backend revision changed")
            is PersistentBackendMutationResult.Rejected ->
                PersistentRecordTransitionResult.Rejected(result.reason)
            PersistentBackendMutationResult.Corrupt ->
                PersistentRecordTransitionResult.Failed("indexed persistent store is corrupt")
            is PersistentBackendMutationResult.Incompatible ->
                PersistentRecordTransitionResult.Failed(result.reason)
            is PersistentBackendMutationResult.Failed ->
                PersistentRecordTransitionResult.Failed(result.reason, result.throwable)
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
    internal fun removeExact(
        id: PersistentEntityId,
        generation: PersistentGeneration
    ): PersistentMutationResult {
        if (indexedMode) return removeIndexed(id, generation)
        val state = requireLegacyState()
        val current = state.entries[id]
            ?: return PersistentMutationResult.Rejected("persistent entity is not live")
        if (current.generation != generation) {
            return PersistentMutationResult.Rejected("persistent ownership generation is stale")
        }

        val candidate = state.copy(entries = state.entries - id)
        return when (val committed = backend.commit(storeId, revision, candidate.detached())) {
            is PersistentBackendCommitResult.Committed -> {
                if (committed.revision <= revision) {
                    removeRevisionFailure(current.record, generation)
                } else {
                    revision = committed.revision
                    legacyState = candidate.detached()
                    highWatermark = candidate.highWatermark
                    entryCount = candidate.entries.size.toLong()
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

    private fun removeIndexed(
        id: PersistentEntityId,
        generation: PersistentGeneration
    ): PersistentMutationResult {
        val indexed = requireNotNull(indexedBackend)
        val current = try {
            inspect(id)
        } catch (e: PersistentRecordAccessException) {
            return PersistentMutationResult.Failed(e.message ?: "indexed persistent read failed", e)
        } ?: return PersistentMutationResult.Rejected("persistent entity is not live")
        if (current.generation != generation) {
            return PersistentMutationResult.Rejected("persistent ownership generation is stale")
        }

        return when (
            val result = indexed.removeEntry(
                storeId = storeId,
                expectedRevision = revision,
                expectedHighWatermark = highWatermark,
                id = id,
                generation = generation
            )
        ) {
            is PersistentBackendMutationResult.Committed -> {
                val metadata = result.metadata
                if (metadata.revision <= revision ||
                    metadata.highWatermark != highWatermark ||
                    metadata.entryCount != entryCount - 1L
                ) {
                    removeRevisionFailure(current.record, generation)
                } else {
                    applyIndexedMetadata(metadata)
                    observe(
                        DiagnosticSeverity.INFO,
                        "PERSISTENT_RECORD_REMOVED",
                        "persistent indexed record removed",
                        metadata(current.record, generation)
                    )
                    PersistentMutationResult.Committed
                }
            }
            PersistentBackendMutationResult.Conflict ->
                PersistentMutationResult.Rejected("persistent backend revision changed")
            is PersistentBackendMutationResult.Rejected ->
                PersistentMutationResult.Rejected(result.reason)
            PersistentBackendMutationResult.Corrupt ->
                PersistentMutationResult.Failed("indexed persistent store is corrupt")
            is PersistentBackendMutationResult.Incompatible ->
                PersistentMutationResult.Failed(result.reason)
            is PersistentBackendMutationResult.Failed ->
                PersistentMutationResult.Failed(result.reason, result.throwable)
        }
    }

    private fun applyIndexedMetadata(metadata: PersistentBackendMetadata) {
        revision = metadata.revision
        highWatermark = metadata.highWatermark
        entryCount = metadata.entryCount
    }

    private fun requireLegacyState(): PersistentBackendState =
        checkNotNull(legacyState) { "persistent legacy state is unavailable in indexed mode" }

    private fun commitRevisionFailure(
        record: PersistentRecord,
        generation: PersistentGeneration
    ): PersistentInstallResult.Failed {
        observe(
            DiagnosticSeverity.ERROR,
            "PERSISTENT_RECORD_COMMIT_FAILED",
            "persistent backend returned inconsistent commit metadata",
            metadata(record, generation) + ("failureCategory" to "backend-revision")
        )
        return PersistentInstallResult.Failed("persistent backend returned inconsistent commit metadata")
    }

    private fun transitionRevisionFailure(
        record: PersistentRecord,
        generation: PersistentGeneration,
        replacement: PersistentRecord
    ): PersistentRecordTransitionResult.Failed {
        observe(
            DiagnosticSeverity.ERROR,
            "PERSISTENT_RECORD_TRANSITION_FAILED",
            "persistent backend returned inconsistent transition metadata",
            metadata(record, generation) +
                ("persistentReplacementEntityId" to replacement.id.value) +
                ("failureCategory" to "backend-revision")
        )
        return PersistentRecordTransitionResult.Failed(
            "persistent backend returned inconsistent transition metadata"
        )
    }

    private fun removeRevisionFailure(
        record: PersistentRecord,
        generation: PersistentGeneration
    ): PersistentMutationResult.Failed {
        observe(
            DiagnosticSeverity.ERROR,
            "PERSISTENT_RECORD_REMOVE_FAILED",
            "persistent backend returned inconsistent remove metadata",
            metadata(record, generation) + ("failureCategory" to "backend-revision")
        )
        return PersistentMutationResult.Failed("persistent backend returned inconsistent remove metadata")
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
        ): PersistentStoreOpenResult {
            val indexed = backend as? IndexedPersistentRecordMutationBackend
            if (indexed != null) {
                return when (val loaded = indexed.loadMetadata(storeId)) {
                    PersistentBackendMetadataLoadResult.Missing ->
                        PersistentStoreOpenResult.Opened(
                            PersistentRecordStore(
                                foundation, storeId, backend,
                                initialRevision = 0L,
                                initialHighWatermark = 0L,
                                initialEntryCount = 0L,
                                initialLegacyState = null
                            )
                        )
                    is PersistentBackendMetadataLoadResult.Loaded ->
                        PersistentStoreOpenResult.Opened(
                            PersistentRecordStore(
                                foundation, storeId, backend,
                                initialRevision = loaded.metadata.revision,
                                initialHighWatermark = loaded.metadata.highWatermark,
                                initialEntryCount = loaded.metadata.entryCount,
                                initialLegacyState = null
                            )
                        )
                    PersistentBackendMetadataLoadResult.Corrupt ->
                        PersistentStoreOpenResult.Corrupt
                    is PersistentBackendMetadataLoadResult.Incompatible ->
                        PersistentStoreOpenResult.Incompatible(loaded.reason)
                    is PersistentBackendMetadataLoadResult.Failed ->
                        PersistentStoreOpenResult.Failed(loaded.reason, loaded.throwable)
                }
            }

            return when (val loaded = backend.load(storeId)) {
                PersistentBackendLoadResult.Missing -> PersistentStoreOpenResult.Opened(
                    PersistentRecordStore(
                        foundation, storeId, backend,
                        initialRevision = 0L,
                        initialHighWatermark = 0L,
                        initialEntryCount = 0L,
                        initialLegacyState = PersistentBackendState(storeId, 0, emptyMap())
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
                        loadedState.entries.values.map { it.generation }.toSet().size != loadedState.entries.size ->
                            PersistentStoreOpenResult.Corrupt
                        else -> PersistentStoreOpenResult.Opened(
                            PersistentRecordStore(
                                foundation, storeId, backend,
                                initialRevision = loaded.revision,
                                initialHighWatermark = loadedState.highWatermark,
                                initialEntryCount = loadedState.entries.size.toLong(),
                                initialLegacyState = loadedState
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
}
