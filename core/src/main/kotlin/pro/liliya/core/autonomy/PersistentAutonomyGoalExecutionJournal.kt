package pro.liliya.core.autonomy

import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentInstallResult
import pro.liliya.core.persistence.PersistentRecordBackend
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentRecordTransitionResult
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

sealed interface PersistentAutonomyGoalExecutionPrepareResult {
    data class Prepared(val snapshot: AutonomyGoalExecutionSnapshot) : PersistentAutonomyGoalExecutionPrepareResult
    data class Rejected(val reason: String) : PersistentAutonomyGoalExecutionPrepareResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentAutonomyGoalExecutionPrepareResult
}

sealed interface PersistentAutonomyGoalExecutionTransitionResult {
    data class Committed(val snapshot: AutonomyGoalExecutionSnapshot) : PersistentAutonomyGoalExecutionTransitionResult
    data class Rejected(val reason: String) : PersistentAutonomyGoalExecutionTransitionResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentAutonomyGoalExecutionTransitionResult
}

sealed interface PersistentAutonomyGoalExecutionJournalOpenResult {
    data class Opened(val journal: PersistentAutonomyGoalExecutionJournal) : PersistentAutonomyGoalExecutionJournalOpenResult
    data object Corrupt : PersistentAutonomyGoalExecutionJournalOpenResult
    data class Incompatible(val reason: String) : PersistentAutonomyGoalExecutionJournalOpenResult
    data class RestorationFailed(val reason: String) : PersistentAutonomyGoalExecutionJournalOpenResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentAutonomyGoalExecutionJournalOpenResult
}

/**
 * Durable one-shot recovery journal for bounded autonomy execution.
 *
 * This journal never authorizes or executes. On restart, stale AUTHORIZED state is downgraded to
 * RECOVERY_REQUIRED so fresh Authority is mandatory. EXECUTING becomes OUTCOME_UNKNOWN because the
 * external side effect may already have happened; that state is terminal and cannot be replayed.
 */
