package pro.liliya.core.persistence

internal data class PersistentBackendEntry(
    val generation: PersistentGeneration,
    val record: PersistentRecord
)

internal data class PersistentBackendState(
    val storeId: PersistentStoreId,
    val highWatermark: Long,
    val entries: Map<PersistentEntityId, PersistentBackendEntry>
)

sealed interface PersistentBackendLoadResult {
    data object Missing : PersistentBackendLoadResult
    data class Loaded internal constructor(
        internal val revision: Long,
        internal val state: PersistentBackendState
    ) : PersistentBackendLoadResult
    data object Corrupt : PersistentBackendLoadResult
    data class Incompatible(val reason: String) : PersistentBackendLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentBackendLoadResult
}

sealed interface PersistentBackendCommitResult {
    data class Committed internal constructor(internal val revision: Long) : PersistentBackendCommitResult
    data object Conflict : PersistentBackendCommitResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentBackendCommitResult
}

interface PersistentRecordBackend {
    fun load(storeId: PersistentStoreId): PersistentBackendLoadResult

    internal fun commit(
        storeId: PersistentStoreId,
        expectedRevision: Long,
        state: PersistentBackendState
    ): PersistentBackendCommitResult
}

/**
 * Contract backend used by core tests and development compositions.
 * State is durable across store reopen only while this backend instance remains alive.
 */
internal class InMemoryPersistentRecordBackend : PersistentRecordBackend {
    private data class Stored(
        val revision: Long,
        val state: PersistentBackendState
    )

    private val stores = mutableMapOf<PersistentStoreId, Stored>()
    private val forcedLoad = mutableMapOf<PersistentStoreId, PersistentBackendLoadResult>()
    private var failNextCommit: Throwable? = null

    @Synchronized
    override fun load(storeId: PersistentStoreId): PersistentBackendLoadResult {
        forcedLoad[storeId]?.let { return it }
        val stored = stores[storeId] ?: return PersistentBackendLoadResult.Missing
        return PersistentBackendLoadResult.Loaded(
            revision = stored.revision,
            state = stored.state.detached()
        )
    }

    @Synchronized
    override fun commit(
        storeId: PersistentStoreId,
        expectedRevision: Long,
        state: PersistentBackendState
    ): PersistentBackendCommitResult {
        failNextCommit?.let { throwable ->
            failNextCommit = null
            return PersistentBackendCommitResult.Failed("persistent backend commit failed", throwable)
        }

        val currentRevision = stores[storeId]?.revision ?: 0L
        if (currentRevision != expectedRevision) return PersistentBackendCommitResult.Conflict

        val nextRevision = currentRevision + 1
        stores[storeId] = Stored(nextRevision, state.detached())
        return PersistentBackendCommitResult.Committed(nextRevision)
    }

    @Synchronized
    fun failNextCommit(throwable: Throwable = IllegalStateException("forced persistent backend failure")) {
        failNextCommit = throwable
    }

    @Synchronized
    fun forceLoad(storeId: PersistentStoreId, result: PersistentBackendLoadResult) {
        forcedLoad[storeId] = result
    }

    @Synchronized
    fun clearForcedLoad(storeId: PersistentStoreId) {
        forcedLoad.remove(storeId)
    }

    private fun PersistentBackendState.detached(): PersistentBackendState = copy(
        entries = entries.mapValues { (_, entry) ->
            entry.copy(record = entry.record.detached())
        }.toMap()
    )

    private fun PersistentRecord.detached(): PersistentRecord = copy(
        payload = PersistentPayload(payload.copyBytes())
    )
}
