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
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.decision.DecisionInstallResult
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
import pro.liliya.core.reasoning.ReasoningOrigin
import pro.liliya.core.reasoning.ReasoningOwnership
import pro.liliya.core.reasoning.ReasoningPremise
import pro.liliya.core.reasoning.ReasoningPremiseId
import pro.liliya.core.reasoning.ReasoningSourceId
import pro.liliya.core.reasoning.ReasoningSourceReference

class ControlledAgentCoordinationDecisionBridgeRaceContractTest {
    private data class Fixture(
        val foundation: FoundationComposition,
        val planning: PlanningComposition,
        val reasoning: ReasoningComposition,
        val decisions: DecisionComposition
    )

    private data class Inputs(
        val planning: PlanningOwnership,
        val reasoning: ReasoningOwnership
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "coord-decision-race-${sequence.incrementAndGet()}" }
        )
        return Fixture(
            foundation = foundation,
            planning = PlanningComposition(foundation),
            reasoning = ReasoningComposition(foundation),
            decisions = DecisionComposition(foundation)
        )
    }

    private fun evidence() = AgentCoordinationDeliberationReadyEvidence(
        coordination = ExactAgentCoordinationReference(
            AgentCoordinationId("coord-decision-race"),
            AgentCoordinationGeneration(3)
        ),
        attemptBindingGeneration = AgentCoordinationAttemptBindingGeneration(4),
        participant = ExactAgentReference(AgentId("agent-a"), AgentGeneration(7)),
        requestId = AutonomyDeliberationRequestId("delib-decision-race"),
        requestGeneration = AutonomyDeliberationGeneration(5),
        attempt = AutonomyAttemptReference(
            AutonomyProposalId("autonomy-decision-race"),
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

    private fun planningProposal(evidence: AgentCoordinationDeliberationReadyEvidence) = PlanningProposal(
        id = PlanningProposalId("planning-coord-decision-race"),
        origin = PlanningOrigin(
            sourceId = PlanningSourceId("agent-coordination-deliberation"),
            sourceReference = PlanningSourceReference(planningReference(evidence))
        ),
        goal = "private planning goal",
        steps = listOf(PlanningStep(PlanningStepId("step-1"), "private planning step")),
        createdAt = Instant.parse("2026-08-30T08:00:00Z")
    )

    private fun installInputs(f: Fixture, ready: AgentCoordinationDeliberationReadyEvidence): Inputs {
        val planning = assertIs<PlanningInstallResult.Installed>(
            f.planning.install(planningProposal(ready))
        ).ownership
        val reasoning = assertIs<ReasoningInstallResult.Installed>(
            f.reasoning.install(
                ReasoningArtifact(
                    id = ReasoningArtifactId("reasoning-coord-decision-race"),
                    origin = ReasoningOrigin(
                        sourceId = ReasoningSourceId("agent-coordination-planning"),
                        sourceReference = ReasoningSourceReference(
                            planningReference(ready) + ";planning=${planning.proposal.id.value}@${planning.generation.value}"
                        )
                    ),
                    premises = listOf(ReasoningPremise(ReasoningPremiseId("premise-1"), "private premise")),
                    analysis = "private analysis",
                    conclusion = "private conclusion",
                    createdAt = Instant.parse("2026-08-30T08:01:00Z")
                )
            )
        ).ownership
        return Inputs(planning, reasoning)
    }

    private fun request(inputs: Inputs) = AgentCoordinationDecisionRequest(
        deliberationRequestId = AutonomyDeliberationRequestId("delib-decision-race"),
        deliberationGeneration = AutonomyDeliberationGeneration(5),
        planningProposalId = inputs.planning.proposal.id,
        planningGeneration = inputs.planning.generation,
        reasoningArtifactId = inputs.reasoning.artifact.id,
        reasoningGeneration = inputs.reasoning.generation,
        decisionId = DecisionId("decision-coord-race"),
        options = listOf(DecisionOption(DecisionOptionId("option-a"), "private option")),
        selectedOptionId = DecisionOptionId("option-a"),
        rationale = "private rationale",
        createdAt = Instant.parse("2026-08-30T08:02:00Z")
    )

    private fun checker(vararg results: AgentCoordinationDeliberationPreflightResult):
        AgentCoordinationDeliberationPreflightChecker {
        require(results.isNotEmpty())
        val index = AtomicInteger(0)
        return AgentCoordinationDeliberationPreflightChecker { _, _ ->
            results[index.getAndIncrement().coerceAtMost(results.lastIndex)]
        }
    }

    private fun bridge(
        f: Fixture,
        preflight: AgentCoordinationDeliberationPreflightChecker,
        installer: AgentCoordinationDecisionInstaller = AgentCoordinationDecisionInstaller(f.decisions::install)
    ) = ControlledAgentCoordinationDecisionBridge(
        foundation = f.foundation,
        preflight = preflight,
        planning = f.planning,
        reasoning = f.reasoning,
        decisions = f.decisions,
        installer = installer,
        testOnly = Unit
    )

    @Test
    fun initial_preflight_rejection_writes_no_decision() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)

        assertIs<AgentCoordinationDecisionResult.Rejected>(
            bridge(
                f,
                checker(AgentCoordinationDeliberationPreflightResult.Rejected("not ready"))
            ).install(request(inputs))
        )
        assertEquals(0, f.decisions.snapshot().size)
    }

    @Test
    fun planning_removed_after_decision_write_compensates_exact_decision_generation() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        val installer = AgentCoordinationDecisionInstaller { record ->
            val installed = f.decisions.install(record)
            assertEquals(true, inputs.planning.remove())
            installed
        }

        assertIs<AgentCoordinationDecisionResult.Rejected>(
            bridge(
                f,
                checker(
                    AgentCoordinationDeliberationPreflightResult.Ready(ready),
                    AgentCoordinationDeliberationPreflightResult.Ready(ready)
                ),
                installer
            ).install(request(inputs))
        )
        assertNull(f.decisions.inspect(DecisionId("decision-coord-race")))
    }

    @Test
    fun reasoning_replacement_after_decision_write_compensates_decision_and_preserves_replacement() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        var replacementGeneration: pro.liliya.core.reasoning.ReasoningGeneration? = null
        val installer = AgentCoordinationDecisionInstaller { record ->
            val installed = f.decisions.install(record)
            assertEquals(true, inputs.reasoning.remove())
            val replacement = ReasoningArtifact(
                id = inputs.reasoning.artifact.id,
                origin = ReasoningOrigin(ReasoningSourceId("replacement-test-source")),
                premises = listOf(ReasoningPremise(ReasoningPremiseId("replacement-premise"), "private replacement")),
                analysis = "private replacement analysis",
                conclusion = "private replacement conclusion",
                createdAt = Instant.parse("2026-08-30T08:03:00Z")
            )
            replacementGeneration = assertIs<ReasoningInstallResult.Installed>(
                f.reasoning.install(replacement)
            ).ownership.generation
            installed
        }

        assertIs<AgentCoordinationDecisionResult.Rejected>(
            bridge(
                f,
                checker(
                    AgentCoordinationDeliberationPreflightResult.Ready(ready),
                    AgentCoordinationDeliberationPreflightResult.Ready(ready)
                ),
                installer
            ).install(request(inputs))
        )
        assertNull(f.decisions.inspect(DecisionId("decision-coord-race")))
        assertEquals(
            assertNotNull(replacementGeneration),
            assertNotNull(f.reasoning.inspect(inputs.reasoning.artifact.id)).generation
        )
    }
}