class PersistentAutonomyGoalExecutionJournal private constructor(
    private val store: PersistentRecordStore
) {
    @Synchronized
    fun prepare(plan: AutonomyGoalExecutionPlan): PersistentAutonomyGoalExecutionPrepareResult {
        val record = AutonomyGoalExecutionPersistentCodec.encode(plan, AutonomyGoalExecutionState.PLANNED)
        return when (val installed = store.install(record)) {
            is PersistentInstallResult.Installed -> PersistentAutonomyGoalExecutionPrepareResult.Prepared(
                AutonomyGoalExecutionSnapshot(
                    plan = plan,
                    generation = AutonomyGoalExecutionGeneration(installed.ownership.generation.value),
                    state = AutonomyGoalExecutionState.PLANNED
                )
            )
            is PersistentInstallResult.Rejected -> PersistentAutonomyGoalExecutionPrepareResult.Rejected(installed.reason)
            is PersistentInstallResult.Failed -> PersistentAutonomyGoalExecutionPrepareResult.Failed(
                "autonomy execution durable prepare failed",
                installed.throwable
            )
        }
    }

    @Synchronized
    fun transition(
        reference: AutonomyGoalExecutionReference,
        expectedState: AutonomyGoalExecutionState,
        nextState: AutonomyGoalExecutionState
    ): PersistentAutonomyGoalExecutionTransitionResult {
        if (!isAllowedTransition(expectedState, nextState)) {
            return PersistentAutonomyGoalExecutionTransitionResult.Rejected(
                "autonomy execution state transition is not allowed"
            )
        }
        val entityId = PersistentEntityId(AutonomyGoalExecutionPersistentCodec.entityId(reference.transactionId))
        val current = store.inspect(entityId)
            ?: return PersistentAutonomyGoalExecutionTransitionResult.Rejected(
                "autonomy execution transaction is not live"
            )
        if (current.generation.value != reference.generation.value) {
            return PersistentAutonomyGoalExecutionTransitionResult.Rejected(
                "autonomy execution transaction generation is stale"
            )
        }
        val decoded = when (val result = AutonomyGoalExecutionPersistentCodec.decode(current.record)) {
            is AutonomyGoalExecutionPersistentDecodeResult.Decoded -> result
            AutonomyGoalExecutionPersistentDecodeResult.Corrupt ->
                return PersistentAutonomyGoalExecutionTransitionResult.Failed(
                    "autonomy execution transaction became corrupt"
                )
            is AutonomyGoalExecutionPersistentDecodeResult.Incompatible ->
                return PersistentAutonomyGoalExecutionTransitionResult.Failed(result.reason)
        }
        if (decoded.plan.id != reference.transactionId || decoded.state != expectedState) {
            return PersistentAutonomyGoalExecutionTransitionResult.Rejected(
                "autonomy execution transaction state is stale"
            )
        }

        val replacement = AutonomyGoalExecutionPersistentCodec.encode(decoded.plan, nextState)
        return when (
            val transitioned = store.transitionExact(
                sourceId = entityId,
                sourceGeneration = PersistentGeneration(reference.generation.value),
                replacement = replacement
            )
        ) {
            is PersistentRecordTransitionResult.Committed ->
                PersistentAutonomyGoalExecutionTransitionResult.Committed(
                    AutonomyGoalExecutionSnapshot(decoded.plan, reference.generation, nextState)
                )
            is PersistentRecordTransitionResult.Rejected ->
                PersistentAutonomyGoalExecutionTransitionResult.Rejected(transitioned.reason)
            is PersistentRecordTransitionResult.Failed ->
                PersistentAutonomyGoalExecutionTransitionResult.Failed(
                    "autonomy execution durable transition failed",
                    transitioned.throwable
                )
        }
    }

    fun inspect(id: AutonomyGoalExecutionTransactionId): AutonomyGoalExecutionSnapshot? {
        val current = store.inspect(
            PersistentEntityId(AutonomyGoalExecutionPersistentCodec.entityId(id))
        ) ?: return null
        val decoded = AutonomyGoalExecutionPersistentCodec.decode(current.record)
            as? AutonomyGoalExecutionPersistentDecodeResult.Decoded ?: return null
        return AutonomyGoalExecutionSnapshot(
            decoded.plan,
            AutonomyGoalExecutionGeneration(current.generation.value),
            decoded.state
        )
    }

    companion object {
        fun open(
            foundation: FoundationComposition,
            storeId: PersistentStoreId,
            backend: PersistentRecordBackend
        ): PersistentAutonomyGoalExecutionJournalOpenResult = when (
            val opened = PersistentRecordStore.open(foundation, storeId, backend)
        ) {
            is PersistentStoreOpenResult.Opened -> restoreOpened(opened.store)
            PersistentStoreOpenResult.Corrupt -> PersistentAutonomyGoalExecutionJournalOpenResult.Corrupt
            is PersistentStoreOpenResult.Incompatible ->
                PersistentAutonomyGoalExecutionJournalOpenResult.Incompatible(opened.reason)
            is PersistentStoreOpenResult.Failed -> PersistentAutonomyGoalExecutionJournalOpenResult.Failed(
                "autonomy execution backend open failed",
                opened.throwable
            )
        }

        private fun restoreOpened(
            store: PersistentRecordStore
        ): PersistentAutonomyGoalExecutionJournalOpenResult {
            for (entry in store.snapshotEntries()) {
                when (val decoded = AutonomyGoalExecutionPersistentCodec.decode(entry.record)) {
                    AutonomyGoalExecutionPersistentDecodeResult.Corrupt ->
                        return PersistentAutonomyGoalExecutionJournalOpenResult.Corrupt
                    is AutonomyGoalExecutionPersistentDecodeResult.Incompatible ->
                        return PersistentAutonomyGoalExecutionJournalOpenResult.Incompatible(decoded.reason)
                    is AutonomyGoalExecutionPersistentDecodeResult.Decoded -> {
                        val restoredState = when (decoded.state) {
                            AutonomyGoalExecutionState.AUTHORIZED -> AutonomyGoalExecutionState.RECOVERY_REQUIRED
                            AutonomyGoalExecutionState.EXECUTING -> AutonomyGoalExecutionState.OUTCOME_UNKNOWN
                            else -> null
                        } ?: continue
                        val replacement = AutonomyGoalExecutionPersistentCodec.encode(decoded.plan, restoredState)
                        when (
                            val transitioned = store.transitionExact(
                                sourceId = entry.record.id,
                                sourceGeneration = entry.generation,
                                replacement = replacement
                            )
                        ) {
                            is PersistentRecordTransitionResult.Committed -> Unit
                            is PersistentRecordTransitionResult.Rejected ->
                                return PersistentAutonomyGoalExecutionJournalOpenResult.RestorationFailed(
                                    "autonomy execution restart invalidation rejected: ${transitioned.reason}"
                                )
                            is PersistentRecordTransitionResult.Failed ->
                                return PersistentAutonomyGoalExecutionJournalOpenResult.Failed(
                                    "autonomy execution restart invalidation failed",
                                    transitioned.throwable
                                )
                        }
                    }
                }
            }
            return PersistentAutonomyGoalExecutionJournalOpenResult.Opened(
                PersistentAutonomyGoalExecutionJournal(store)
            )
        }

        private fun isAllowedTransition(
            from: AutonomyGoalExecutionState,
            to: AutonomyGoalExecutionState
        ): Boolean = when (from) {
            AutonomyGoalExecutionState.PLANNED ->
                to == AutonomyGoalExecutionState.AUTHORIZED || to == AutonomyGoalExecutionState.REJECTED
            AutonomyGoalExecutionState.AUTHORIZED ->
                to == AutonomyGoalExecutionState.EXECUTING ||
                    to == AutonomyGoalExecutionState.REJECTED ||
                    to == AutonomyGoalExecutionState.RECOVERY_REQUIRED
            AutonomyGoalExecutionState.RECOVERY_REQUIRED ->
                to == AutonomyGoalExecutionState.AUTHORIZED || to == AutonomyGoalExecutionState.REJECTED
            AutonomyGoalExecutionState.EXECUTING ->
                to == AutonomyGoalExecutionState.SUCCEEDED ||
                    to == AutonomyGoalExecutionState.FAILED ||
                    to == AutonomyGoalExecutionState.OUTCOME_UNKNOWN
            AutonomyGoalExecutionState.SUCCEEDED,
            AutonomyGoalExecutionState.FAILED,
            AutonomyGoalExecutionState.REJECTED,
            AutonomyGoalExecutionState.OUTCOME_UNKNOWN -> false
        }
    }
}
