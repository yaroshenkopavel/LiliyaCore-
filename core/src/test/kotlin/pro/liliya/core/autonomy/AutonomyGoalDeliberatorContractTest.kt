package pro.liliya.core.autonomy

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AutonomyGoalDeliberatorContractTest {
    private val createdAt = Instant.parse("2026-09-17T09:00:00Z")
    private val scope = AutonomyGoalScope("local-core-maintenance")
    private val allowed = AutonomyActionClass("local-read")

    private fun goal(
        expiresAt: Instant = createdAt.plusSeconds(3600),
        budget: AutonomyGoalBudget = AutonomyGoalBudget(
            maxSteps = 3,
            maxWallClock = Duration.ofMinutes(30),
            maxExecutions = 2
        )
    ) = AutonomyGoalEnvelope(
        id = AutonomyGoalId("goal-1"),
        scope = scope,
        createdAt = createdAt,
        expiresAt = expiresAt,
        allowedActionClasses = setOf(allowed),
        budget = budget
    )

    private fun proposal(
        id: String,
        preference: Int,
        goalId: AutonomyGoalId = AutonomyGoalId("goal-1"),
        proposalScope: AutonomyGoalScope = scope,
        actionClass: AutonomyActionClass = allowed
    ) = AutonomyGoalProposal(
        id = AutonomyGoalProposalId(id),
        goalId = goalId,
        scope = proposalScope,
        actionClass = actionClass,
        preference = preference
    )

    private fun deliberate(
        goal: AutonomyGoalEnvelope = goal(),
        currentScope: AutonomyGoalScope = scope,
        proposals: List<AutonomyGoalProposal>,
        usage: AutonomyGoalUsage = AutonomyGoalUsage(0, 0),
        now: Instant = createdAt.plusSeconds(60),
        maxProposals: Int = 8
    ) = AutonomyGoalDeliberator(AutonomyGoalDeliberationPolicy(maxProposals)).deliberate(
        goal = goal,
        currentScope = currentScope,
        proposals = proposals,
        usage = usage,
        now = now
    )

    @Test
    fun selection_is_deterministic_independent_of_input_order() {
        val candidates = listOf(
            proposal("proposal-b", preference = 10),
            proposal("proposal-a", preference = 10),
            proposal("proposal-c", preference = 5)
        )

        val forward = assertIs<AutonomyGoalDeliberationResult.Selected>(
            deliberate(proposals = candidates)
        )
        val reverse = assertIs<AutonomyGoalDeliberationResult.Selected>(
            deliberate(proposals = candidates.reversed())
        )

        assertEquals(AutonomyGoalProposalId("proposal-a"), forward.proposal.id)
        assertEquals(forward, reverse)
    }

    @Test
    fun expired_or_out_of_scope_goal_stops_before_selection() {
        val candidates = listOf(proposal("proposal-a", preference = 10))

        val expired = assertIs<AutonomyGoalDeliberationResult.Stopped>(
            deliberate(
                goal = goal(expiresAt = createdAt.plusSeconds(30)),
                proposals = candidates,
                now = createdAt.plusSeconds(30)
            )
        )
        val outOfScope = assertIs<AutonomyGoalDeliberationResult.Stopped>(
            deliberate(
                currentScope = AutonomyGoalScope("different-owner-scope"),
                proposals = candidates
            )
        )

        assertEquals(AutonomyGoalStopReason.GOAL_EXPIRED, expired.reason)
        assertEquals(AutonomyGoalStopReason.GOAL_SCOPE_MISMATCH, outOfScope.reason)
    }

    @Test
    fun every_goal_budget_fails_closed_at_boundary() {
        val candidate = listOf(proposal("proposal-a", preference = 1))
        val boundedGoal = goal(
            budget = AutonomyGoalBudget(
                maxSteps = 2,
                maxWallClock = Duration.ofMinutes(5),
                maxExecutions = 1
            )
        )

        val steps = assertIs<AutonomyGoalDeliberationResult.Stopped>(
            deliberate(
                goal = boundedGoal,
                proposals = candidate,
                usage = AutonomyGoalUsage(completedSteps = 2, completedExecutions = 0)
            )
        )
        val wallClock = assertIs<AutonomyGoalDeliberationResult.Stopped>(
            deliberate(
                goal = boundedGoal,
                proposals = candidate,
                now = createdAt.plus(Duration.ofMinutes(5))
            )
        )
        val executions = assertIs<AutonomyGoalDeliberationResult.Stopped>(
            deliberate(
                goal = boundedGoal,
                proposals = candidate,
                usage = AutonomyGoalUsage(completedSteps = 0, completedExecutions = 1)
            )
        )

        assertEquals(AutonomyGoalStopReason.STEP_BUDGET_EXHAUSTED, steps.reason)
        assertEquals(AutonomyGoalStopReason.WALL_CLOCK_BUDGET_EXHAUSTED, wallClock.reason)
        assertEquals(AutonomyGoalStopReason.EXECUTION_BUDGET_EXHAUSTED, executions.reason)
    }

    @Test
    fun proposal_input_is_bounded_and_duplicate_ids_fail_closed() {
        val tooMany = assertIs<AutonomyGoalDeliberationResult.Stopped>(
            deliberate(
                proposals = listOf(
                    proposal("proposal-a", 3),
                    proposal("proposal-b", 2),
                    proposal("proposal-c", 1)
                ),
                maxProposals = 2
            )
        )
        val duplicate = assertIs<AutonomyGoalDeliberationResult.Stopped>(
            deliberate(
                proposals = listOf(
                    proposal("proposal-a", 3),
                    proposal("proposal-a", 2)
                )
            )
        )

        assertEquals(AutonomyGoalStopReason.PROPOSAL_LIMIT_EXCEEDED, tooMany.reason)
        assertEquals(AutonomyGoalStopReason.DUPLICATE_PROPOSAL_ID, duplicate.reason)
    }

    @Test
    fun mismatched_goal_scope_or_action_class_fails_closed() {
        val wrongGoal = assertIs<AutonomyGoalDeliberationResult.Stopped>(
            deliberate(
                proposals = listOf(
                    proposal("proposal-a", 1, goalId = AutonomyGoalId("goal-2"))
                )
            )
        )
        val wrongScope = assertIs<AutonomyGoalDeliberationResult.Stopped>(
            deliberate(
                proposals = listOf(
                    proposal("proposal-a", 1, proposalScope = AutonomyGoalScope("other"))
                )
            )
        )
        val forbiddenAction = assertIs<AutonomyGoalDeliberationResult.Stopped>(
            deliberate(
                proposals = listOf(
                    proposal("proposal-a", 1, actionClass = AutonomyActionClass("local-write"))
                )
            )
        )

        assertEquals(AutonomyGoalStopReason.PROPOSAL_GOAL_MISMATCH, wrongGoal.reason)
        assertEquals(AutonomyGoalStopReason.PROPOSAL_SCOPE_MISMATCH, wrongScope.reason)
        assertEquals(AutonomyGoalStopReason.ACTION_CLASS_NOT_ALLOWED, forbiddenAction.reason)
    }

    @Test
    fun proposal_selection_produces_evidence_only_and_has_no_execution_surface() {
        val selected = assertIs<AutonomyGoalDeliberationResult.Selected>(
            deliberate(proposals = listOf(proposal("proposal-a", 1)))
        )

        assertEquals(AutonomyGoalProposalId("proposal-a"), selected.proposal.id)
        assertEquals(AutonomyGoalId("goal-1"), selected.proposal.goalId)
        assertEquals(allowed, selected.proposal.actionClass)
    }
}
