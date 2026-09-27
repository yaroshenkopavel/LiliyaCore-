package pro.liliya.core.persistence

/**
 * Optional granular mutation capability for indexed durable backends.
 *
 * This seam preserves the legacy PersistentRecordBackend snapshot contract while allowing capable
 * backends to mutate one exact record transactionally without receiving the complete store state.
 *
 * Callers supply the expected revision and high-watermark used to derive authenticated generation
 * bindings before persistence. The backend must reject races rather than silently renumbering.
 */
interface IndexedPersistentRecordMutationBackend : IndexedPersistentRecordReadBackend {
    fun installEntry(
        storeId: PersistentStoreId,
        expectedRevision: Long,
        expectedHighWatermark: Long,
        entry: PersistentBackendEntry
    ): PersistentBackendMutationResult

    fun transitionEntry(
        storeId: PersistentStoreId,
        expectedRevision: Long,
        expectedHighWatermark: Long,
        sourceId: PersistentEntityId,
        sourceGeneration: PersistentGeneration,
        replacement: PersistentBackendEntry
    ): PersistentBackendMutationResult

    fun removeEntry(
        storeId: PersistentStoreId,
        expectedRevision: Long,
        expectedHighWatermark: Long,
        id: PersistentEntityId,
        generation: PersistentGeneration
    ): PersistentBackendMutationResult
}

sealed interface PersistentBackendMutationResult {
    data class Committed(
        val metadata: PersistentBackendMetadata
    ) : PersistentBackendMutationResult

    /** The store revision/high-watermark changed since the caller built the mutation. */
    data object Conflict : PersistentBackendMutationResult

    /** The exact entity/generation preconditions do not describe the current live state. */
    data class Rejected(val reason: String) : PersistentBackendMutationResult

    data object Corrupt : PersistentBackendMutationResult

    data class Incompatible(val reason: String) : PersistentBackendMutationResult

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : PersistentBackendMutationResult
}
