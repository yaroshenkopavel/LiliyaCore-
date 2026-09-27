package pro.liliya.core.memory

import java.time.Instant

@JvmInline
value class MemoryRetentionExecutionPlanId(val value: String) {
    init {
        require(value.isNotBlank()) { "memory retention execution plan id must not be blank" }
    }

    override fun toString(): String = value
}

sealed interface MemoryRetentionLedgerExecutionPlanResult {
    data object NothingToPrune : MemoryRetentionLedgerExecutionPlanResult

    data class SingleTarget(
        val plan: MemoryRetentionTransactionPlan
    ) : MemoryRetentionLedgerExecutionPlanResult

    data class MultiTarget(
        val plan: MemoryRetentionMultiTargetPlan
    ) : MemoryRetentionLedgerExecutionPlanResult
}

/**
 * Converts a reviewed shadow ledger into an identity-only execution plan without executing it.
 *
 * This boundary is deliberately pure: it has no Authority access, no Memory access and no durable
 * mutation capability. KEEP entries never become execution targets. PRUNE_CANDIDATE entries carry
 * only record identity, exact generation, retention class and disposition into the next layer.
 */
class MemoryRetentionLedgerExecutionPlanBuilder {
    fun build(
        id: MemoryRetentionExecutionPlanId,
        ledger: MemoryRetentionLedger,
        createdAt: Instant
    ): MemoryRetentionLedgerExecutionPlanResult {
        val entries = ledger.entries
        require(entries.map { it.recordId }.toSet().size == entries.size) {
            "memory retention execution planning requires unique memory record ids"
        }

        val targets = entries
            .asSequence()
            .filter { it.action == MemoryRetentionShadowAction.PRUNE_CANDIDATE }
            .map { entry ->
                check(entry.requiresPruneAuthority) {
                    "prune candidate must require retention Authority"
                }
                MemoryRetentionTransactionTarget(
                    recordId = entry.recordId,
                    generation = entry.generation,
                    retentionClass = entry.retentionClass,
                    disposition = entry.disposition
                )
            }
            .toList()

        return when (targets.size) {
            0 -> MemoryRetentionLedgerExecutionPlanResult.NothingToPrune
            1 -> MemoryRetentionLedgerExecutionPlanResult.SingleTarget(
                MemoryRetentionTransactionPlan(
                    id = MemoryRetentionTransactionId(id.value),
                    targets = targets,
                    createdAt = createdAt
                )
            )
            else -> MemoryRetentionLedgerExecutionPlanResult.MultiTarget(
                MemoryRetentionMultiTargetPlan(
                    id = MemoryRetentionMultiTargetId(id.value),
                    targets = targets,
                    createdAt = createdAt
                )
            )
        }
    }
}
