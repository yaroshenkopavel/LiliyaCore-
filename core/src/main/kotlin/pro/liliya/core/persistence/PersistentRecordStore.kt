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
    initialState: PersistentBackendState,
    initialIndexedEntryCount: Long? = null
) {
    private var revision: Long = initialRevision
    private var state: PersistentBackendState = initialState.detached()
    private var indexedEntryCount: Long? = initialIndexedEntryCount

    private val indexedMutationBackend: IndexedPersistentRecordMutationBackend?
        get() = if (indexedEntryCount != null) {
            backend as? IndexedPersistentRecordMutationBackend
        } else {
            null
        }

    @Synchronized
    fun install(record: PersistentRecord): PersistentInstallResult {
        indexedMutationBackend?.let { return installIndexed(record, it) }

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

    /**
     * Fail-closed exact lookup. Indexed backends are queried directly so a selected-record
     * corruption/incompatibility/failure cannot be collapsed into "missing".
     */
    @Synchronized
    fun inspectResult(id: PersistentEntityId): PersistentRecordLookupResult {
        val indexed = backend as? IndexedPersistentRecordReadBackend
        if (indexed != null) {
            return when (val loaded = indexed.loadEntry(storeId, id)) {
                PersistentBackendEntryLoadResult.Missing -> PersistentRecordLookupResult.Missing
                is PersistentBackendEntryLoadResult.Loaded ->
                    PersistentRecordLookupResult.Found(
                        PersistentRecordSnapshot(
                            loaded.snapshot.record.detached(),
                            loaded.snapshot.generation
                        )
                    )
                PersistentBackendEntryLoadResult.Corrupt -> PersistentRecordLookupResult.Corrupt
                is PersistentBackendEntryLoadResult.Incompatible ->
                    PersistentRecordLookupResult.Incompatible(loaded.reason)
                is PersistentBackendEntryLoadResult.Failed ->
                    PersistentRecordLookupResult.Failed(loaded.reason, loaded.throwable)
            }
        }

        val current = state.entries[id] ?: return PersistentRecordLookupResult.Missing
        return PersistentRecordLookupResult.Found(
            PersistentRecordSnapshot(current.record.detached(), current.generation)
        )
    }

    /**
     * Compatibility API. New fail-closed consumers must prefer inspectResult().
     */
    @Synchronized
    fun find(id: PersistentEntityId): PersistentRecord? =
        (inspectResult(id) as? PersistentRecordLookupResult.Found)?.snapshot?.record?.detached()

    /**
     * Compatibility API. New fail-closed consumers must prefer inspectResult().
     */
    @Synchronized
    fun inspect(id: PersistentEntityId): PersistentRecordSnapshot? =
        (inspectResult(id) as? PersistentRecordLookupResult.Found)?.snapshot

    /**
     * Compatibility API. New fail-closed consumers must prefer inspectResult().
     */
    @Synchronized
    fun contains(id: PersistentEntityId): Boolean =
        inspectResult(id) is PersistentRecordLookupResult.Found

    @Synchronized
    fun snapshot(): List<PersistentRecord> = snapshotEntries().map { it.record }

    /**
     * Fail-closed explicit enumeration. Indexed backends are walked through bounded pages so
     * future metadata-only stores do not need a full PersistentBackendState load.
     */
    @Synchronized
    fun snapshotEntriesResult(): PersistentRecordSnapshotEntriesResult {
        val indexed = backend as? IndexedPersistentRecordReadBackend
        if (indexed == null) {
            val entries = state.entries.values
                .map { PersistentRecordSnapshot(it.record.detached(), it.generation) }
                .sortedWith(compareBy({ it.record.createdAt }, { it.record.id.value }))
            return if (entries.isEmpty()) {
                PersistentRecordSnapshotEntriesResult.Empty
            } else {
                PersistentRecordSnapshotEntriesResult.Loaded(entries)
            }
        }

        if (indexedEntryCount != null) {
            val barrier = indexedMetadataBarrier(indexed)
            if (barrier != null) return barrier
        }

        val collected = ArrayList<PersistentRecordSnapshot>()
        val seenIds = HashSet<PersistentEntityId>()
        val seenGenerations = HashSet<PersistentGeneration>()
        var cursor: PersistentBackendPageCursor? = null

        while (true) {
            val loaded = when (
                val page = indexed.loadPage(
                    storeId,
                    PersistentBackendPageRequest(
                        limit = PersistentBackendPageRequest.MAX_PAGE_SIZE,
                        order = PersistentBackendPageOrder.OLDEST_FIRST,
                        cursorExclusive = cursor
                    )
                )
            ) {
                PersistentBackendPageLoadResult.Missing -> {
                    val expectedCount = indexedEntryCount
                    if (collected.isEmpty() &&
                        ((expectedCount != null && expectedCount == 0L) ||
                            (expectedCount == null && state.entries.isEmpty()))
                    ) {
                        return PersistentRecordSnapshotEntriesResult.Empty
                    }
                    return PersistentRecordSnapshotEntriesResult.Corrupt
                }
                is PersistentBackendPageLoadResult.Loaded -> page.page
                PersistentBackendPageLoadResult.Corrupt ->
                    return PersistentRecordSnapshotEntriesResult.Corrupt
                is PersistentBackendPageLoadResult.Incompatible ->
                    return PersistentRecordSnapshotEntriesResult.Incompatible(page.reason)
                is PersistentBackendPageLoadResult.Failed ->
                    return PersistentRecordSnapshotEntriesResult.Failed(
                        page.reason,
                        page.throwable
                    )
            }

            for (snapshot in loaded.entries) {
                if (!seenIds.add(snapshot.record.id) || !seenGenerations.add(snapshot.generation)) {
                    return PersistentRecordSnapshotEntriesResult.Corrupt
                }
                collected += PersistentRecordSnapshot(
                    snapshot.record.detached(),
                    snapshot.generation
                )
            }

            val next = loaded.nextCursor ?: break
            if (loaded.entries.isEmpty() || next == cursor) {
                return PersistentRecordSnapshotEntriesResult.Corrupt
            }
            cursor = next
        }

        indexedEntryCount?.let { expected ->
            if (collected.size.toLong() != expected) {
                return PersistentRecordSnapshotEntriesResult.Corrupt
            }
            val barrier = indexedMetadataBarrier(indexed)
            if (barrier != null) return barrier
        }

        return if (collected.isEmpty()) {
            PersistentRecordSnapshotEntriesResult.Empty
        } else {
            PersistentRecordSnapshotEntriesResult.Loaded(collected)
        }
    }

    private fun indexedMetadataBarrier(
        indexed: IndexedPersistentRecordReadBackend
    ): PersistentRecordSnapshotEntriesResult? {
        val expectedCount = indexedEntryCount
            ?: return null
        return when (val loaded = indexed.loadMetadata(storeId)) {
            PersistentBackendMetadataLoadResult.Missing ->
                if (revision == 0L && state.highWatermark == 0L && expectedCount == 0L) {
                    null
                } else {
                    PersistentRecordSnapshotEntriesResult.Failed(
                        "persistent indexed store changed during enumeration"
                    )
                }
            is PersistentBackendMetadataLoadResult.Loaded ->
                if (loaded.metadata.revision == revision &&
                    loaded.metadata.highWatermark == state.highWatermark &&
                    loaded.metadata.entryCount == expectedCount
                ) {
                    null
                } else {
                    PersistentRecordSnapshotEntriesResult.Failed(
                        "persistent indexed store changed during enumeration"
                    )
                }
            PersistentBackendMetadataLoadResult.Corrupt ->
                PersistentRecordSnapshotEntriesResult.Corrupt
            is PersistentBackendMetadataLoadResult.Incompatible ->
                PersistentRecordSnapshotEntriesResult.Incompatible(loaded.reason)
            is PersistentBackendMetadataLoadResult.Failed ->
                PersistentRecordSnapshotEntriesResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
        }
    }

    /**
     * Compatibility API. New fail-closed consumers must prefer snapshotEntriesResult().
     */
    @Synchronized
    fun snapshotEntries(): List<PersistentRecordSnapshot> {
        if (indexedEntryCount != null) {
            return when (val result = snapshotEntriesResult()) {
                PersistentRecordSnapshotEntriesResult.Empty -> emptyList()
                is PersistentRecordSnapshotEntriesResult.Loaded -> result.entries
                PersistentRecordSnapshotEntriesResult.Corrupt ->
                    throw IllegalStateException("persistent indexed snapshot is corrupt")
                is PersistentRecordSnapshotEntriesResult.Incompatible ->
                    throw IllegalStateException(result.reason)
                is PersistentRecordSnapshotEntriesResult.Failed ->
                    throw IllegalStateException(result.reason, result.throwable)
            }
        }
        return state.entries.values
            .map { PersistentRecordSnapshot(it.record.detached(), it.generation) }
            .sortedWith(compareBy({ it.record.createdAt }, { it.record.id.value }))
    }

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
        indexedMutationBackend?.let {
            return transitionIndexed(
                it,
                sourceId,
                sourceGeneration,
                replacement
            )
        }

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

    private fun installIndexed(
        record: PersistentRecord,
        indexed: IndexedPersistentRecordMutationBackend
    ): PersistentInstallResult {
        when (val existing = inspectResult(record.id)) {
            PersistentRecordLookupResult.Missing -> Unit
            is PersistentRecordLookupResult.Found ->
                return rejectInstall(record, "persistent entity is already live")
            PersistentRecordLookupResult.Corrupt ->
                return PersistentInstallResult.Failed("persistent indexed store is corrupt")
            is PersistentRecordLookupResult.Incompatible ->
                return PersistentInstallResult.Failed(existing.reason)
            is PersistentRecordLookupResult.Failed ->
                return PersistentInstallResult.Failed(existing.reason, existing.throwable)
        }

        val currentCount = indexedEntryCount
            ?: return PersistentInstallResult.Failed("persistent indexed metadata unavailable")
        val nextValue = state.highWatermark + 1L
        if (nextValue <= 0L) {
            return PersistentInstallResult.Failed("persistent generation overflow")
        }
        val generation = PersistentGeneration(nextValue)
        val entry = PersistentBackendEntry(generation, record.detached())

        return when (
            val committed = indexed.installEntry(
                storeId = storeId,
                expectedRevision = revision,
                expectedHighWatermark = state.highWatermark,
                entry = entry
            )
        ) {
            is PersistentBackendMutationResult.Committed -> {
                val validation = acceptIndexedMetadata(
                    committed.metadata,
                    expectedHighWatermark = nextValue,
                    expectedEntryCount = currentCount + 1L
                )
                if (validation != null) {
                    PersistentInstallResult.Failed(validation)
                } else {
                    applyIndexedMetadata(committed.metadata)
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
                rejectInstall(record, committed.reason)
            PersistentBackendMutationResult.Corrupt ->
                PersistentInstallResult.Failed("persistent indexed store is corrupt")
            is PersistentBackendMutationResult.Incompatible ->
                PersistentInstallResult.Failed(committed.reason)
            is PersistentBackendMutationResult.Failed ->
                PersistentInstallResult.Failed(committed.reason, committed.throwable)
        }
    }

    private fun transitionIndexed(
        indexed: IndexedPersistentRecordMutationBackend,
        sourceId: PersistentEntityId,
        sourceGeneration: PersistentGeneration,
        replacement: PersistentRecord
    ): PersistentRecordTransitionResult {
        val current = when (val lookedUp = inspectResult(sourceId)) {
            PersistentRecordLookupResult.Missing ->
                return PersistentRecordTransitionResult.Rejected(
                    "persistent transition source is not live"
                )
            is PersistentRecordLookupResult.Found -> lookedUp.snapshot
            PersistentRecordLookupResult.Corrupt ->
                return PersistentRecordTransitionResult.Failed(
                    "persistent indexed store is corrupt"
                )
            is PersistentRecordLookupResult.Incompatible ->
                return PersistentRecordTransitionResult.Failed(lookedUp.reason)
            is PersistentRecordLookupResult.Failed ->
                return PersistentRecordTransitionResult.Failed(
                    lookedUp.reason,
                    lookedUp.throwable
                )
        }
        if (current.generation != sourceGeneration) {
            return PersistentRecordTransitionResult.Rejected(
                "persistent transition source generation is stale"
            )
        }

        if (replacement.id != sourceId) {
            when (val replacementLookup = inspectResult(replacement.id)) {
                PersistentRecordLookupResult.Missing -> Unit
                is PersistentRecordLookupResult.Found ->
                    return PersistentRecordTransitionResult.Rejected(
                        "persistent transition replacement entity is already live"
                    )
                PersistentRecordLookupResult.Corrupt ->
                    return PersistentRecordTransitionResult.Failed(
                        "persistent indexed store is corrupt"
                    )
                is PersistentRecordLookupResult.Incompatible ->
                    return PersistentRecordTransitionResult.Failed(replacementLookup.reason)
                is PersistentRecordLookupResult.Failed ->
                    return PersistentRecordTransitionResult.Failed(
                        replacementLookup.reason,
                        replacementLookup.throwable
                    )
            }
        }

        val currentCount = indexedEntryCount
            ?: return PersistentRecordTransitionResult.Failed(
                "persistent indexed metadata unavailable"
            )
        val replacementEntry = PersistentBackendEntry(
            sourceGeneration,
            replacement.detached()
        )

        return when (
            val committed = indexed.transitionEntry(
                storeId = storeId,
                expectedRevision = revision,
                expectedHighWatermark = state.highWatermark,
                sourceId = sourceId,
                sourceGeneration = sourceGeneration,
                replacement = replacementEntry
            )
        ) {
            is PersistentBackendMutationResult.Committed -> {
                val validation = acceptIndexedMetadata(
                    committed.metadata,
                    expectedHighWatermark = state.highWatermark,
                    expectedEntryCount = currentCount
                )
                if (validation != null) {
                    PersistentRecordTransitionResult.Failed(validation)
                } else {
                    applyIndexedMetadata(committed.metadata)
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
                PersistentRecordTransitionResult.Rejected(
                    "persistent backend revision changed"
                )
            is PersistentBackendMutationResult.Rejected ->
                PersistentRecordTransitionResult.Rejected(committed.reason)
            PersistentBackendMutationResult.Corrupt ->
                PersistentRecordTransitionResult.Failed(
                    "persistent indexed store is corrupt"
                )
            is PersistentBackendMutationResult.Incompatible ->
                PersistentRecordTransitionResult.Failed(committed.reason)
            is PersistentBackendMutationResult.Failed ->
                PersistentRecordTransitionResult.Failed(
                    committed.reason,
                    committed.throwable
                )
        }
    }

    private fun removeIndexed(
        indexed: IndexedPersistentRecordMutationBackend,
        id: PersistentEntityId,
        generation: PersistentGeneration
    ): PersistentMutationResult {
        val current = when (val lookedUp = inspectResult(id)) {
            PersistentRecordLookupResult.Missing ->
                return PersistentMutationResult.Rejected("persistent entity is not live")
            is PersistentRecordLookupResult.Found -> lookedUp.snapshot
            PersistentRecordLookupResult.Corrupt ->
                return PersistentMutationResult.Failed("persistent indexed store is corrupt")
            is PersistentRecordLookupResult.Incompatible ->
                return PersistentMutationResult.Failed(lookedUp.reason)
            is PersistentRecordLookupResult.Failed ->
                return PersistentMutationResult.Failed(
                    lookedUp.reason,
                    lookedUp.throwable
                )
        }
        if (current.generation != generation) {
            return PersistentMutationResult.Rejected(
                "persistent ownership generation is stale"
            )
        }

        val currentCount = indexedEntryCount
            ?: return PersistentMutationResult.Failed(
                "persistent indexed metadata unavailable"
            )
        if (currentCount <= 0L) {
            return PersistentMutationResult.Failed(
                "persistent indexed entry count is inconsistent"
            )
        }

        return when (
            val committed = indexed.removeEntry(
                storeId = storeId,
                expectedRevision = revision,
                expectedHighWatermark = state.highWatermark,
                id = id,
                generation = generation
            )
        ) {
            is PersistentBackendMutationResult.Committed -> {
                val validation = acceptIndexedMetadata(
                    committed.metadata,
                    expectedHighWatermark = state.highWatermark,
                    expectedEntryCount = currentCount - 1L
                )
                if (validation != null) {
                    PersistentMutationResult.Failed(validation)
                } else {
                    applyIndexedMetadata(committed.metadata)
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
                PersistentMutationResult.Rejected(committed.reason)
            PersistentBackendMutationResult.Corrupt ->
                PersistentMutationResult.Failed("persistent indexed store is corrupt")
            is PersistentBackendMutationResult.Incompatible ->
                PersistentMutationResult.Failed(committed.reason)
            is PersistentBackendMutationResult.Failed ->
                PersistentMutationResult.Failed(committed.reason, committed.throwable)
        }
    }

    private fun acceptIndexedMetadata(
        metadata: PersistentBackendMetadata,
        expectedHighWatermark: Long,
        expectedEntryCount: Long
    ): String? = when {
        metadata.revision <= revision ->
            "persistent indexed backend returned non-monotonic revision"
        metadata.highWatermark != expectedHighWatermark ->
            "persistent indexed backend returned unexpected high watermark"
        metadata.entryCount != expectedEntryCount ->
            "persistent indexed backend returned unexpected entry count"
        else -> null
    }

    private fun applyIndexedMetadata(metadata: PersistentBackendMetadata) {
        revision = metadata.revision
        state = PersistentBackendState(
            storeId = storeId,
            highWatermark = metadata.highWatermark,
            entries = emptyMap()
        )
        indexedEntryCount = metadata.entryCount
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
        indexedMutationBackend?.let { return removeIndexed(it, id, generation) }

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
        ): PersistentStoreOpenResult {
            val indexed = backend as? IndexedPersistentRecordMutationBackend
            if (indexed != null) {
                return openIndexed(foundation, storeId, indexed)
            }
            return openLegacy(foundation, storeId, backend)
        }

        private fun openIndexed(
            foundation: FoundationComposition,
            storeId: PersistentStoreId,
            backend: IndexedPersistentRecordMutationBackend
        ): PersistentStoreOpenResult = when (val loaded = backend.loadMetadata(storeId)) {
            PersistentBackendMetadataLoadResult.Missing ->
                PersistentStoreOpenResult.Opened(
                    PersistentRecordStore(
                        foundation = foundation,
                        storeId = storeId,
                        backend = backend,
                        initialRevision = 0L,
                        initialState = PersistentBackendState(storeId, 0L, emptyMap()),
                        initialIndexedEntryCount = 0L
                    )
                )

            is PersistentBackendMetadataLoadResult.Loaded ->
                PersistentStoreOpenResult.Opened(
                    PersistentRecordStore(
                        foundation = foundation,
                        storeId = storeId,
                        backend = backend,
                        initialRevision = loaded.metadata.revision,
                        initialState = PersistentBackendState(
                            storeId,
                            loaded.metadata.highWatermark,
                            emptyMap()
                        ),
                        initialIndexedEntryCount = loaded.metadata.entryCount
                    )
                )

            PersistentBackendMetadataLoadResult.Corrupt ->
                PersistentStoreOpenResult.Corrupt

            is PersistentBackendMetadataLoadResult.Incompatible ->
                PersistentStoreOpenResult.Incompatible(loaded.reason)

            is PersistentBackendMetadataLoadResult.Failed ->
                PersistentStoreOpenResult.Failed(loaded.reason, loaded.throwable)
        }

        private fun openLegacy(
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
