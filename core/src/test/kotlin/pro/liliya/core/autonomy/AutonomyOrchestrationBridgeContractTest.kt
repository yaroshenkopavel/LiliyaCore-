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
import pro.liliya.core.orchestration.OrchestrationComposition
import pro.liliya.core.orchestration.OrchestrationIntentId
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

class AutonomyOrchestrationBridgeContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val autonomy: AutonomyComposition,
        val gate: ControlledAutonomyDeliberationGate,
        val deliberation: AutonomyDeliberationComposition,
        val planning: PlanningComposition,
        val reasoning: ReasoningComposition,
        val decisions: DecisionComposition,
        val orchestration: OrchestrationComposition,
        val planningBridge: AutonomyPlanningBridge,
        val reasoningBridge: AutonomyReasoningBridge,
        val decisionBridge: AutonomyDecisionBridge,
        val orchestrationBridge: AutonomyOrchestrationBridge
    )

    private data class Prepared(
        val request: AutonomyDeliberationOwnership,
        val planning: pro.liliya.core.planning.PlanningOwnership,
        val reasoning: pro.liliya.core.reasoning.ReasoningOwnership,
        val decision: pro.liliya.core.decision.DecisionOwnership
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "autonomy-orchestration-${sequence.incrementAndGet()}" }
        )
        val autonomy = AutonomyComposition(foundation)
        val gate = ControlledAutonomyDeliberationGate(foundation, autonomy)
        val deliberation = AutonomyDeliberationComposition(foundation)
        val planning = PlanningComposition(foundation)
        val reasoning = ReasoningComposition(foundation)
        val decisions = DecisionComposition(foundation)
        val orchestration = OrchestrationComposition(foundation)
        val preflight = AutonomyDeliberationPreflight(foundation, deliberation, gate)
        return Fixture(
            logs,
            autonomy,
            gate,
            deliberation,
            planning,
            reasoning,
            decisions,
            orchestration,
            AutonomyPlanningBridge(foundation, preflight, planning),
            AutonomyReasoningBridge(foundation, preflight, planning, reasoning),
            AutonomyDecisionBridge(foundation, preflight, planning, reasoning, decisions),
            AutonomyOrchestrationBridge(foundation, preflight, planning, reasoning, decisions, orchestration)
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
                    Instant.parse("2026-08-29T15:50:00Z")
                )
            )
        ).ownership
        val claim = assertIs<AutonomyDeliberationAttemptResult.Claimed>(
            f.gate.claimAttempt(autonomyOwnership.proposal.id, autonomyOwnership.generation)
        )
        val request = assertIs<AutonomyDeliberationInstallResult.Installed>(
            f.deliberation.install(
                AutonomyDeliberationRequest(
                    AutonomyDeliberationRequestId("request-1"),
                    AutonomyAttemptReference(claim.evidence.proposal.id, claim.evidence.generation, claim.evidence.attemptNumber),
                    "private deliberation objective",
                    Instant.parse("2026-08-29T15:51:00Z")
                )
            )
        ).ownership
        val planning = assertIs<AutonomyPlanningBridgeResult.Installed>(
            f.planningBridge.install(
                AutonomyPlanningBridgeRequest(
                    request.request.id,
                    request.generation,
                    PlanningProposalId("plan-1"),
                    "private planning goal",
                    listOf(PlanningStep(PlanningStepId("step-1"), "private planning step")),
                    Instant.parse("2026-08-29T15:52:00Z")
                )
            )
        ).planning
        val reasoning = assertIs<AutonomyReasoningBridgeResult.Installed>(
            f.reasoningBridge.install(
                AutonomyReasoningBridgeRequest(
                    request.request.id,
                    request.generation,
                    planning.proposal.id,
                    planning.generation,
                    ReasoningArtifactId("reason-1"),
                    listOf(ReasoningPremise(ReasoningPremiseId("premise-1"), "private premise")),
                    "private analysis",
                    "private conclusion",
                    Instant.parse("2026-08-29T15:53:00Z")
                )
            )
        ).reasoning
        val decision = assertIs<AutonomyDecisionBridgeResult.Installed>(
            f.decisionBridge.install(
                AutonomyDecisionBridgeRequest(
                    request.request.id,
                    request.generation,
                    planning.proposal.id,
                    planning.generation,
                    reasoning.artifact.id,
                    reasoning.generation,
                    DecisionId("decision-1"),
                    listOf(
                        DecisionOption(DecisionOptionId("option-a"), "private option a"),
                        DecisionOption(DecisionOptionId("option-b"), "private option b")
                    ),
                    DecisionOptionId("option-a"),
                    "private decision rationale",
                    Instant.parse("2026-08-29T15:54:00Z")
                )
            )
        ).decision
        return Prepared(request, planning, reasoning, decision)
    }

    private fun bridgeRequest(p: Prepared) = AutonomyOrchestrationBridgeRequest(
        p.request.request.id,
        p.request.generation,
        p.planning.proposal.id,
        p.planning.generation,
        p.reasoning.artifact.id,
        p.reasoning.generation,
        p.decision.decision.id,
        p.decision.generation,
        OrchestrationIntentId("intent-1"),
        "private orchestration description",
        Instant.parse("2026-08-29T15:55:00Z")
    )

    @Test
    fun exact_live_chain_installs_non_executing_orchestration_intent() {
        val f = fixture()
        val prepared = prepare(f)

        val result = assertIs<AutonomyOrchestrationBridgeResult.Installed>(
            f.orchestrationBridge.install(bridgeRequest(prepared))
        )

        val intent = result.orchestration.intent
        assertEquals(prepared.decision.decision.id, intent.decision.decisionId)
        assertEquals(prepared.decision.generation, intent.decision.generation)
        assertEquals(prepared.decision.decision.selectedOptionId, intent.decision.selectedOptionId)
        assertTrue(f.orchestration.contains(intent.id))
    }

    @Test
    fun stale_decision_generation_causes_zero_orchestration_writes() {
        val f = fixture()
        val prepared = prepare(f)
        assertTrue(prepared.decision.remove())

        assertIs<AutonomyOrchestrationBridgeResult.Rejected>(
            f.orchestrationBridge.install(bridgeRequest(prepared))
        )
        assertFalse(f.orchestration.contains(OrchestrationIntentId("intent-1")))
    }

    @Test
    fun cancellation_before_orchestration_causes_zero_orchestration_writes() {
        val f = fixture()
        val prepared = prepare(f)
        assertIs<AutonomyDeliberationCancellationResult.Cancelled>(
            f.gate.cancel(prepared.request.request.autonomy.proposalId, prepared.request.request.autonomy.proposalGeneration)
        )

        assertIs<AutonomyOrchestrationBridgeResult.Rejected>(
            f.orchestrationBridge.install(bridgeRequest(prepared))
        )
        assertFalse(f.orchestration.contains(OrchestrationIntentId("intent-1")))
    }

    @Test
    fun decision_with_mismatched_inputs_is_rejected() {
        val f = fixture()
        val prepared = prepare(f)
        assertTrue(prepared.decision.remove())
        val unrelated = pro.liliya.core.decision.DecisionRecord(
            DecisionId("decision-1"),
            listOf(pro.liliya.core.decision.DecisionInputReference.Planning(prepared.planning.proposal.id, prepared.planning.generation)),
            listOf(DecisionOption(DecisionOptionId("option-a"), "private option")),
            DecisionOptionId("option-a"),
            "private unrelated rationale",
            Instant.parse("2026-08-29T15:56:00Z")
        )
        val replacement = assertIs<pro.liliya.core.decision.DecisionInstallResult.Installed>(
            f.decisions.install(unrelated)
        ).ownership
        val req = AutonomyOrchestrationBridgeRequest(
            prepared.request.request.id,
            prepared.request.generation,
            prepared.planning.proposal.id,
            prepared.planning.generation,
            prepared.reasoning.artifact.id,
            prepared.reasoning.generation,
            replacement.decision.id,
            replacement.generation,
            OrchestrationIntentId("intent-1"),
            "private description",
            Instant.parse("2026-08-29T15:57:00Z")
        )

        assertIs<AutonomyOrchestrationBridgeResult.Rejected>(f.orchestrationBridge.install(req))
        assertFalse(f.orchestration.contains(OrchestrationIntentId("intent-1")))
    }

    @Test
    fun private_payload_is_absent_from_orchestration_bridge_observability() {
        val f = fixture()
        val prepared = prepare(f)
        f.orchestrationBridge.install(bridgeRequest(prepared))

        val secrets = setOf(
            "private autonomy objective", "private autonomy trigger", "private deliberation objective",
            "private planning goal", "private planning step", "private premise", "private analysis",
            "private conclusion", "private option a", "private option b", "private decision rationale",
            "private orchestration description"
        )
        assertFalse(f.logs.snapshot().any { event ->
            event.message in secrets || event.metadata.values.any { it in secrets }
        })
    }
}
