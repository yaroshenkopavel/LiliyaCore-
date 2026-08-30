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
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningInstallResult
import pro.liliya.core.planning.PlanningOrigin
import pro.liliya.core.planning.PlanningOwnership
import pro.liliya.core.planning.PlanningProposal
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.planning.PlanningSourceId
import pro.liliya.core.planning.PlanningSourceReference
import pro.liliya.core.planning.PlanningStep
import pro.liliya.core.planning.PlanningStepId
import pro.liliya.core.reasoning.ReasoningArtifact
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.reasoning.ReasoningInstallResult
import pro.liliya.core.reasoning.ReasoningOwnership
import pro.liliya.core.reasoning.ReasoningPremise
import pro.liliya.core.reasoning.ReasoningPremiseId
import pro.liliya.core.reasoning.ReasoningSourceId

class ControlledAgentCoordinationReasoningBridgeContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val planning: PlanningComposition,
        val reasoning: ReasoningComposition
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "coord-reason-${sequence.incrementAndGet()}" }
        )
        return Fixture(
            logs = logs,
            foundation = foundation,
            planning = PlanningComposition(foundation),
            reasoning = ReasoningComposition(foundation)
        )
    }

    private fun evidence(
        coordinationGeneration: Long = 3L,
        bindingGeneration: Long = 4L
    ) = AgentCoordinationDeliberationReadyEvidence(
        coordination = ExactAgentCoordinationReference(
            AgentCoordinationId("coord-reason"),
            AgentCoordinationGeneration(coordinationGeneration)
        ),
        attemptBindingGeneration = AgentCoordinationAttemptBindingGeneration(bindingGeneration),
        participant = ExactAgentReference(AgentId("agent-a"), AgentGeneration(7)),
        requestId = AutonomyDeliberationRequestId("delib-reason"),
        requestGeneration = AutonomyDeliberationGeneration(5),
        attempt = AutonomyAttemptReference(
            AutonomyProposalId("autonomy-reason"),
            AutonomyGeneration(6),
            2
        )
    )

    private fun planningReference(evidence: AgentCoordinationDeliberationReadyEvidence): String =
        "coordination=${evidence.coordination.id.value}@${evidence.coordination.generation.value};" +
            "attemptBinding=${evidence.attemptBindingGeneration.value};" +
            "participant=${evidence.participant.id.value}@${evidence.participant.generation.value};" +
            "request=${evidence.requestId.value}@${evidence.requestGeneration.value};" +
            "proposal=${evidence.attempt.proposalId.value}@${evidence.attempt.proposalGeneration.value};" +
            "attempt=${evidence.attempt.attemptNumber}"

    private fun installPlanning(
        f: Fixture,
        ready: AgentCoordinationDeliberationReadyEvidence = evidence(),
        id: String = "planning-coord"
    ): PlanningOwnership = assertIs<PlanningInstallResult.Installed>(
        f.planning.install(
            PlanningProposal(
                id = PlanningProposalId(id),
                origin = PlanningOrigin(
                    sourceId = PlanningSourceId("agent-coordination-deliberation"),
                    sourceReference = PlanningSourceReference(planningReference(ready))
                ),
                goal = "private planning goal",
                steps = listOf(PlanningStep(PlanningStepId("step-1"), "private planning step")),
                createdAt = Instant.parse("2026-08-30T08:00:00Z")
            )
        )
    ).ownership

    private fun request(
        planningGeneration: PlanningGeneration,
        secret: String = "private coordinated reasoning content"
    ) = AgentCoordinationReasoningRequest(
        deliberationRequestId = AutonomyDeliberationRequestId("delib-reason"),
        deliberationGeneration = AutonomyDeliberationGeneration(5),
        planningProposalId = PlanningProposalId("planning-coord"),
        planningGeneration = planningGeneration,
        reasoningArtifactId = ReasoningArtifactId("reasoning-coord"),
        premises = listOf(ReasoningPremise(ReasoningPremiseId("premise-1"), secret)),
        analysis = secret,
        conclusion = secret,
        createdAt = Instant.parse("2026-08-30T08:01:00Z")
    )

    private fun checker(vararg results: AgentCoordinationDeliberationPreflightResult):
        AgentCoordinationDeliberationPreflightChecker {
        require(results.isNotEmpty()) { "checker requires at least one preflight result" }
        val index = AtomicInteger(0)
        return AgentCoordinationDeliberationPreflightChecker { _, _ ->
            val position = index.getAndIncrement().coerceAtMost(results.lastIndex)
            results[position]
        }
    }

    @Test
    fun stable_exact_planning_and_readiness_install_one_reasoning_artifact() {
        val f = fixture()
        val ready = evidence()
        val planning = installPlanning(f, ready)
        val bridge = ControlledAgentCoordinationReasoningBridge(
            foundation = f.foundation,
            preflight = checker(
                AgentCoordinationDeliberationPreflightResult.Ready(ready),
                AgentCoordinationDeliberationPreflightResult.Ready(ready)
            ),
            planning = f.planning,
            reasoning = f.reasoning,
            installer = AgentCoordinationReasoningInstaller(f.reasoning::install),
            testOnly = Unit
        )

        val installed = assertIs<AgentCoordinationReasoningResult.Installed>(
            bridge.install(request(planning.generation))
        )

        assertEquals(1, f.reasoning.snapshot().size)
        assertEquals(ReasoningSourceId("agent-coordination-planning"), installed.reasoning.artifact.origin.sourceId)
        val reference = assertNotNull(installed.reasoning.artifact.origin.sourceReference).value
        assertEquals(true, reference.contains("coordination=coord-reason@3"))
        assertEquals(true, reference.contains("attemptBinding=4"))
        assertEquals(true, reference.contains("participant=agent-a@7"))
        assertEquals(true, reference.contains("request=delib-reason@5"))
        assertEquals(true, reference.contains("proposal=autonomy-reason@6"))
        assertEquals(true, reference.contains("attempt=2"))
        assertEquals(true, reference.contains("planning=planning-coord@${planning.generation.value}"))
    }

    @Test
    fun stale_planning_generation_rejects_before_reasoning_write() {
        val f = fixture()
        val ready = evidence()
        val planning = installPlanning(f, ready)
        val bridge = ControlledAgentCoordinationReasoningBridge(
            foundation = f.foundation,
            preflight = checker(AgentCoordinationDeliberationPreflightResult.Ready(ready)),
            planning = f.planning,
            reasoning = f.reasoning,
            installer = AgentCoordinationReasoningInstaller(f.reasoning::install),
            testOnly = Unit
        )

        assertIs<AgentCoordinationReasoningResult.Rejected>(
            bridge.install(request(PlanningGeneration(planning.generation.value + 1)))
        )
        assertEquals(0, f.reasoning.snapshot().size)
    }

    @Test
    fun mismatched_planning_provenance_rejects_before_reasoning_write() {
        val f = fixture()
        val ready = evidence()
        val other = evidence(bindingGeneration = 99)
        val planning = installPlanning(f, other)
        val bridge = ControlledAgentCoordinationReasoningBridge(
            foundation = f.foundation,
            preflight = checker(AgentCoordinationDeliberationPreflightResult.Ready(ready)),
            planning = f.planning,
            reasoning = f.reasoning,
            installer = AgentCoordinationReasoningInstaller(f.reasoning::install),
            testOnly = Unit
        )

        assertIs<AgentCoordinationReasoningResult.Rejected>(bridge.install(request(planning.generation)))
        assertEquals(0, f.reasoning.snapshot().size)
    }

    @Test
    fun governance_change_after_reasoning_write_rolls_back_exact_created_generation() {
        val f = fixture()
        val ready = evidence()
        val planning = installPlanning(f, ready)
        val bridge = ControlledAgentCoordinationReasoningBridge(
            foundation = f.foundation,
            preflight = checker(
                AgentCoordinationDeliberationPreflightResult.Ready(ready),
                AgentCoordinationDeliberationPreflightResult.Rejected("participant stopped")
            ),
            planning = f.planning,
            reasoning = f.reasoning,
            installer = AgentCoordinationReasoningInstaller(f.reasoning::install),
            testOnly = Unit
        )

        assertIs<AgentCoordinationReasoningResult.Rejected>(bridge.install(request(planning.generation)))
        assertNull(f.reasoning.inspect(ReasoningArtifactId("reasoning-coord")))
    }

    @Test
    fun changed_exact_readiness_after_write_rolls_back_reasoning() {
        val f = fixture()
        val initial = evidence()
        val changed = evidence(bindingGeneration = 5)
        val planning = installPlanning(f, initial)
        val bridge = ControlledAgentCoordinationReasoningBridge(
            foundation = f.foundation,
            preflight = checker(
                AgentCoordinationDeliberationPreflightResult.Ready(initial),
                AgentCoordinationDeliberationPreflightResult.Ready(changed)
            ),
            planning = f.planning,
            reasoning = f.reasoning,
            installer = AgentCoordinationReasoningInstaller(f.reasoning::install),
            testOnly = Unit
        )

        assertIs<AgentCoordinationReasoningResult.Rejected>(bridge.install(request(planning.generation)))
        assertEquals(0, f.reasoning.snapshot().size)
    }

    @Test
    fun compensation_failure_is_explicit_failed_and_critical_observable() {
        val f = fixture()
        val ready = evidence()
        val planning = installPlanning(f, ready)
        val installer = AgentCoordinationReasoningInstaller { artifact ->
            val real = assertIs<ReasoningInstallResult.Installed>(f.reasoning.install(artifact)).ownership
            ReasoningInstallResult.Installed(
                object : ReasoningOwnership {
                    override val artifact: ReasoningArtifact = real.artifact
                    override val generation = real.generation
                    override fun remove(): Boolean = false
                }
            )
        }
        val bridge = ControlledAgentCoordinationReasoningBridge(
            foundation = f.foundation,
            preflight = checker(
                AgentCoordinationDeliberationPreflightResult.Ready(ready),
                AgentCoordinationDeliberationPreflightResult.Rejected("simulated race")
            ),
            planning = f.planning,
            reasoning = f.reasoning,
            installer = installer,
            testOnly = Unit
        )

        assertIs<AgentCoordinationReasoningResult.Failed>(bridge.install(request(planning.generation)))
        assertNotNull(f.reasoning.inspect(ReasoningArtifactId("reasoning-coord")))
        assertEquals(
            1,
            f.logs.snapshot().count { it.marker == "AGENT_COORDINATION_REASONING_COMPENSATION_FAILED" }
        )
    }

    @Test
    fun private_reasoning_content_does_not_enter_bridge_observability_and_api_has_no_execution_semantics() {
        val f = fixture()
        val ready = evidence()
        val planning = installPlanning(f, ready)
        val secret = "never-log-coordination-reasoning-secret"
        val bridge = ControlledAgentCoordinationReasoningBridge(
            foundation = f.foundation,
            preflight = checker(
                AgentCoordinationDeliberationPreflightResult.Ready(ready),
                AgentCoordinationDeliberationPreflightResult.Ready(ready)
            ),
            planning = f.planning,
            reasoning = f.reasoning,
            installer = AgentCoordinationReasoningInstaller(f.reasoning::install),
            testOnly = Unit
        )

        assertIs<AgentCoordinationReasoningResult.Installed>(
            bridge.install(request(planning.generation, secret))
        )
        assertFalse(f.logs.snapshot().any { event ->
            event.message == secret || event.metadata.values.any { it == secret }
        })

        val forbidden = setOf(
            "authority", "authorize", "permission", "capability", "execution", "execute",
            "executor", "scheduler", "schedule", "decision"
        )
        val names = listOf(
            AgentCoordinationReasoningRequest::class.java,
            AgentCoordinationReasoningResult::class.java
        ).flatMap { type -> type.methods.map { it.name.lowercase() } }
        assertFalse(names.any { name -> forbidden.any { token -> name.contains(token) } })
    }
}
