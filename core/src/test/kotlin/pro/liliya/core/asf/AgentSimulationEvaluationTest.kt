package pro.liliya.core.asf

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentSimulationEvaluationTest {
    private val now = Instant.parse("2026-09-29T17:00:00Z")
    private val root = AgentRootTaskId("root-eval")

    @Test
    fun completed_run_measurement_preserves_source_and_simulation_provenance() {
        val proposal = proposal("run")
        val pipeline = AgentSimulationPipeline(
            adapter = AgentSimulationAdapter { request ->
                AgentSimulationResult.create(
                    request,
                    AgentSimulationState.PLAUSIBLE,
                    listOf("evidence:simulation")
                )
            },
            budget = AgentSimulationBudget(
                maxContextReferences = 4,
                maxEvidenceReferences = 4,
                maxRiskItems = 2
            )
        )
        val run = pipeline.run(
            proposal,
            listOf("context:a", "context:b")
        )

        val evaluation = AgentSimulationEvaluationHooks.measure(
            proposal,
            run
        )

        assertEquals(proposal.id, evaluation.proposalId)
        assertEquals(proposal.sourceArtifactId, evaluation.sourceArtifactId)
        assertEquals(proposal.sourceProducerId, evaluation.sourceProducerId)
        assertEquals(root, evaluation.rootTaskId)
        assertEquals(2, evaluation.contextReferenceCount)
        assertEquals(1, evaluation.evidenceReferenceCount)
        assertEquals(0, evaluation.riskItemCount)
        assertTrue(!evaluation.reviewed)
        assertEquals(
            AgentSimulationRecoveryDisposition.NEW_SIMULATION_REQUIRED,
            evaluation.recoveryDisposition
        )
    }

    @Test
    fun reviewed_measurement_preserves_review_artifact_identity() {
        val proposal = proposal("reviewed")
        val request = AgentSimulationRequest.create(
            proposal,
            listOf("context:a")
        )
        val simulation = AgentSimulationResult.create(
            request,
            AgentSimulationState.PLAUSIBLE,
            listOf("evidence:simulation")
        )
        val proposalRef =
            AgentSimulationProvenance.proposalReference(proposal)
        val simulationRef =
            AgentSimulationProvenance.simulationReference(simulation)
        val reviewArtifact = AgentArtifact.create(
            AgentInstanceId("reviewer"),
            AgentInstanceGeneration(1),
            root,
            "simulation-review",
            "sha256:review",
            listOf("evidence:review", proposalRef, simulationRef),
            now
        )
        val contribution = AgentReviewContribution.create(
            AgentReviewKind.CRITIQUE,
            reviewArtifact,
            listOf(
                AgentReviewFinding.create(
                    AgentClaimKey("claim:simulation"),
                    AgentFindingDisposition.CHALLENGES,
                    listOf("evidence:review")
                )
            )
        )
        val synthesis = AgentSynthesisGate().synthesize(
            listOf(contribution)
        )
        val bundle = AgentSimulationReviewBundle.create(
            proposal,
            simulation,
            contribution,
            synthesis
        )

        val evaluation =
            AgentSimulationEvaluationHooks.measure(bundle)

        assertEquals(reviewArtifact.id, evaluation.reviewArtifactId)
        assertTrue(evaluation.reviewed)
        assertEquals(
            AgentProposalReadiness.BLOCKED,
            evaluation.readiness
        )
    }

    @Test
    fun incomplete_run_cannot_be_measured() {
        val proposal = proposal("cancelled")
        val run = AgentSimulationRun(
            state = AgentSimulationRunState.CANCELLED,
            proposalId = proposal.id,
            request = null,
            simulation = null,
            readiness = null
        )

        assertFailsWith<IllegalArgumentException> {
            AgentSimulationEvaluationHooks.measure(proposal, run)
        }
    }

    @Test
    fun evaluation_contract_has_no_authority_execution_or_secret_fields() {
        val forbidden = listOf(
            "authority", "permission", "credential", "secret",
            "token", "license", "executiongrant", "principal"
        )
        val fields = AgentSimulationDryRunEvaluation::class.java
            .declaredFields
            .map { it.name.lowercase() }

        forbidden.forEach { word ->
            assertTrue(fields.none { word in it })
        }
    }

    private fun proposal(seed: String): AgentToolProposal {
        val source = AgentArtifact.create(
            AgentInstanceId("producer-$seed"),
            AgentInstanceGeneration(1),
            root,
            "proposal-source",
            "sha256:$seed",
            listOf("evidence:$seed"),
            now
        )
        return AgentToolProposal.create(
            source,
            "tool:opaque",
            "prepare-change",
            "target:$seed",
            AgentEffectClass.UNKNOWN,
            listOf("precondition:a"),
            listOf("effect:a")
        )
    }
}
