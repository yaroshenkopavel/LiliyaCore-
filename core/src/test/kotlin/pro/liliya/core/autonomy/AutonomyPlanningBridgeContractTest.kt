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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AutonomyPlanningBridgeContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val autonomy: AutonomyComposition,
        val gate: ControlledAutonomyDeliberationGate,
        val deliberation: AutonomyDeliberationComposition,
        val planning: PlanningComposition,
        val bridge: AutonomyPlanningBridge
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "autonomy-planning-${sequence.incrementAndGet()}" }
        )
        val autonomy = AutonomyComposition(foundation)
        val gate = ControlledAutonomyDeliberationGate(foundation, autonomy)
        val deliberation = AutonomyDeliberationComposition(foundation)
        val planning = PlanningComposition(foundation)
        val preflight = AutonomyDeliberationPreflight(foundation, deliberation, gate)
        return Fixture(
            logs = logs,
            autonomy = autonomy,
            gate = gate,
            deliberation = deliberation,
            planning = planning,
            bridge = AutonomyPlanningBridge(foundation, preflight, planning)
        )
    }

    private fun proposal() = AutonomyProposal(
        id = AutonomyProposalId("autonomy-1"),
        origin = AutonomyOrigin.Declared(AutonomySourceId("goal-context")),
        objective = "private autonomy objective",
        triggerDescription = "private autonomy trigger",
        priority = AutonomyPriority.NORMAL,
        budget = AutonomyBudget(2),
        createdAt = Instant.parse("2026-08-29T15:20:00Z")
    )

    private fun prepare(f: Fixture): AutonomyDeliberationOwnership {
        val autonomyOwnership = assertIs<AutonomyInstallResult.Installed>(f.autonomy.install(proposal())).ownership
        val claim = assertIs<AutonomyDeliberationAttemptResult.Claimed>(
            f.gate.claimAttempt(autonomyOwnership.proposal.id, autonomyOwnership.generation)
        )
        return assertIs<AutonomyDeliberationInstallResult.Installed>(
            f.deliberation.install(
                AutonomyDeliberationRequest(
                    id = AutonomyDeliberationRequestId("request-1"),
                    autonomy = AutonomyAttemptReference(
                        claim.evidence.proposal.id,
                        claim.evidence.generation,
                        claim.evidence.attemptNumber
                    ),
                    objective = "private deliberation objective",
                    createdAt = Instant.parse("2026-08-29T15:21:00Z")
                )
            )
        ).ownership
    }

    private fun bridgeRequest(ownership: AutonomyDeliberationOwnership) = AutonomyPlanningBridgeRequest(
        deliberationRequestId = ownership.request.id,
        deliberationGeneration = ownership.generation,
        planningProposalId = PlanningProposalId("plan-1"),
        goal = "private planning goal",
        steps = listOf(
            PlanningStep(PlanningStepId("step-1"), "private planning step")
        ),
        createdAt = Instant.parse("2026-08-29T15:22:00Z")
    )

    @Test
    fun exact_live_deliberation_installs_planning_with_trusted_structural_origin() {
        val f = fixture()
        val requestOwnership = prepare(f)

        val result = assertIs<AutonomyPlanningBridgeResult.Installed>(
            f.bridge.install(bridgeRequest(requestOwnership))
        )

        val proposal = result.planning.proposal
        assertEquals("autonomy-deliberation", proposal.origin.sourceId.value)
        assertTrue(proposal.origin.sourceReference!!.value.contains("request=request-1@"))
        assertTrue(proposal.origin.sourceReference!!.value.contains("proposal=autonomy-1@"))
        assertTrue(proposal.origin.sourceReference!!.value.contains("attempt=1"))
        assertTrue(f.planning.contains(proposal.id))
    }

    @Test
    fun cancellation_before_bridge_causes_zero_planning_writes() {
        val f = fixture()
        val requestOwnership = prepare(f)
        assertIs<AutonomyDeliberationCancellationResult.Cancelled>(
            f.gate.cancel(
                requestOwnership.request.autonomy.proposalId,
                requestOwnership.request.autonomy.proposalGeneration
            )
        )

        val result = f.bridge.install(bridgeRequest(requestOwnership))

        assertIs<AutonomyPlanningBridgeResult.Rejected>(result)
        assertFalse(f.planning.contains(PlanningProposalId("plan-1")))
    }

    @Test
    fun stale_request_generation_causes_zero_planning_writes() {
        val f = fixture()
        val stale = prepare(f)
        assertTrue(stale.remove())
        assertIs<AutonomyDeliberationInstallResult.Installed>(
            f.deliberation.install(stale.request)
        )

        val result = f.bridge.install(bridgeRequest(stale))

        assertIs<AutonomyPlanningBridgeResult.Rejected>(result)
        assertFalse(f.planning.contains(PlanningProposalId("plan-1")))
    }

    @Test
    fun duplicate_planning_id_rejects_without_replacement() {
        val f = fixture()
        val requestOwnership = prepare(f)
        val first = assertIs<AutonomyPlanningBridgeResult.Installed>(
            f.bridge.install(bridgeRequest(requestOwnership))
        )

        val second = f.bridge.install(bridgeRequest(requestOwnership))

        assertIs<AutonomyPlanningBridgeResult.Rejected>(second)
        assertEquals(first.planning.proposal, f.planning.find(PlanningProposalId("plan-1")))
    }

    @Test
    fun bridge_does_not_create_decision_authority_or_execution_semantics_in_observability() {
        val f = fixture()
        val requestOwnership = prepare(f)
        f.bridge.install(bridgeRequest(requestOwnership))

        val forbidden = setOf(
            "decision", "authority", "authorized", "capability", "permission",
            "execution", "execute", "executed", "executor", "agent", "scheduler"
        )
        val bridgeEvents = f.logs.snapshot().filter {
            it.marker == "AUTONOMY_PLANNING_BRIDGE_INSTALLED" ||
                it.marker == "AUTONOMY_PLANNING_BRIDGE_REJECTED"
        }
        assertFalse(bridgeEvents.any { event ->
            event.metadata.keys.any { key -> forbidden.any { key.lowercase().contains(it) } }
        })
    }

    @Test
    fun private_cognitive_payload_is_absent_from_bridge_observability() {
        val f = fixture()
        val requestOwnership = prepare(f)
        f.bridge.install(bridgeRequest(requestOwnership))

        val secrets = setOf(
            "private autonomy objective",
            "private autonomy trigger",
            "private deliberation objective",
            "private planning goal",
            "private planning step"
        )
        assertFalse(f.logs.snapshot().any { event ->
            event.message in secrets || event.metadata.values.any { it in secrets }
        })
    }
}
