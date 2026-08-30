package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
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
import pro.liliya.core.reasoning.ReasoningGeneration
import pro.liliya.core.reasoning.ReasoningInstallResult
import pro.liliya.core.reasoning.ReasoningOrigin
import pro.liliya.core.reasoning.ReasoningPremise
import pro.liliya.core.reasoning.ReasoningPremiseId
import pro.liliya.core.reasoning.ReasoningSourceId

class ControlledAgentCoordinationReasoningBridgeRaceContractTest {
    private data class Fixture(
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
            correlationIds = CorrelationIdGenerator { "coord-reason-race-${sequence.incrementAndGet()}" }
        )
        return Fixture(
            foundation = foundation,
            planning = PlanningComposition(foundation),
            reasoning = ReasoningComposition(foundation)
        )
    }

    private fun evidence() = AgentCoordinationDeliberationReadyEvidence(
        coordination = ExactAgentCoordinationReference(
            AgentCoordinationId("coord-reason-race"),
            AgentCoordinationGeneration(3)
        ),
        attemptBindingGeneration = AgentCoordinationAttemptBindingGeneration(4),
        participant = ExactAgentReference(AgentId("agent-a"), AgentGeneration(7)),
        requestId = AutonomyDeliberationRequestId("delib-reason-race"),
        requestGeneration = AutonomyDeliberationGeneration(5),
        attempt = AutonomyAttemptReference(
            AutonomyProposalId("autonomy-reason-race"),
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

    private fun planningProposal(
        evidence: AgentCoordinationDeliberationReadyEvidence,
        goal: String = "private planning goal"
    ) = PlanningProposal(
        id = PlanningProposalId("planning-coord-race"),
        origin = PlanningOrigin(
            sourceId = PlanningSourceId("agent-coordination-deliberation"),
            sourceReference = PlanningSourceReference(planningReference(evidence))
        ),
        goal = goal,
        steps = listOf(PlanningStep(PlanningStepId("step-1"), "private planning step")),
        createdAt = Instant.parse("2026-08-30T08:00:00Z")
    )

    private fun installPlanning(
        f: Fixture,
        ready: AgentCoordinationDeliberationReadyEvidence
    ): PlanningOwnership = assertIs<PlanningInstallResult.Installed>(
        f.planning.install(planningProposal(ready))
    ).ownership

    private fun request(planning: PlanningOwnership) = AgentCoordinationReasoningRequest(
        deliberationRequestId = AutonomyDeliberationRequestId("delib-reason-race"),
        deliberationGeneration = AutonomyDeliberationGeneration(5),
        planningProposalId = planning.proposal.id,
        planningGeneration = planning.generation,
        reasoningArtifactId = ReasoningArtifactId("reasoning-coord-race"),
        premises = listOf(
            ReasoningPremise(ReasoningPremiseId("premise-1"), "private coordinated reasoning premise")
        ),
        analysis = "private coordinated reasoning analysis",
        conclusion = "private coordinated reasoning conclusion",
        createdAt = Instant.parse("2026-08-30T08:01:00Z")
    )

    private fun checker(vararg results: AgentCoordinationDeliberationPreflightResult):
        AgentCoordinationDeliberationPreflightChecker {
        require(results.isNotEmpty())
        val index = AtomicInteger(0)
        return AgentCoordinationDeliberationPreflightChecker { _, _ ->
            results[index.getAndIncrement().coerceAtMost(results.lastIndex)]
        }
    }

    @Test
    fun initial_preflight_rejection_writes_no_reasoning() {
        val f = fixture()
        val ready = evidence()
        val planning = installPlanning(f, ready)
        val bridge = ControlledAgentCoordinationReasoningBridge(
            foundation = f.foundation,
            preflight = checker(AgentCoordinationDeliberationPreflightResult.Rejected("not ready")),
            planning = f.planning,
            reasoning = f.reasoning,
            installer = AgentCoordinationReasoningInstaller(f.reasoning::install),
            testOnly = Unit
        )

        assertIs<AgentCoordinationReasoningResult.Rejected>(bridge.install(request(planning)))
        assertEquals(0, f.reasoning.snapshot().size)
    }

    @Test
    fun planning_removed_after_reasoning_write_compensates_exact_reasoning_generation() {
        val f = fixture()
        val ready = evidence()
        val planning = installPlanning(f, ready)
        val installer = AgentCoordinationReasoningInstaller { artifact ->
            val installed = f.reasoning.install(artifact)
            assertEquals(true, planning.remove())
            installed
        }
        val bridge = ControlledAgentCoordinationReasoningBridge(
            foundation = f.foundation,
            preflight = checker(
                AgentCoordinationDeliberationPreflightResult.Ready(ready),
                AgentCoordinationDeliberationPreflightResult.Ready(ready)
            ),
            planning = f.planning,
            reasoning = f.reasoning,
            installer = installer,
            testOnly = Unit
        )

        assertIs<AgentCoordinationReasoningResult.Rejected>(bridge.install(request(planning)))
        assertNull(f.reasoning.inspect(ReasoningArtifactId("reasoning-coord-race")))
    }

    @Test
    fun planning_replacement_after_reasoning_write_compensates_reasoning_and_preserves_replacement() {
        val f = fixture()
        val ready = evidence()
        val planning = installPlanning(f, ready)
        lateinit var replacement: PlanningOwnership
        val installer = AgentCoordinationReasoningInstaller { artifact ->
            val installed = f.reasoning.install(artifact)
            assertEquals(true, planning.remove())
            replacement = assertIs<PlanningInstallResult.Installed>(
                f.planning.install(planningProposal(ready, goal = "replacement private planning goal"))
            ).ownership
            installed
        }
        val bridge = ControlledAgentCoordinationReasoningBridge(
            foundation = f.foundation,
            preflight = checker(
                AgentCoordinationDeliberationPreflightResult.Ready(ready),
                AgentCoordinationDeliberationPreflightResult.Ready(ready)
            ),
            planning = f.planning,
            reasoning = f.reasoning,
            installer = installer,
            testOnly = Unit
        )

        assertIs<AgentCoordinationReasoningResult.Rejected>(bridge.install(request(planning)))
        assertNull(f.reasoning.inspect(ReasoningArtifactId("reasoning-coord-race")))
        assertEquals(replacement.generation, assertNotNull(f.planning.inspect(planning.proposal.id)).generation)
    }

    @Test
    fun compensation_does_not_remove_or_fail_on_newer_reasoning_generation() {
        val f = fixture()
        val ready = evidence()
        val planning = installPlanning(f, ready)
        var replacementGeneration: ReasoningGeneration? = null
        val installer = AgentCoordinationReasoningInstaller { artifact ->
            val original = assertIs<ReasoningInstallResult.Installed>(f.reasoning.install(artifact)).ownership
            assertEquals(true, original.remove())
            val replacementArtifact = ReasoningArtifact(
                id = artifact.id,
                origin = ReasoningOrigin(ReasoningSourceId("replacement-test-source")),
                premises = listOf(
                    ReasoningPremise(ReasoningPremiseId("replacement-premise"), "private replacement premise")
                ),
                analysis = "private replacement analysis",
                conclusion = "private replacement conclusion",
                createdAt = Instant.parse("2026-08-30T08:02:00Z")
            )
            replacementGeneration = assertIs<ReasoningInstallResult.Installed>(
                f.reasoning.install(replacementArtifact)
            ).ownership.generation
            ReasoningInstallResult.Installed(original)
        }
        val bridge = ControlledAgentCoordinationReasoningBridge(
            foundation = f.foundation,
            preflight = checker(
                AgentCoordinationDeliberationPreflightResult.Ready(ready),
                AgentCoordinationDeliberationPreflightResult.Rejected("simulated governance race")
            ),
            planning = f.planning,
            reasoning = f.reasoning,
            installer = installer,
            testOnly = Unit
        )

        assertIs<AgentCoordinationReasoningResult.Rejected>(bridge.install(request(planning)))
        assertEquals(
            assertNotNull(replacementGeneration),
            assertNotNull(f.reasoning.inspect(ReasoningArtifactId("reasoning-coord-race"))).generation
        )
    }
}
