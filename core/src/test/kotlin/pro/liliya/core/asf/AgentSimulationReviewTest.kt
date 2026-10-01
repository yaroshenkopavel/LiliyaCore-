package pro.liliya.core.asf

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentSimulationReviewTest {
    private val now = Instant.parse("2026-09-29T16:20:00Z")
    private val root = AgentRootTaskId("root-sim-review")

    @Test
    fun challenged_review_bundle_is_blocked_and_non_replayable() {
        val proposal = proposal("challenge")
        val simulation = plausibleSimulation(proposal)
        val contribution = reviewContribution(
            proposal,
            simulation,
            AgentFindingDisposition.CHALLENGES,
            "review-challenge"
        )
        val synthesis = AgentSynthesisGate().synthesize(listOf(contribution))

        val bundle = AgentSimulationReviewBundle.create(
            proposal,
            simulation,
            contribution,
            synthesis
        )

        assertEquals(AgentProposalReadiness.BLOCKED, bundle.readiness)
        assertEquals(
            AgentSimulationRecoveryDisposition.NEW_SIMULATION_REQUIRED,
            bundle.recoveryDisposition
        )
    }

    @Test
    fun supported_review_only_reaches_ready_for_governance() {
        val proposal = proposal("supported")
        val simulation = plausibleSimulation(proposal)
        val contribution = reviewContribution(
            proposal,
            simulation,
            AgentFindingDisposition.SUPPORTS,
            "review-support"
        )
        val synthesis = AgentSynthesisGate().synthesize(listOf(contribution))

        val bundle = AgentSimulationReviewBundle.create(
            proposal,
            simulation,
            contribution,
            synthesis
        )

        assertEquals(
            AgentProposalReadiness.READY_FOR_GOVERNANCE,
            bundle.readiness
        )
    }

    @Test
    fun missing_exact_proposal_reference_fails_closed() {
        val proposal = proposal("missing-proposal")
        val simulation = plausibleSimulation(proposal)
        val simRef = AgentSimulationProvenance.simulationReference(simulation)
        val artifact = reviewArtifact(
            "missing-proposal",
            listOf("evidence:review", simRef)
        )
        val contribution = contribution(
            artifact,
            AgentFindingDisposition.SUPPORTS
        )
        val synthesis = AgentSynthesisGate().synthesize(listOf(contribution))

        assertFailsWith<IllegalArgumentException> {
            AgentSimulationReviewBundle.create(
                proposal,
                simulation,
                contribution,
                synthesis
            )
        }
    }

    @Test
    fun missing_exact_simulation_reference_fails_closed() {
        val proposal = proposal("missing-simulation")
        val simulation = plausibleSimulation(proposal)
        val proposalRef = AgentSimulationProvenance.proposalReference(proposal)
        val artifact = reviewArtifact(
            "missing-simulation",
            listOf("evidence:review", proposalRef)
        )
        val contribution = contribution(
            artifact,
            AgentFindingDisposition.SUPPORTS
        )
        val synthesis = AgentSynthesisGate().synthesize(listOf(contribution))

        assertFailsWith<IllegalArgumentException> {
            AgentSimulationReviewBundle.create(
                proposal,
                simulation,
                contribution,
                synthesis
            )
        }
    }

    @Test
    fun simulation_reference_changes_with_simulation_state() {
        val proposal = proposal("state-change")
        val request = AgentSimulationRequest.create(
            proposal,
            listOf("context:a")
        )
        val plausible = AgentSimulationResult.create(
            request,
            AgentSimulationState.PLAUSIBLE,
            listOf("evidence:a")
        )
        val unresolved = AgentSimulationResult.create(
            request,
            AgentSimulationState.UNRESOLVED,
            listOf("evidence:a"),
            listOf("risk:a")
        )

        assertNotEquals(
            AgentSimulationProvenance.simulationReference(plausible),
            AgentSimulationProvenance.simulationReference(unresolved)
        )
    }

    @Test
    fun review_bundle_contract_has_no_permission_or_execution_fields() {
        val forbidden = listOf(
            "authority", "permission", "credential", "secret",
            "token", "license", "executiongrant", "principal"
        )
        val fields = AgentSimulationReviewBundle::class.java
            .declaredFields
            .map { it.name.lowercase() }

        forbidden.forEach { word ->
            assertTrue(fields.none { word in it })
        }
    }

    private fun plausibleSimulation(
        proposal: AgentToolProposal
    ): AgentSimulationResult {
        val request = AgentSimulationRequest.create(
            proposal,
            listOf("context:a")
        )
        return AgentSimulationResult.create(
            request,
            AgentSimulationState.PLAUSIBLE,
            listOf("evidence:simulation")
        )
    }

    private fun reviewContribution(
        proposal: AgentToolProposal,
        simulation: AgentSimulationResult,
        disposition: AgentFindingDisposition,
        seed: String
    ): AgentReviewContribution {
        val proposalRef = AgentSimulationProvenance.proposalReference(proposal)
        val simulationRef = AgentSimulationProvenance.simulationReference(simulation)
        val artifact = reviewArtifact(
            seed,
            listOf("evidence:review", proposalRef, simulationRef)
        )
        return contribution(artifact, disposition)
    }

    private fun contribution(
        artifact: AgentArtifact,
        disposition: AgentFindingDisposition
    ): AgentReviewContribution =
        AgentReviewContribution.create(
            AgentReviewKind.VERIFICATION,
            artifact,
            listOf(
                AgentReviewFinding.create(
                    AgentClaimKey("claim:simulation"),
                    disposition,
                    listOf("evidence:review")
                )
            )
        )

    private fun reviewArtifact(
        seed: String,
        provenance: List<String>
    ): AgentArtifact =
        AgentArtifact.create(
            AgentInstanceId("reviewer-$seed"),
            AgentInstanceGeneration(1),
            root,
            "simulation-review",
            "sha256:$seed",
            provenance,
            now
        )

    private fun proposal(seed: String): AgentToolProposal {
        val source = AgentArtifact.create(
            AgentInstanceId("producer-$seed"),
            AgentInstanceGeneration(1),
            root,
            "proposal-source",
            "sha256:source-$seed",
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
