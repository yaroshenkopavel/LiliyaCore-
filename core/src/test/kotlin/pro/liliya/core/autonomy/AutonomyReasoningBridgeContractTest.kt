package pro.liliya.core.autonomy

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
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

class AutonomyReasoningBridgeContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val autonomy: AutonomyComposition,
        val gate: ControlledAutonomyDeliberationGate,
        val deliberation: AutonomyDeliberationComposition,
        val planning: PlanningComposition,
        val reasoning: ReasoningComposition,
        val planningBridge: AutonomyPlanningBridge,
        val reasoningBridge: AutonomyReasoningBridge
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "autonomy-reasoning-${sequence.incrementAndGet()}" }
        )
        val autonomy = AutonomyComposition(foundation)
        val gate = ControlledAutonomyDeliberationGate(foundation, autonomy)
        val deliberation = AutonomyDeliberationComposition(foundation)
        val planning = PlanningComposition(foundation)
        val reasoning = ReasoningComposition(foundation)
        val preflight = AutonomyDeliberationPreflight(foundation, deliberation, gate)
        return Fixture(
            logs = logs,
            autonomy = autonomy,
            gate = gate,
            deliberation = deliberation,
            planning = planning,
            reasoning = reasoning,
            planningBridge = AutonomyPlanningBridge(foundation, preflight, planning),
            reasoningBridge = AutonomyReasoningBridge(foundation, preflight, planning, reasoning)
        )
    }

    private data class Prepared(
        val request: AutonomyDeliberationOwnership,
        val planning: pro.liliya.core.planning.PlanningOwnership
    )

    private fun prepare(f: Fixture): Prepared {
        val autonomyOwnership = assertIs<AutonomyInstallResult.Installed>(
            f.autonomy.install(
                AutonomyProposal(
                    id = AutonomyProposalId("autonomy-1"),
                    origin = AutonomyOrigin.Declared(AutonomySourceId("goal-context")),
                    objective = "private autonomy objective",
                    triggerDescription = "private autonomy trigger",
                    priority = AutonomyPriority.NORMAL,
                    budget = AutonomyBudget(2),
                    createdAt = Instant.parse("2026-08-29T15:30:00Z")
                )
            )
        ).ownership
        val claim = assertIs<AutonomyDeliberationAttemptResult.Claimed>(
            f.gate.claimAttempt(autonomyOwnership.proposal.id, autonomyOwnership.generation)
        )
        val requestOwnership = assertIs<AutonomyDeliberationInstallResult.Installed>(
            f.deliberation.install(
                AutonomyDeliberationRequest(
                    id = AutonomyDeliberationRequestId("request-1"),
                    autonomy = AutonomyAttemptReference(
                        claim.evidence.proposal.id,
                        claim.evidence.generation,
                        claim.evidence.attemptNumber
                    ),
                    objective = "private deliberation objective",
                    createdAt = Instant.parse("2026-08-29T15:31:00Z")
                )
            )
        ).ownership
        val planningOwnership = assertIs<AutonomyPlanningBridgeResult.Installed>(
            f.planningBridge.install(
                AutonomyPlanningBridgeRequest(
                    deliberationRequestId = requestOwnership.request.id,
                    deliberationGeneration = requestOwnership.generation,
                    planningProposalId = PlanningProposalId("plan-1"),
                    goal = "private planning goal",
                    steps = listOf(PlanningStep(PlanningStepId("step-1"), "private planning step")),
                    createdAt = Instant.parse("2026-08-29T15:32:00Z")
                )
            )
        ).planning
        return Prepared(requestOwnership, planningOwnership)
    }

    private fun bridgeRequest(prepared: Prepared) = AutonomyReasoningBridgeRequest(
        deliberationRequestId = prepared.request.request.id,
        deliberationGeneration = prepared.request.generation,
        planningProposalId = prepared.planning.proposal.id,
        planningGeneration = prepared.planning.generation,
        reasoningArtifactId = ReasoningArtifactId("reason-1"),
        premises = listOf(
            ReasoningPremise(ReasoningPremiseId("premise-1"), "private premise")
        ),
        analysis = "private reasoning analysis",
        conclusion = "private reasoning conclusion",
        createdAt = Instant.parse("2026-08-29T15:33:00Z")
    )

    @Test
    fun exact_live_deliberation_and_exact_planning_install_reasoning_with_trusted_origin() {
        val f = fixture()
        val prepared = prepare(f)

        val result = assertIs<AutonomyReasoningBridgeResult.Installed>(
            f.reasoningBridge.install(bridgeRequest(prepared))
        )

        val artifact = result.reasoning.artifact
        assertEquals("autonomy-planning", artifact.origin.sourceId.value)
        assertTrue(artifact.origin.sourceReference!!.value.contains("request=request-1@"))
        assertTrue(artifact.origin.sourceReference!!.value.contains("planning=plan-1@"))
        assertTrue(f.reasoning.contains(artifact.id))
    }

    @Test
    fun stale_planning_generation_causes_zero_reasoning_writes() {
        val f = fixture()
        val prepared = prepare(f)
        assertTrue(prepared.planning.remove())
        val replacement = assertIs<AutonomyPlanningBridgeResult.Installed>(
            f.planningBridge.install(
                AutonomyPlanningBridgeRequest(
                    deliberationRequestId = prepared.request.request.id,
                    deliberationGeneration = prepared.request.generation,
                    planningProposalId = PlanningProposalId("plan-1"),
                    goal = "replacement private goal",
                    steps = listOf(PlanningStep(PlanningStepId("step-1"), "replacement private step")),
                    createdAt = Instant.parse("2026-08-29T15:34:00Z")
                )
            )
        ).planning

        val result = f.reasoningBridge.install(bridgeRequest(prepared))

        assertIs<AutonomyReasoningBridgeResult.Rejected>(result)
        assertFalse(f.reasoning.contains(ReasoningArtifactId("reason-1")))
        assertTrue(f.planning.contains(replacement.proposal.id))
    }

    @Test
    fun cancellation_before_reasoning_causes_zero_reasoning_writes() {
        val f = fixture()
        val prepared = prepare(f)
        assertIs<AutonomyDeliberationCancellationResult.Cancelled>(
            f.gate.cancel(
                prepared.request.request.autonomy.proposalId,
                prepared.request.request.autonomy.proposalGeneration
            )
        )

        val result = f.reasoningBridge.install(bridgeRequest(prepared))

        assertIs<AutonomyReasoningBridgeResult.Rejected>(result)
        assertFalse(f.reasoning.contains(ReasoningArtifactId("reason-1")))
    }

    @Test
    fun unrelated_planning_provenance_is_rejected() {
        val f = fixture()
        val prepared = prepare(f)
        assertTrue(prepared.planning.remove())
        val unrelated = pro.liliya.core.planning.PlanningProposal(
            id = PlanningProposalId("plan-1"),
            origin = pro.liliya.core.planning.PlanningOrigin(
                pro.liliya.core.planning.PlanningSourceId("unrelated")
            ),
            goal = "private unrelated goal",
            steps = listOf(PlanningStep(PlanningStepId("step-1"), "private unrelated step")),
            createdAt = Instant.parse("2026-08-29T15:34:00Z")
        )
        val ownership = assertIs<pro.liliya.core.planning.PlanningInstallResult.Installed>(
            f.planning.install(unrelated)
        ).ownership

        val request = AutonomyReasoningBridgeRequest(
            deliberationRequestId = prepared.request.request.id,
            deliberationGeneration = prepared.request.generation,
            planningProposalId = ownership.proposal.id,
            planningGeneration = ownership.generation,
            reasoningArtifactId = ReasoningArtifactId("reason-1"),
            premises = listOf(ReasoningPremise(ReasoningPremiseId("premise-1"), "private premise")),
            analysis = "private analysis",
            conclusion = "private conclusion",
            createdAt = Instant.parse("2026-08-29T15:35:00Z")
        )

        assertIs<AutonomyReasoningBridgeResult.Rejected>(f.reasoningBridge.install(request))
        assertFalse(f.reasoning.contains(ReasoningArtifactId("reason-1")))
    }

    @Test
    fun private_payload_is_absent_from_reasoning_bridge_observability() {
        val f = fixture()
        val prepared = prepare(f)
        f.reasoningBridge.install(bridgeRequest(prepared))

        val secrets = setOf(
            "private autonomy objective",
            "private autonomy trigger",
            "private deliberation objective",
            "private planning goal",
            "private planning step",
            "private premise",
            "private reasoning analysis",
            "private reasoning conclusion"
        )
        assertFalse(f.logs.snapshot().any { event ->
            event.message in secrets || event.metadata.values.any { it in secrets }
        })
    }
}
