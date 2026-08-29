package pro.liliya.core.autonomy

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.execution.ExecutionActionId
import pro.liliya.core.orchestration.OrchestrationGeneration
import pro.liliya.core.orchestration.OrchestrationIntentId
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningGeneration

class ControlledAutonomyReadinessContractTest {
    @Test
    fun data_only_autonomy_artifacts_expose_no_authority_execution_scheduler_or_agent_methods() {
        val forbidden = listOf(
            "approve", "authorize", "authority", "execute", "execution",
            "schedule", "scheduler", "spawn", "agent"
        )
        val dataOnlyTypes = listOf(
            AutonomyProposal::class.java,
            AutonomyDeliberationRequest::class.java,
            AutonomyDeliberationAttemptEvidence::class.java,
            AutonomyDeliberationReadyEvidence::class.java
        )

        dataOnlyTypes.forEach { type ->
            val methodNames = type.declaredMethods.map { it.name.lowercase() }
            assertFalse(methodNames.any { name -> forbidden.any(name::contains) })
        }
    }

    @Test
    fun full_execution_request_requires_exact_generation_provenance_at_every_mutable_boundary() {
        val request = ControlledAutonomyExecutionRequest(
            deliberationRequestId = AutonomyDeliberationRequestId("request-1"),
            deliberationGeneration = AutonomyDeliberationGeneration(2),
            planningProposalId = PlanningProposalId("planning-1"),
            planningGeneration = PlanningGeneration(3),
            reasoningArtifactId = ReasoningArtifactId("reasoning-1"),
            reasoningGeneration = ReasoningGeneration(4),
            decisionId = DecisionId("decision-1"),
            decisionGeneration = DecisionGeneration(5),
            orchestrationIntentId = OrchestrationIntentId("intent-1"),
            orchestrationGeneration = OrchestrationGeneration(6),
            principal = AuthorityPrincipal("liliya"),
            actionId = ExecutionActionId("device.open.settings")
        )

        val generationFields = ControlledAutonomyExecutionRequest::class.java.declaredFields
            .map { it.name }
            .filter { it.endsWith("Generation") }
            .toSet()

        assertEquals(
            setOf(
                "deliberationGeneration",
                "planningGeneration",
                "reasoningGeneration",
                "decisionGeneration",
                "orchestrationGeneration"
            ),
            generationFields
        )
        assertEquals(AutonomyDeliberationGeneration(2), request.deliberationGeneration)
        assertEquals(PlanningGeneration(3), request.planningGeneration)
        assertEquals(ReasoningGeneration(4), request.reasoningGeneration)
        assertEquals(DecisionGeneration(5), request.decisionGeneration)
        assertEquals(OrchestrationGeneration(6), request.orchestrationGeneration)
    }

    @Test
    fun deliberation_evidence_rendering_contains_only_structural_identity_not_private_payload() {
        val secretObjective = "never-render-autonomy-objective"
        val secretTrigger = "never-render-autonomy-trigger"
        val secretDeliberation = "never-render-deliberation-objective"
        val proposal = AutonomyProposal(
            id = AutonomyProposalId("autonomy-1"),
            origin = AutonomyOrigin.Declared(AutonomySourceId("goal-context")),
            objective = secretObjective,
            triggerDescription = secretTrigger,
            priority = AutonomyPriority.NORMAL,
            budget = AutonomyBudget(2),
            createdAt = Instant.parse("2026-08-29T16:30:00Z")
        )
        val attempt = AutonomyDeliberationAttemptEvidence(
            proposal = proposal,
            generation = AutonomyGeneration(7),
            attemptNumber = 1
        )
        val request = AutonomyDeliberationRequest(
            id = AutonomyDeliberationRequestId("request-1"),
            autonomy = AutonomyAttemptReference(
                proposalId = proposal.id,
                proposalGeneration = attempt.generation,
                attemptNumber = attempt.attemptNumber
            ),
            objective = secretDeliberation,
            createdAt = Instant.parse("2026-08-29T16:31:00Z")
        )
        val ready = AutonomyDeliberationReadyEvidence(
            request = request,
            requestGeneration = AutonomyDeliberationGeneration(8),
            attempt = attempt
        )

        val rendered = listOf(proposal, attempt, request, ready).joinToString("\n")
        assertFalse(rendered.contains(secretObjective))
        assertFalse(rendered.contains(secretTrigger))
        assertFalse(rendered.contains(secretDeliberation))
        assertTrue(rendered.contains("autonomy-1"))
        assertTrue(rendered.contains("request-1"))
    }
}
