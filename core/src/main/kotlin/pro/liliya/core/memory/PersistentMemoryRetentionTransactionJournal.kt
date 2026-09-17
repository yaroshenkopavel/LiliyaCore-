package pro.liliya.core.memory

import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentInstallResult
import pro.liliya.core.persistence.PersistentRecordBackend
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentRecordTransitionResult
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

sealed interface PersistentMemoryRetentionTransactionPrepareResult {
    data class Prepared(val snapshot: MemoryRetentionTransactionSnapshot) :
        PersistentMemoryRetentionTransactionPrepareResult
    data class Rejected(val reason: String) : PersistentMemoryRetentionTransactionPrepareResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        PersistentMemoryRetentionTransactionPrepareResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

sealed interface PersistentMemoryRetentionTransactionTransitionResult {
    data class Committed(val snapshot: MemoryRetentionTransactionSnapshot) :
        PersistentMemoryRetentionTransactionTransitionResult
    data class Rejected(val reason: String) : PersistentMemoryRetentionTransactionTransitionResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        PersistentMemoryRetentionTransactionTransitionResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

sealed interface PersistentMemoryRetentionTransactionJournalOpenResult {
    data class Opened(val journal: PersistentMemoryRetentionTransactionJournal) :
        PersistentMemoryRetentionTransactionJournalOpenResult
    data object Corrupt : PersistentMemoryRetentionTransactionJournalOpenResult
    data class Incompatible(val reason: String) : PersistentMemoryRetentionTransactionJournalOpenResult
    data class RestorationFailed(val reason: String) :
        PersistentMemoryRetentionTransactionJournalOpenResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        PersistentMemoryRetentionTransactionJournalOpenResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

/**
 * Durable retention execution journal.
 *
 * This component records recovery facts only. It cannot authorize or mutate Memory. In particular,
 * an AUTHORIZED record restored after process restart is atomically downgraded to RECOVERY_REQUIRED
 * before this journal is exposed, so a pre-crash authorization state can never be replayed as live
 * Authority by a later orchestration layer.
 */
class PersistentMemoryRetentionTransactionJournal private constructor(
    private val store: PersistentRecordStore
) {
    @Synchronized
    fun prepare(
        plan: MemoryRetentionTransactionPlan
    ): PersistentMemoryRetentionTransactionPrepareResult {
        val record = MemoryRetentionTransactionPersistentCodec.encode(
            plan,
            MemoryRetentionTransactionState.PLANNED
        )
        return when (val installed = store.install(record)) {
            is PersistentInstallResult.Installed ->
                PersistentMemoryRetentionTransactionPrepareResult.Prepared(
                    MemoryRetentionTransactionSnapshot(
                        plan = plan,
                        generation = MemoryRetentionTransactionGeneration(
                            installed.ownership.generation.value
                        ),
                        state = MemoryRetentionTransactionState.PLANNED
                    )
                )
            is PersistentInstallResult.Rejected ->
                PersistentMemoryRetentionTransactionPrepareResult.Rejected(installed.reason)
            is PersistentInstallResult.Failed ->
                PersistentMemoryRetentionTransactionPrepareResult.Failed(
                    "memory retention transaction durable prepare failed",
                    installed.throwable
                )
        }
    }

    @Synchronized
    fun transition(
        reference: MemoryRetentionTransactionReference,
        expectedState: MemoryRetentionTransactionState,
        nextState: MemoryRetentionTransactionState
    ): PersistentMemoryRetentionTransactionTransitionResult {
        if (!isAllowedTransition(expectedState, nextState)) {
            return PersistentMemoryRetentionTransactionTransitionResult.Rejected(
                "memory retention transaction state transition is not allowed"
            )
        }

        val entityId = PersistentEntityId(
            MemoryRetentionTransactionPersistentCodec.entityId(reference.transactionId)
        )
        val current = store.inspect(entityId)
            ?: return PersistentMemoryRetentionTransactionTransitionResult.Rejected(
                "memory retention transaction is not live"
            )
        if (current.generation.value != reference.generation.value) {
            return PersistentMemoryRetentionTransactionTransitionResult.Rejected(
                "memory retention transaction generation is stale"
            )
        }
        val decoded = when (val result = MemoryRetentionTransactionPersistentCodec.decode(current.record)) {
            is MemoryRetentionTransactionPersistentDecodeResult.Decoded -> result
            MemoryRetentionTransactionPersistentDecodeResult.Corrupt ->
                return PersistentMemoryRetentionTransactionTransitionResult.Failed(
                    "memory retention transaction became corrupt"
                )
            is MemoryRetentionTransactionPersistentDecodeResult.Incompatible ->
                return PersistentMemoryRetentionTransactionTransitionResult.Failed(result.reason)
        }
        if (decoded.plan.id != reference.transactionId || decoded.state != expectedState) {
            return PersistentMemoryRetentionTransactionTransitionResult.Rejected(
                "memory retention transaction state is stale"
            )
        }

        val replacement = MemoryRetentionTransactionPersistentCodec.encode(decoded.plan, nextState)
        return when (
            val transitioned = store.transitionExact(
                sourceId = entityId,
                sourceGeneration = PersistentGeneration(reference.generation.value),
                replacement = replacement
            )
        ) {
            is PersistentRecordTransitionResult.Committed ->
                PersistentMemoryRetentionTransactionTransitionResult.Committed(
                    MemoryRetentionTransactionSnapshot(decoded.plan, reference.generation, nextState)
                )
            is PersistentRecordTransitionResult.Rejected ->
                PersistentMemoryRetentionTransactionTransitionResult.Rejected(transitioned.reason)
            is PersistentRecordTransitionResult.Failed ->
                PersistentMemoryRetentionTransactionTransitionResult.Failed(
                    "memory retention transaction durable transition failed",
                    transitioned.throwable
                )
        }
    }

    fun inspect(id: MemoryRetentionTransactionId): MemoryRetentionTransactionSnapshot? {
        val record = store.inspect(
            PersistentEntityId(MemoryRetentionTransactionPersistentCodec.entityId(id))
        ) ?: return null
        val decoded = MemoryRetentionTransactionPersistentCodec.decode(record.record)
            as? MemoryRetentionTransactionPersistentDecodeResult.Decoded ?: return null
        return MemoryRetentionTransactionSnapshot(
            decoded.plan,
            MemoryRetentionTransactionGeneration(record.generation.value),
            decoded.state
        )
    }

    fun snapshotEntries(): List<MemoryRetentionTransactionSnapshot> = store.snapshotEntries().mapNotNull {
        val decoded = MemoryRetentionTransactionPersistentCodec.decode(it.record)
            as? MemoryRetentionTransactionPersistentDecodeResult.Decoded ?: return@mapNotNull null
        MemoryRetentionTransactionSnapshot(
            decoded.plan,
            MemoryRetentionTransactionGeneration(it.generation.value),
            decoded.state
        )
    }.sortedWith(compareBy({ it.plan.createdAt }, { it.plan.id.value }))

    companion object {
        fun open(
            foundation: FoundationComposition,
            storeId: PersistentStoreId,
            backend: PersistentRecordBackend
        ): PersistentMemoryRetentionTransactionJournalOpenResult = when (
            val opened = PersistentRecordStore.open(foundation, storeId, backend)
        ) {
            is PersistentStoreOpenResult.Opened -> restoreOpened(opened.store)
            PersistentStoreOpenResult.Corrupt ->
                PersistentMemoryRetentionTransactionJournalOpenResult.Corrupt
            is PersistentStoreOpenResult.Incompatible ->
                PersistentMemoryRetentionTransactionJournalOpenResult.Incompatible(opened.reason)
            is PersistentStoreOpenResult.Failed ->
                PersistentMemoryRetentionTransactionJournalOpenResult.Failed(
                    "memory retention transaction backend open failed",
                    opened.throwable
                )
        }

        private fun restoreOpened(
            store: PersistentRecordStore
        ): PersistentMemoryRetentionTransactionJournalOpenResult {
            for (entry in store.snapshotEntries()) {
                when (val decoded = MemoryRetentionTransactionPersistentCodec.decode(entry.record)) {
                    MemoryRetentionTransactionPersistentDecodeResult.Corrupt ->
                        return PersistentMemoryRetentionTransactionJournalOpenResult.Corrupt
                    is MemoryRetentionTransactionPersistentDecodeResult.Incompatible ->
                        return PersistentMemoryRetentionTransactionJournalOpenResult.Incompatible(
                            decoded.reason
                        )
                    is MemoryRetentionTransactionPersistentDecodeResult.Decoded -> {
                        if (decoded.state == MemoryRetentionTransactionState.AUTHORIZED) {
                            val replacement = MemoryRetentionTransactionPersistentCodec.encode(
                                decoded.plan,
                                MemoryRetentionTransactionState.RECOVERY_REQUIRED
                            )
                            when (
                                val transitioned = store.transitionExact(
                                    sourceId = entry.record.id,
                                    sourceGeneration = entry.generation,
                                    replacement = replacement
                                )
                            ) {
                                is PersistentRecordTransitionResult.Committed -> Unit
                                is PersistentRecordTransitionResult.Rejected ->
                                    return PersistentMemoryRetentionTransactionJournalOpenResult
                                        .RestorationFailed(
                                            "authorized retention transaction could not be invalidated after restart: ${transitioned.reason}"
                                        )
                                is PersistentRecordTransitionResult.Failed ->
                                    return PersistentMemoryRetentionTransactionJournalOpenResult.Failed(
                                        "authorized retention transaction recovery transition failed",
                                        transitioned.throwable
                                    )
                            }
                        }
                    }
                }
            }
            return PersistentMemoryRetentionTransactionJournalOpenResult.Opened(
                PersistentMemoryRetentionTransactionJournal(store)
            )
        }

        private fun isAllowedTransition(
            from: MemoryRetentionTransactionState,
            to: MemoryRetentionTransactionState
        ): Boolean = when (from) {
            MemoryRetentionTransactionState.PLANNED ->
                to == MemoryRetentionTransactionState.AUTHORIZED ||
                    to == MemoryRetentionTransactionState.REJECTED
            MemoryRetentionTransactionState.AUTHORIZED ->
                to == MemoryRetentionTransactionState.COMMITTED ||
                    to == MemoryRetentionTransactionState.REJECTED ||
                    to == MemoryRetentionTransactionState.RECOVERY_REQUIRED
            MemoryRetentionTransactionState.RECOVERY_REQUIRED ->
                to == MemoryRetentionTransactionState.AUTHORIZED ||
                    to == MemoryRetentionTransactionState.COMMITTED ||
                    to == MemoryRetentionTransactionState.REJECTED
            MemoryRetentionTransactionState.COMMITTED,
            MemoryRetentionTransactionState.REJECTED -> false
        }
    }
}
