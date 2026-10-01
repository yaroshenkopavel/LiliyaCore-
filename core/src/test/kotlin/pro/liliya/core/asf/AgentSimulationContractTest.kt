package pro.liliya.core.asf

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentSimulationContractTest {
    private val now = Instant.parse("2026-09-29T16:00:00Z")
    private val root = AgentRootTaskId("root-sim")

    @Test
    fun proposal_identity_is_deterministic_and_order_canonical() {
        val source = artifact("source-a")
        val first = AgentToolProposal.create(
            sourceArtifact = source,
            toolReference = "tool:calendar",
            operationClass = "create-event",
            targetReference = "calendar:default",
            effectClass = AgentEffectClass.EXTERNAL_STATE_CHANGE,
            preconditions = listOf("user-approved", "slot-free"),
            expectedEffects = listOf("event-created", "invite-recorded")
        )
        val second = AgentToolProposal.create(
            sourceArtifact = source,
            toolReference = "tool:calendar",
            operationClass = "create-event",
            targetReference = "calendar:default",
            effectClass = AgentEffectClass.EXTERNAL_STATE_CHANGE,
            preconditions = listOf("slot-free", "user-approved"),
            expectedEffects = listOf("invite-recorded", "event-created")
        )

        assertEquals(first, second)
        assertEquals(listOf("slot-free", "user-approved"), first.preconditions)
        assertEquals(listOf("event-created", "invite-recorded"), first.expectedEffects)
    }

    @Test
    fun proposal_identity_changes_with_effect_or_target() {
        val source = artifact("source-b")
        val base = AgentToolProposal.create(
            source,
            "tool:storage",
            "write",
            "target:a",
            AgentEffectClass.LOCAL_STATE_CHANGE,
            listOf("path-valid"),
            listOf("state-updated")
        )
        val changed = AgentToolProposal.create(
            source,
            "tool:storage",
            "write",
            "target:b",
            AgentEffectClass.LOCAL_STATE_CHANGE,
            listOf("path-valid"),
            listOf("state-updated")
        )

        assertNotEquals(base.id, changed.id)
    }

    @Test
    fun simulation_adapter_is_prediction_only_contract() {
        val proposal = proposal("sim-a")
        val request = AgentSimulationRequest.create(
            proposal,
            listOf("evidence:dry-run", "policy:tool-view")
        )
        val adapter = AgentSimulationAdapter { incoming ->
            AgentSimulationResult.create(
                incoming,
                AgentSimulationState.PLAUSIBLE,
                listOf("evidence:dry-run")
            )
        }

        val result = adapter.simulate(request)

        assertEquals(proposal.id, result.proposalId)
        assertEquals(root, result.rootTaskId)
        assertEquals(AgentSimulationState.PLAUSIBLE, result.state)
        assertTrue(result.observedRisks.isEmpty())
    }

    @Test
    fun plausible_simulation_cannot_retain_unresolved_risk() {
        val request = AgentSimulationRequest.create(
            proposal("sim-risk"),
            listOf("evidence:dry-run")
        )

        assertFailsWith<IllegalArgumentException> {
            AgentSimulationResult.create(
                request,
                AgentSimulationState.PLAUSIBLE,
                listOf("evidence:dry-run"),
                listOf("risk:unknown-effect")
            )
        }
    }

    @Test
    fun successful_simulation_only_becomes_ready_for_governance() {
        val proposal = proposal("ready")
        val request = AgentSimulationRequest.create(
            proposal,
            listOf("evidence:dry-run")
        )
        val simulation = AgentSimulationResult.create(
            request,
            AgentSimulationState.PLAUSIBLE,
            listOf("evidence:dry-run")
        )

        assertEquals(
            AgentProposalReadiness.READY_FOR_GOVERNANCE,
            AgentProposalReadinessGate.evaluate(proposal, simulation)
        )
    }

    @Test
    fun blocked_simulation_is_blocked() {
        val proposal = proposal("blocked")
        val request = AgentSimulationRequest.create(
            proposal,
            listOf("evidence:dry-run")
        )
        val simulation = AgentSimulationResult.create(
            request,
            AgentSimulationState.BLOCKED,
            listOf("evidence:dry-run"),
            listOf("risk:precondition-failed")
        )

        assertEquals(
            AgentProposalReadiness.BLOCKED,
            AgentProposalReadinessGate.evaluate(proposal, simulation)
        )
    }

    @Test
    fun unresolved_simulation_is_unresolved() {
        val proposal = proposal("unresolved")
        val request = AgentSimulationRequest.create(
            proposal,
            listOf("evidence:dry-run")
        )
        val simulation = AgentSimulationResult.create(
            request,
            AgentSimulationState.UNRESOLVED,
            listOf("evidence:dry-run"),
            listOf("risk:insufficient-context")
        )

        assertEquals(
            AgentProposalReadiness.UNRESOLVED,
            AgentProposalReadinessGate.evaluate(proposal, simulation)
        )
    }

    @Test
    fun mismatched_proposal_simulation_fails_closed() {
        val first = proposal("first")
        val second = proposal("second")
        val simulation = AgentSimulationResult.create(
            AgentSimulationRequest.create(first, listOf("evidence:a")),
            AgentSimulationState.PLAUSIBLE,
            listOf("evidence:a")
        )

        assertFailsWith<IllegalArgumentException> {
            AgentProposalReadinessGate.evaluate(second, simulation)
        }
    }

    @Test
    fun challenged_review_blocks_readiness() {
        val proposal = proposal("review-block")
        val request = AgentSimulationRequest.create(
            proposal,
            listOf("evidence:dry-run")
        )
        val simulation = AgentSimulationResult.create(
            request,
            AgentSimulationState.PLAUSIBLE,
            listOf("evidence:dry-run")
        )
        val reviewArtifact = AgentArtifact.create(
            AgentInstanceId("reviewer"),
            AgentInstanceGeneration(1),
            root,
            "review",
            "sha256:review",
            listOf("evidence:review"),
            now
        )
        val synthesis = AgentSynthesisGate().synthesize(
            listOf(
                AgentReviewContribution.create(
                    AgentReviewKind.CRITIQUE,
                    reviewArtifact,
                    listOf(
                        AgentReviewFinding.create(
                            AgentClaimKey("claim:proposal"),
                            AgentFindingDisposition.CHALLENGES,
                            listOf("evidence:review")
                        )
                    )
                )
            )
        )

        assertEquals(
            AgentProposalReadiness.BLOCKED,
            AgentProposalReadinessGate.evaluate(proposal, simulation, synthesis)
        )
    }

    @Test
    fun unresolved_review_never_becomes_permission() {
        val proposal = proposal("review-unresolved")
        val simulation = AgentSimulationResult.create(
            AgentSimulationRequest.create(proposal, listOf("evidence:dry-run")),
            AgentSimulationState.PLAUSIBLE,
            listOf("evidence:dry-run")
        )
        val reviewArtifact = AgentArtifact.create(
            AgentInstanceId("reviewer-u"),
            AgentInstanceGeneration(1),
            root,
            "review",
            "sha256:review-u",
            listOf("evidence:review-u"),
            now
        )
        val synthesis = AgentSynthesisGate().synthesize(
            listOf(
                AgentReviewContribution.create(
                    AgentReviewKind.VERIFICATION,
                    reviewArtifact,
                    listOf(
                        AgentReviewFinding.create(
                            AgentClaimKey("claim:proposal"),
                            AgentFindingDisposition.INCONCLUSIVE,
                            listOf("evidence:review-u")
                        )
                    )
                )
            )
        )

        assertEquals(
            AgentProposalReadiness.UNRESOLVED,
            AgentProposalReadinessGate.evaluate(proposal, simulation, synthesis)
        )
    }

    @Test
    fun simulation_contracts_contain_no_authority_execution_or_secret_fields() {
        val forbidden = listOf(
            "authority", "permission", "credential", "secret",
            "token", "license", "executiongrant", "principal"
        )
        listOf(
            AgentToolProposal::class.java,
            AgentSimulationRequest::class.java,
            AgentSimulationResult::class.java
        ).forEach { type ->
            val fields = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(fields.none { word in it }, "${type.simpleName} contains forbidden field: $word")
            }
        }
    }

    private fun proposal(seed: String): AgentToolProposal =
        AgentToolProposal.create(
            sourceArtifact = artifact(seed),
            toolReference = "tool:opaque",
            operationClass = "prepare-change",
            targetReference = "target:$seed",
            effectClass = AgentEffectClass.UNKNOWN,
            preconditions = listOf("precondition:a"),
            expectedEffects = listOf("effect:a")
        )

    private fun artifact(seed: String): AgentArtifact =
        AgentArtifact.create(
            AgentInstanceId("producer-$seed"),
            AgentInstanceGeneration(1),
            root,
            "proposal-source",
            "sha256:$seed",
            listOf("evidence:$seed"),
            now
        )
}
