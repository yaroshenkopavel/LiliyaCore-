package pro.liliya.core.autonomy

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.CapabilityId

class AutonomyGoalAuthorityRequestFactoryContractTest {
    private val actionClass = AutonomyActionClass("local-read")
    private val capability = CapabilityId("capability.local.read")

    private fun selected(): AutonomyGoalDeliberationResult.Selected {
        val createdAt = Instant.parse("2026-09-17T09:00:00Z")
        val goal = AutonomyGoalEnvelope(
            id = AutonomyGoalId("goal-1"),
            scope = AutonomyGoalScope("owner-local-scope"),
            createdAt = createdAt,
            expiresAt = createdAt.plusSeconds(3600),
            allowedActionClasses = setOf(actionClass),
            budget = AutonomyGoalBudget(
                maxSteps = 2,
                maxWallClock = Duration.ofMinutes(20),
                maxExecutions = 1
            )
        )
        return assertIs<AutonomyGoalDeliberationResult.Selected>(
            AutonomyGoalDeliberator(AutonomyGoalDeliberationPolicy(maxProposals = 4)).deliberate(
                goal = goal,
                currentScope = goal.scope,
                proposals = listOf(
                    AutonomyGoalProposal(
                        id = AutonomyGoalProposalId("proposal-1"),
                        goalId = goal.id,
                        scope = goal.scope,
                        actionClass = actionClass,
                        preference = 7
                    )
                ),
                usage = AutonomyGoalUsage(completedSteps = 0, completedExecutions = 0),
                now = createdAt.plusSeconds(5)
            )
        )
    }

    @Test
    fun selected_proposal_becomes_identity_only_authority_request() {
        val factory = AutonomyGoalAuthorityRequestFactory(
            mapOf(actionClass to capability)
        )

        val created = assertIs<AutonomyGoalAuthorityRequestResult.Created>(
            factory.create(selected(), AuthorityPrincipal("local-owner"))
        )

        assertEquals(AutonomyGoalId("goal-1"), created.identity.goalId)
        assertEquals(AutonomyGoalProposalId("proposal-1"), created.identity.proposalId)
        assertEquals(actionClass, created.identity.actionClass)
        assertEquals("local-owner", created.request.principal.value)
        assertEquals(capability, created.request.capability)
        assertEquals("owner-local-scope", created.request.scope.value)
        assertEquals(
            "bounded-autonomy:goal=goal-1;proposal=proposal-1;actionClass=local-read",
            created.request.reason
        )
    }

    @Test
    fun authority_boundary_has_no_private_cognition_payload_field() {
        val created = assertIs<AutonomyGoalAuthorityRequestResult.Created>(
            AutonomyGoalAuthorityRequestFactory(mapOf(actionClass to capability)).create(
                selected(),
                AuthorityPrincipal("local-owner")
            )
        )

        val exposed = listOf(
            created.request.principal.value,
            created.request.capability.value,
            created.request.reason,
            created.request.scope.value,
            created.identity.goalId.value,
            created.identity.proposalId.value,
            created.identity.scope.value,
            created.identity.actionClass.value
        )
        val privatePayload = "private-cognition-must-not-cross-authority-boundary"

        exposed.forEach { assertNotEquals(privatePayload, it) }
    }

    @Test
    fun unmapped_action_class_fails_before_authority() {
        val result = AutonomyGoalAuthorityRequestFactory(
            mapOf(AutonomyActionClass("local-write") to CapabilityId("capability.local.write"))
        ).create(
            selected(),
            AuthorityPrincipal("local-owner")
        )

        val rejected = assertIs<AutonomyGoalAuthorityRequestResult.Rejected>(result)
        assertEquals(
            AutonomyGoalAuthorityRequestFailure.ACTION_CLASS_NOT_MAPPED,
            rejected.failure
        )
    }
}
