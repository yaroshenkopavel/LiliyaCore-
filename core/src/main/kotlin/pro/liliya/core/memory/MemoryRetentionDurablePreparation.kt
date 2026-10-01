package pro.liliya.core.memory

import java.time.Instant

sealed interface MemoryRetentionDurablePreparationResult {
    data object NothingToPrune : MemoryRetentionDurablePreparationResult

    data class Prepared(
        val snapshot: MemoryRetentionMultiTargetSnapshot
    ) : MemoryRetentionDurablePreparationResult

    data class Rejected(val reason: String) : MemoryRetentionDurablePreparationResult

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : MemoryRetentionDurablePreparationResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

/**
 * Persists a reviewed identity-only retention execution plan without executing it.
 *
 * Every non-empty reviewed plan is normalized into the single durable ordered-parent namespace,
 * including a one-target plan. The single-target transaction journal therefore remains only an
 * internal child execution primitive. This facade has no Authority, principal, Memory or mutation
 * dependency and cannot execute a prune.
 */
class MemoryRetentionDurablePlanPreparer(
    private val builder: MemoryRetentionLedgerExecutionPlanBuilder,
    private val parentJournal: PersistentMemoryRetentionMultiTargetJournal
) {
    fun prepare(
        id: MemoryRetentionExecutionPlanId,
        ledger: MemoryRetentionLedger,
        createdAt: Instant
    ): MemoryRetentionDurablePreparationResult = when (
        val planned = builder.build(id, ledger, createdAt)
    ) {
        MemoryRetentionLedgerExecutionPlanResult.NothingToPrune -> prepareNothing(id)
        is MemoryRetentionLedgerExecutionPlanResult.SingleTarget -> prepareParent(
            MemoryRetentionMultiTargetPlan(
                id = MemoryRetentionMultiTargetId(planned.plan.id.value),
                targets = planned.plan.targets,
                createdAt = planned.plan.createdAt
            )
        )
        is MemoryRetentionLedgerExecutionPlanResult.MultiTarget -> prepareParent(planned.plan)
    }

    private fun prepareNothing(
        id: MemoryRetentionExecutionPlanId
    ): MemoryRetentionDurablePreparationResult =
        if (parentJournal.inspect(MemoryRetentionMultiTargetId(id.value)) != null) {
            MemoryRetentionDurablePreparationResult.Rejected(
                "memory retention execution plan identity already has durable state"
            )
        } else {
            MemoryRetentionDurablePreparationResult.NothingToPrune
        }

    private fun prepareParent(
        plan: MemoryRetentionMultiTargetPlan
    ): MemoryRetentionDurablePreparationResult {
        reconcile(plan)?.let { return it }
        return when (val prepared = parentJournal.prepare(plan)) {
            is PersistentMemoryRetentionMultiTargetPrepareResult.Prepared ->
                MemoryRetentionDurablePreparationResult.Prepared(prepared.snapshot)
            is PersistentMemoryRetentionMultiTargetPrepareResult.Rejected ->
                reconcile(plan) ?: MemoryRetentionDurablePreparationResult.Rejected(prepared.reason)
            is PersistentMemoryRetentionMultiTargetPrepareResult.Failed ->
                reconcile(plan) ?: MemoryRetentionDurablePreparationResult.Failed(
                    prepared.reason,
                    prepared.throwable
                )
        }
    }

    private fun reconcile(
        plan: MemoryRetentionMultiTargetPlan
    ): MemoryRetentionDurablePreparationResult? {
        val existing = parentJournal.inspect(plan.id) ?: return null
        return if (
            existing.plan == plan &&
            existing.state == MemoryRetentionMultiTargetState.ACTIVE &&
            existing.nextIndex == 0
        ) {
            MemoryRetentionDurablePreparationResult.Prepared(existing)
        } else {
            MemoryRetentionDurablePreparationResult.Rejected(
                "memory retention execution plan identity already exists with conflicting plan or progress"
            )
        }
    }
}
