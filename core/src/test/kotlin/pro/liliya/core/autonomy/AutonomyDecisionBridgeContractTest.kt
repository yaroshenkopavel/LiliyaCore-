package pro.liliya.core.autonomy

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.decision.DecisionOption
import pro.liliya.core.decision.DecisionOptionId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.planning.PlanningStep
import pro.liliya.core.planning.PlanningStepId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.reasoning.ReasoningPremise
import pro.liliya.core.reasoning.ReasoningPremiseId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AutonomyDecisionBridgeContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val autonomy: AutonomyComposition,
        val gate: ControlledAutonomyDeliberationGate,
        val deliberation: AutonomyDeliberationComposition,
        val planning: PlanningComposition,
        val reasoning: ReasoningComposition,
        val decisions: DecisionComposition,
        val planningBridge: AutonomyPlanningBridge,
        val reasoningBridge: AutonomyReasoningBridge,
        val decisionBridge: AutonomyDecisionBridge
    )

    private data class Prepared(
        val request: AutonomyDeliberationOwnership,
        val planning: pro.liliya.core.planning.PlanningOwnership,
        val reasoning: pro.liliya.core.reasoning.ReasoningOwnership
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "autonomy-decision-${sequence.incrementAndGet()}" }
        )
        val autonomy = AutonomyComposition(foundation)
        val gate = ControlledAutonomyDeliberationGate(foundation, autonomy)
        val deliberation = AutonomyDeliberationComposition(foundation)
        val planning = PlanningComposition(foundation)
        val reasoning = ReasoningComposition(foundation)
        val decisions = DecisionComposition(foundation)
        val preflight = AutonomyDeliberationPreflight(foundation, deliberation, gate)
        return Fixture(
            logs,
            autonomy,
            gate,
            deliberation,
            planning,
            reasoning,
            decisions,
            AutonomyPlanningBridge(foundation, preflight, planning),
            AutonomyReasoningBridge(foundation, preflight, planning, reasoning),
            AutonomyDecisionBridge(foundation, preflight, planning, reasoning, decisions)
        )
    }

    private fun prepare(f: Fixture): Prepared {
        val autonomyOwnership = assertIs<AutonomyInstallResult.Installed>(
            f.autonomy.install(
                AutonomyProposal(
                    AutonomyProposalId("autonomy-1"),
                    AutonomyOrigin.Declared(AutonomySourceId("goal-context")),
                    "private autonomy objective",
                    "private autonomy trigger",
                    AutonomyPriority.NORMAL,
                    AutonomyBudget(2),
                    Instant.parse("2026-08-29T15:40:00Z")
                )
            )
        ).ownership
        val claim = assertIs<AutonomyDeliberationAttemptResult.Claimed>(
            f.gate.claimAttempt(autonomyOwnership.proposal.id, autonomyOwnership.generation)
        )
        val requestOwnership = assertIs<AutonomyDeliberationInstallResult.Installed>(
            f.deliberation.install(
                AutonomyDeliberationRequest(
                    AutonomyDeliberationRequestId("request-1"),
                    AutonomyAttemptReference(claim.evidence.proposal.id, claim.evidence.generation, claim.evidence.attemptNumber),
                    "private deliberation objective",
                    Instant.parse("2026-08-29T15:41:00Z")
                )
            )
        ).ownership
        val planningOwnership = assertIs<AutonomyPlanningBridgeResult.Installed>(
            f.planningBridge.install(
                AutonomyPlanningBridgeRequest(
                    requestOwnership.request.id,
                    requestOwnership.generation,
                    PlanningProposalId("plan-1"),
                    "private planning goal",
                    listOf(PlanningStep(PlanningStepId("step-1"), "private planning step")),
                    Instant.parse("2026-08-29T15:42:00Z")
                )
            )
        ).planning
        val reasoningOwnership = assertIs<AutonomyReasoningBridgeResult.Installed>(
            f.reasoningBridge.install(
                AutonomyReasoningBridgeRequest(
                    requestOwnership.request.id,
                    requestOwnership.generation,
                    planningOwnership.proposal.id,
                    planningOwnership.generation,
                    ReasoningArtifactId("reason-1"),
                    listOf(ReasoningPremise(ReasoningPremiseId("premise-1"), "private premise")),
                    "private analysis",
                    "private conclusion",
                    Instant.parse("2026-08-29T15:43:00Z")
                )
            )
        ).reasoning
        return Prepared(requestOwnership, planningOwnership, reasoningOwnership)
    }

    private fun decisionRequest(p: Prepared) = AutonomyDecisionBridgeRequest(
        p.request.request.id,
        p.request.generation,
        p.planning.proposal.id,
        p.planning.generation,
        p.reasoning.artifact.id,
        p.reasoning.generation,
        DecisionId("decision-1"),
        listOf(
            DecisionOption(DecisionOptionId("option-a"), "private option a"),
            DecisionOption(DecisionOptionId("option-b"), "private option b")
        ),
        DecisionOptionId("option-a"),
        "private decision rationale",
        Instant.parse("2026-08-29T15:44:00Z")
    )

    @Test
    fun exact_live_chain_installs_decision_with_exact_planning_and_reasoning_inputs() {
        val f = fixture()
        val prepared = prepare(f)

        val result = assertIs<AutonomyDecisionBridgeResult.Installed>(
            f.decisionBridge.install(decisionRequest(prepared))
        )

        val decision = result.decision.decision
        assertEquals(prepared.planning.proposal.id, (decision.inputs[0] as pro.liliya.core.decision.DecisionInputReference.Planning).proposalId)
        assertEquals(prepared.reasoning.artifact.id, (decision.inputs[1] as pro.liliya.core.decision.DecisionInputReference.Reasoning).artifactId)
        assertEquals(DecisionOptionId("option-a"), decision.selectedOptionId)
        assertTrue(f.decisions.contains(decision.id))
    }

    @Test
    fun stale_reasoning_generation_causes_zero_decision_writes() {
        val f = fixture()
        val prepared = prepare(f)
        assertTrue(prepared.reasoning.remove())

        val result = f.decisionBridge.install(decisionRequest(prepared))

        assertIs<AutonomyDecisionBridgeResult.Rejected>(result)
        assertFalse(f.decisions.contains(DecisionId("decision-1")))
    }

    @Test
    fun cancellation_before_decision_causes_zero_decision_writes() {
        val f = fixture()
        val prepared = prepare(f)
        assertIs<AutonomyDeliberationCancellationResult.Cancelled>(
            f.gate.cancel(prepared.request.request.autonomy.proposalId, prepared.request.request.autonomy.proposalGeneration)
        )

        assertIs<AutonomyDecisionBridgeResult.Rejected>(f.decisionBridge.install(decisionRequest(prepared)))
        assertFalse(f.decisions.contains(DecisionId("decision-1")))
    }

    @Test
    fun unrelated_reasoning_provenance_is_rejected() {
        val f = fixture()
        val prepared = prepare(f)
        assertTrue(prepared.reasoning.remove())
        val unrelated = pro.liliya.core.reasoning.ReasoningArtifact(
            ReasoningArtifactId("reason-1"),
            pro.liliya.core.reasoning.ReasoningOrigin(pro.liliya.core.reasoning.ReasoningSourceId("unrelated")),
            listOf(ReasoningPremise(ReasoningPremiseId("premise-1"), "private unrelated premise")),
            "private unrelated analysis",
            "private unrelated conclusion",
            Instant.parse("2026-08-29T15:45:00Z")
        )
        val replacement = assertIs<pro.liliya.core.reasoning.ReasoningInstallResult.Installed>(
            f.reasoning.install(unrelated)
        ).ownership
        val req = AutonomyDecisionBridgeRequest(
            prepared.request.request.id,
            prepared.request.generation,
            prepared.planning.proposal.id,
            prepared.planning.generation,
            replacement.artifact.id,
            replacement.generation,
            DecisionId("decision-1"),
            listOf(DecisionOption(DecisionOptionId("option-a"), "private option")),
            DecisionOptionId("option-a"),
            "private rationale",
            Instant.parse("2026-08-29T15:46:00Z")
        )

        assertIs<AutonomyDecisionBridgeResult.Rejected>(f.decisionBridge.install(req))
        assertFalse(f.decisions.contains(DecisionId("decision-1")))
    }

    @Test
    fun private_payload_is_absent_from_decision_bridge_observability() {
        val f = fixture()
        val prepared = prepare(f)
        f.decisionBridge.install(decisionRequest(prepared))

        val secrets = setOf(
            "private autonomy objective", "private autonomy trigger", "private deliberation objective",
            "private planning goal", "private planning step", "private premise", "private analysis",
            "private conclusion", "private option a", "private option b", "private decision rationale"
        )
        assertFalse(f.logs.snapshot().any { event ->
            event.message in secrets || event.metadata.values.any { it in secrets }
        })
    }
}
