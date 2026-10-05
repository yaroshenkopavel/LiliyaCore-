package pro.liliya.core.autonomy

import java.time.Duration
import java.time.Instant

@JvmInline
value class AutonomyGoalId(val value: String) {
    init { require(value.isNotBlank()) { "autonomy goal id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class AutonomyGoalScope(val value: String) {
    init { require(value.isNotBlank()) { "autonomy goal scope must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class AutonomyActionClass(val value: String) {
    init { require(value.isNotBlank()) { "autonomy action class must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class AutonomyGoalProposalId(val value: String) {
    init { require(value.isNotBlank()) { "autonomy goal proposal id must not be blank" } }
    override fun toString(): String = value
}

data class AutonomyGoalBudget(
    val maxSteps: Int,
    val maxWallClock: Duration,
    val maxExecutions: Int
) {
    init {
        require(maxSteps > 0) { "autonomy goal max steps must be positive" }
        require(!maxWallClock.isNegative && !maxWallClock.isZero) {
            "autonomy goal wall-clock budget must be positive"
        }
        require(maxExecutions > 0) { "autonomy goal max executions must be positive" }
    }
}

/**
 * Owner-defined boundary for bounded autonomy.
 *
 * This envelope describes what Core may deliberate about. It is not Authority and grants no
 * permission to execute an action.
 */
data class AutonomyGoalEnvelope(
    val id: AutonomyGoalId,
    val scope: AutonomyGoalScope,
    val createdAt: Instant,
    val expiresAt: Instant,
    val allowedActionClasses: Set<AutonomyActionClass>,
    val budget: AutonomyGoalBudget
) {
    init {
        require(expiresAt.isAfter(createdAt)) { "autonomy goal expiry must be after creation" }
        require(allowedActionClasses.isNotEmpty()) { "autonomy goal must allow at least one action class" }
    }
}

/**
 * Immutable evidence for a possible local next step. A proposal is never permission.
 */
data class AutonomyGoalProposal(
    val id: AutonomyGoalProposalId,
    val goalId: AutonomyGoalId,
    val scope: AutonomyGoalScope,
    val actionClass: AutonomyActionClass,
    val preference: Int
)

data class AutonomyGoalUsage(
    val completedSteps: Int,
    val completedExecutions: Int
) {
    init {
        require(completedSteps >= 0) { "completed autonomy steps must not be negative" }
        require(completedExecutions >= 0) { "completed autonomy executions must not be negative" }
    }
}

data class AutonomyGoalDeliberationPolicy(
    val maxProposals: Int
) {
    init { require(maxProposals > 0) { "autonomy deliberation proposal bound must be positive" } }
}

enum class AutonomyGoalStopReason {
    GOAL_NOT_STARTED,
    GOAL_EXPIRED,
    GOAL_SCOPE_MISMATCH,
    STEP_BUDGET_EXHAUSTED,
    WALL_CLOCK_BUDGET_EXHAUSTED,
    EXECUTION_BUDGET_EXHAUSTED,
    PROPOSAL_LIMIT_EXCEEDED,
    DUPLICATE_PROPOSAL_ID,
    PROPOSAL_GOAL_MISMATCH,
    PROPOSAL_SCOPE_MISMATCH,
    ACTION_CLASS_NOT_ALLOWED,
    NO_PROPOSALS
}

sealed interface AutonomyGoalDeliberationResult {
    data class Selected(val proposal: AutonomyGoalProposal) : AutonomyGoalDeliberationResult
    data class Stopped(val reason: AutonomyGoalStopReason) : AutonomyGoalDeliberationResult
}

/**
 * Pure deterministic selection boundary for one bounded autonomy step.
 *
 * No Authority, Execution, Memory, persistence, scheduler or background work is reachable from
 * this type. It only validates a goal/proposal snapshot and selects immutable proposal evidence.
 */
class AutonomyGoalDeliberator(
    private val policy: AutonomyGoalDeliberationPolicy
) {
    fun deliberate(
        goal: AutonomyGoalEnvelope,
        currentScope: AutonomyGoalScope,
        proposals: List<AutonomyGoalProposal>,
        usage: AutonomyGoalUsage,
        now: Instant
    ): AutonomyGoalDeliberationResult {
        if (now.isBefore(goal.createdAt)) return stopped(AutonomyGoalStopReason.GOAL_NOT_STARTED)
        if (!now.isBefore(goal.expiresAt)) return stopped(AutonomyGoalStopReason.GOAL_EXPIRED)
        if (currentScope != goal.scope) return stopped(AutonomyGoalStopReason.GOAL_SCOPE_MISMATCH)
        if (usage.completedSteps >= goal.budget.maxSteps) {
            return stopped(AutonomyGoalStopReason.STEP_BUDGET_EXHAUSTED)
        }
        if (Duration.between(goal.createdAt, now) >= goal.budget.maxWallClock) {
            return stopped(AutonomyGoalStopReason.WALL_CLOCK_BUDGET_EXHAUSTED)
        }
        if (usage.completedExecutions >= goal.budget.maxExecutions) {
            return stopped(AutonomyGoalStopReason.EXECUTION_BUDGET_EXHAUSTED)
        }
        if (proposals.size > policy.maxProposals) {
            return stopped(AutonomyGoalStopReason.PROPOSAL_LIMIT_EXCEEDED)
        }
        if (proposals.isEmpty()) return stopped(AutonomyGoalStopReason.NO_PROPOSALS)

        val seenIds = HashSet<AutonomyGoalProposalId>(proposals.size)
        for (proposal in proposals) {
            if (!seenIds.add(proposal.id)) {
                return stopped(AutonomyGoalStopReason.DUPLICATE_PROPOSAL_ID)
            }
            if (proposal.goalId != goal.id) {
                return stopped(AutonomyGoalStopReason.PROPOSAL_GOAL_MISMATCH)
            }
            if (proposal.scope != goal.scope) {
                return stopped(AutonomyGoalStopReason.PROPOSAL_SCOPE_MISMATCH)
            }
            if (proposal.actionClass !in goal.allowedActionClasses) {
                return stopped(AutonomyGoalStopReason.ACTION_CLASS_NOT_ALLOWED)
            }
        }

        val selected = proposals.minWithOrNull(
            compareByDescending<AutonomyGoalProposal> { it.preference }
                .thenBy { it.id.value }
        ) ?: return stopped(AutonomyGoalStopReason.NO_PROPOSALS)

        return AutonomyGoalDeliberationResult.Selected(selected)
    }

    private fun stopped(reason: AutonomyGoalStopReason) =
        AutonomyGoalDeliberationResult.Stopped(reason)
}
