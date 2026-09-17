package pro.liliya.core.autonomy

import java.time.Instant
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.execution.ExecutionActionId

@JvmInline
value class AutonomyGoalExecutionTransactionId(val value: String) {
    init { require(value.isNotBlank()) { "autonomy execution transaction id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class AutonomyGoalExecutionGeneration(val value: Long) {
    init { require(value > 0L) { "autonomy execution generation must be positive" } }
    override fun toString(): String = value.toString()
}

data class AutonomyGoalExecutionReference(
    val transactionId: AutonomyGoalExecutionTransactionId,
    val generation: AutonomyGoalExecutionGeneration
)

/**
 * Identity-only durable plan for one proposal execution attempt.
 *
 * No private objective, trigger, memory, reasoning or model output is stored here. The plan is
 * recovery evidence only and grants no Authority.
 */
data class AutonomyGoalExecutionPlan(
    val id: AutonomyGoalExecutionTransactionId,
    val identity: AutonomyGoalAuthorityIdentity,
    val principal: AuthorityPrincipal,
    val capability: CapabilityId,
    val actionId: ExecutionActionId,
    val createdAt: Instant
)

enum class AutonomyGoalExecutionState {
    PLANNED,
    AUTHORIZED,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    REJECTED,
    RECOVERY_REQUIRED,
    OUTCOME_UNKNOWN
}

data class AutonomyGoalExecutionSnapshot(
    val plan: AutonomyGoalExecutionPlan,
    val generation: AutonomyGoalExecutionGeneration,
    val state: AutonomyGoalExecutionState
) {
    val reference: AutonomyGoalExecutionReference
        get() = AutonomyGoalExecutionReference(plan.id, generation)
}
