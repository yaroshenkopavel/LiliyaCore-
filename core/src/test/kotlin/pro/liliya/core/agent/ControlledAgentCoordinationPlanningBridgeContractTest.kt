package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import pro.liliya.core.autonomy.AutonomyAttemptReference
import pro.liliya.core.autonomy.AutonomyDeliberationGeneration
import pro.liliya.core.autonomy.AutonomyDeliberationRequestId
import pro.liliya.core.autonomy.AutonomyGeneration
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.planning.PlanningInstallResult
import pro.liliya.core.planning.PlanningOwnership
import pro.liliya.core.planning.PlanningProposal
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.planning.PlanningSourceId
import pro.liliya.core.planning.PlanningStep
import pro.liliya.core.planning.PlanningStepId

class ControlledAgentCoordinationPlanningBridgeContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val planning: PlanningComposition
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "coord-plan-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, foundation, PlanningComposition(foundation))
    }

    private fun evidence(
        coordinationGeneration: Long = 3L,
        bindingGeneration: Long = 4L
    ) = AgentCoordinationDeliberationReadyEvidence(
        coordination = ExactAgentCoordinationReference(
            AgentCoordinationId("coord-plan"),
            AgentCoordinationGeneration(coordinationGeneration)
        ),
        attemptBindingGeneration = AgentCoordinationAttemptBindingGeneration(bindingGeneration),
        participant = ExactAgentReference(AgentId("agent-a"), AgentGeneration(7)),
        requestId = AutonomyDeliberationRequestId("delib-plan"),
        requestGeneration = AutonomyDeliberationGeneration(5),
        attempt = AutonomyAttemptReference(
            AutonomyProposalId("autonomy-plan"),
            AutonomyGeneration(6),
            2
        )
    )

    private fun request(
        goal: String = "private coordinated planning goal"
    ) = AgentCoordinationPlanningRequest(
        deliberationRequestId = AutonomyDeliberationRequestId("delib-plan"),
        deliberationGeneration = AutonomyDeliberationGeneration(5),
        planningProposalId = PlanningProposalId("planning-coord"),
        goal = goal,
        steps = listOf(PlanningStep(PlanningStepId("step-1"), "private planning step")),
        createdAt = Instant.parse("2026-08-30T07:00:00Z")
    )

    private fun checker(vararg results: AgentCoordinationDeliberationPreflightResult):
        AgentCoordinationDeliberationPreflightChecker {
        val index = AtomicInteger(0)
        return AgentCoordinationDeliberationPreflightChecker {
            val position = index.getAndIncrement().coerceAtMost(results.lastIndex)
            results[position]
        }
    }

    @Test
    fun stable_exact_readiness_installs_one_planning_proposal_with_trusted_structural_origin() {
        val f = fixture()
        val ready = evidence()
        val bridge = ControlledAgentCoordinationPlanningBridge(
            foundation = f.foundation,
            preflight = checker(
                AgentCoordinationDeliberationPreflightResult.Ready(ready),
                AgentCoordinationDeliberationPreflightResult.Ready(ready)
            ),
            planning = f.planning,
            installer = AgentCoordinationPlanningInstaller(f.planning::install),
            testOnly = Unit
        )

        val installed = assertIs<AgentCoordinationPlanningResult.Installed>(
            bridge.install(request())
        )

        assertEquals(1, f.planning.snapshot().size)
        assertEquals(PlanningSourceId("agent-coordination-deliberation"), installed.planning.proposal.origin.sourceId)
        val reference = assertNotNull(installed.planning.proposal.origin.sourceReference).value
        assertEquals(true, reference.contains("coordination=coord-plan@3"))
        assertEquals(true, reference.contains("attemptBinding=4"))
        assertEquals(true, reference.contains("participant=agent-a@7"))
        assertEquals(true, reference.contains("request=delib-plan@5"))
        assertEquals(true, reference.contains("proposal=autonomy-plan@6"))
        assertEquals(true, reference.contains("attempt=2"))
    }

    @Test
    fun initial_preflight_rejection_creates_zero_planning_writes() {
        val f = fixture()
        val bridge = ControlledAgentCoordinationPlanningBridge(
            foundation = f.foundation,
            preflight = checker(
                AgentCoordinationDeliberationPreflightResult.Rejected("coordination not live")
            ),
            planning = f.planning,
            installer = AgentCoordinationPlanningInstaller(f.planning::install),
            testOnly = Unit
        )

        assertIs<AgentCoordinationPlanningResult.Rejected>(bridge.install(request()))
        assertEquals(0, f.planning.snapshot().size)
    }

    @Test
    fun governance_change_after_planning_write_rolls_back_exact_created_generation() {
        val f = fixture()
        val ready = evidence()
        val bridge = ControlledAgentCoordinationPlanningBridge(
            foundation = f.foundation,
            preflight = checker(
                AgentCoordinationDeliberationPreflightResult.Ready(ready),
                AgentCoordinationDeliberationPreflightResult.Rejected("participant stopped")
            ),
            planning = f.planning,
            installer = AgentCoordinationPlanningInstaller(f.planning::install),
            testOnly = Unit
        )

        assertIs<AgentCoordinationPlanningResult.Rejected>(bridge.install(request()))
        assertNull(f.planning.inspect(PlanningProposalId("planning-coord")))
    }

    @Test
    fun changed_exact_readiness_after_write_rolls_back_planning() {
        val f = fixture()
        val initial = evidence()
        val changed = evidence(bindingGeneration = 5)
        val bridge = ControlledAgentCoordinationPlanningBridge(
            foundation = f.foundation,
            preflight = checker(
                AgentCoordinationDeliberationPreflightResult.Ready(initial),
                AgentCoordinationDeliberationPreflightResult.Ready(changed)
            ),
            planning = f.planning,
            installer = AgentCoordinationPlanningInstaller(f.planning::install),
            testOnly = Unit
        )

        assertIs<AgentCoordinationPlanningResult.Rejected>(bridge.install(request()))
        assertEquals(0, f.planning.snapshot().size)
    }

    @Test
    fun compensation_failure_is_explicit_failed_and_critical_observable() {
        val f = fixture()
        val ready = evidence()
        val installer = AgentCoordinationPlanningInstaller { proposal ->
            val real = assertIs<PlanningInstallResult.Installed>(f.planning.install(proposal)).ownership
            PlanningInstallResult.Installed(
                object : PlanningOwnership {
                    override val proposal: PlanningProposal = real.proposal
                    override val generation = real.generation
                    override fun remove(): Boolean = false
                }
            )
        }
        val bridge = ControlledAgentCoordinationPlanningBridge(
            foundation = f.foundation,
            preflight = checker(
                AgentCoordinationDeliberationPreflightResult.Ready(ready),
                AgentCoordinationDeliberationPreflightResult.Rejected("simulated race")
            ),
            planning = f.planning,
            installer = installer,
            testOnly = Unit
        )

        assertIs<AgentCoordinationPlanningResult.Failed>(bridge.install(request()))
        assertNotNull(f.planning.inspect(PlanningProposalId("planning-coord")))
        assertEquals(
            1,
            f.logs.snapshot().count { it.marker == "AGENT_COORDINATION_PLANNING_COMPENSATION_FAILED" }
        )
    }

    @Test
    fun private_goal_and_steps_do_not_enter_bridge_observability() {
        val f = fixture()
        val ready = evidence()
        val secret = "never-log-coordination-planning-secret"
        val secretRequest = AgentCoordinationPlanningRequest(
            deliberationRequestId = AutonomyDeliberationRequestId("delib-plan"),
            deliberationGeneration = AutonomyDeliberationGeneration(5),
            planningProposalId = PlanningProposalId("planning-coord"),
            goal = secret,
            steps = listOf(PlanningStep(PlanningStepId("step-1"), secret)),
            createdAt = Instant.parse("2026-08-30T07:00:00Z")
        )
        val bridge = ControlledAgentCoordinationPlanningBridge(
            foundation = f.foundation,
            preflight = checker(
                AgentCoordinationDeliberationPreflightResult.Ready(ready),
                AgentCoordinationDeliberationPreflightResult.Ready(ready)
            ),
            planning = f.planning,
            installer = AgentCoordinationPlanningInstaller(f.planning::install),
            testOnly = Unit
        )

        assertIs<AgentCoordinationPlanningResult.Installed>(bridge.install(secretRequest))
        assertFalse(f.logs.snapshot().any { event ->
            event.message == secret || event.metadata.values.any { it == secret }
        })
    }

    @Test
    fun result_and_request_api_expose_no_authority_execution_or_scheduler_semantics() {
        val forbidden = setOf(
            "authority", "authorize", "permission", "capability", "execution", "execute",
            "executor", "scheduler", "schedule", "reasoning", "decision"
        )
        val names = listOf(
            AgentCoordinationPlanningRequest::class.java,
            AgentCoordinationPlanningResult::class.java
        ).flatMap { type -> type.methods.map { it.name.lowercase() } }

        assertFalse(names.any { name -> forbidden.any { token -> name.contains(token) } })
    }
}
