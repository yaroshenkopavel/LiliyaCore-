package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.autonomy.AutonomyAttemptReference
import pro.liliya.core.autonomy.AutonomyDeliberationGeneration
import pro.liliya.core.autonomy.AutonomyDeliberationRequestId
import pro.liliya.core.autonomy.AutonomyGeneration
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.decision.DecisionInputReference
import pro.liliya.core.decision.DecisionInstallResult
import pro.liliya.core.decision.DecisionOption
import pro.liliya.core.decision.DecisionOptionId
import pro.liliya.core.decision.DecisionOwnership
import pro.liliya.core.decision.DecisionRecord
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.execution.ExecutionActionId
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.orchestration.ControlledOrchestrationExecutionResult
import pro.liliya.core.orchestration.OrchestrationComposition
import pro.liliya.core.orchestration.OrchestrationDecisionReference
import pro.liliya.core.orchestration.OrchestrationExecutionPreflightRequest
import pro.liliya.core.orchestration.OrchestrationInstallResult
import pro.liliya.core.orchestration.OrchestrationIntent
import pro.liliya.core.orchestration.OrchestrationIntentId
import pro.liliya.core.orchestration.OrchestrationOwnership
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

class ControlledAgentCoordinationExecutionContractTest {
    private data class Fixture(
        val foundation: FoundationComposition,
        val planning: PlanningComposition,
        val reasoning: ReasoningComposition,
        val decisions: DecisionComposition,
        val orchestration: OrchestrationComposition
    )

    private data class Inputs(
        val planning: PlanningOwnership,
        val reasoning: ReasoningOwnership,
        val decision: DecisionOwnership,
        val orchestration: OrchestrationOwnership
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "coord-exec-${sequence.incrementAndGet()}" }
        )
        return Fixture(
            foundation,
            PlanningComposition(foundation),
            ReasoningComposition(foundation),
            DecisionComposition(foundation),
            OrchestrationComposition(foundation)
        )
    }

    private fun evidence(bindingGeneration: Long = 4L) = AgentCoordinationDeliberationReadyEvidence(
        coordination = ExactAgentCoordinationReference(
            AgentCoordinationId("coord-exec"),
            AgentCoordinationGeneration(3)
        ),
        attemptBindingGeneration = AgentCoordinationAttemptBindingGeneration(bindingGeneration),
        participant = ExactAgentReference(AgentId("agent-a"), AgentGeneration(7)),
        requestId = AutonomyDeliberationRequestId("delib-exec"),
        requestGeneration = AutonomyDeliberationGeneration(5),
        attempt = AutonomyAttemptReference(
            AutonomyProposalId("autonomy-exec"),
            AutonomyGeneration(6),
            2
        )
    )

    private fun planningReference(e: AgentCoordinationDeliberationReadyEvidence): String =
        "coordination=${e.coordination.id.value}@${e.coordination.generation.value};" +
            "attemptBinding=${e.attemptBindingGeneration.value};" +
            "participant=${e.participant.id.value}@${e.participant.generation.value};" +
            "request=${e.requestId.value}@${e.requestGeneration.value};" +
            "proposal=${e.attempt.proposalId.value}@${e.attempt.proposalGeneration.value};" +
            "attempt=${e.attempt.attemptNumber}"

    private fun installInputs(f: Fixture, ready: AgentCoordinationDeliberationReadyEvidence = evidence()): Inputs {
        val planning = assertIs<PlanningInstallResult.Installed>(
            f.planning.install(
                PlanningProposal(
                    id = PlanningProposalId("planning-coord-exec"),
                    origin = PlanningOrigin(
                        PlanningSourceId("agent-coordination-deliberation"),
                        PlanningSourceReference(planningReference(ready))
                    ),
                    goal = "private planning goal",
                    steps = listOf(PlanningStep(PlanningStepId("step-1"), "private planning step")),
                    createdAt = Instant.parse("2026-08-30T10:00:00Z")
                )
            )
        ).ownership
        val reasoning = assertIs<ReasoningInstallResult.Installed>(
            f.reasoning.install(
                ReasoningArtifact(
                    id = ReasoningArtifactId("reasoning-coord-exec"),
                    origin = ReasoningOrigin(
                        ReasoningSourceId("agent-coordination-planning"),
                        ReasoningSourceReference(
                            planningReference(ready) + ";planning=${planning.proposal.id.value}@${planning.generation.value}"
                        )
                    ),
                    premises = listOf(ReasoningPremise(ReasoningPremiseId("premise-1"), "private premise")),
                    analysis = "private analysis",
                    conclusion = "private conclusion",
                    createdAt = Instant.parse("2026-08-30T10:01:00Z")
                )
            )
        ).ownership
        val decision = assertIs<DecisionInstallResult.Installed>(
            f.decisions.install(
                DecisionRecord(
                    id = DecisionId("decision-coord-exec"),
                    inputs = listOf(
                        DecisionInputReference.Planning(planning.proposal.id, planning.generation),
                        DecisionInputReference.Reasoning(reasoning.artifact.id, reasoning.generation)
                    ),
                    options = listOf(
                        DecisionOption(DecisionOptionId("option-a"), "private option a"),
                        DecisionOption(DecisionOptionId("option-b"), "private option b")
                    ),
                    selectedOptionId = DecisionOptionId("option-a"),
                    rationale = "private rationale",
                    createdAt = Instant.parse("2026-08-30T10:02:00Z")
                )
            )
        ).ownership
        val orchestration = assertIs<OrchestrationInstallResult.Installed>(
            f.orchestration.install(
                OrchestrationIntent(
                    id = OrchestrationIntentId("orch-coord-exec"),
                    decision = OrchestrationDecisionReference(
                        decisionId = decision.decision.id,
                        generation = decision.generation,
                        selectedOptionId = decision.decision.selectedOptionId
                    ),
                    description = "private orchestration description",
                    createdAt = Instant.parse("2026-08-30T10:03:00Z")
                )
            )
        ).ownership
        return Inputs(planning, reasoning, decision, orchestration)
    }

    private fun request(inputs: Inputs) = AgentCoordinationExecutionRequest(
        deliberationRequestId = AutonomyDeliberationRequestId("delib-exec"),
        deliberationGeneration = AutonomyDeliberationGeneration(5),
        planningProposalId = inputs.planning.proposal.id,
        planningGeneration = inputs.planning.generation,
        reasoningArtifactId = inputs.reasoning.artifact.id,
        reasoningGeneration = inputs.reasoning.generation,
        decisionId = inputs.decision.decision.id,
        decisionGeneration = inputs.decision.generation,
        orchestrationIntentId = inputs.orchestration.intent.id,
        orchestrationGeneration = inputs.orchestration.generation,
        principal = AuthorityPrincipal("agent-principal"),
        actionId = ExecutionActionId("send-message")
    )

    private fun checker(vararg results: AgentCoordinationDeliberationPreflightResult):
        AgentCoordinationDeliberationPreflightChecker {
        val index = AtomicInteger(0)
        return AgentCoordinationDeliberationPreflightChecker { _, _ ->
            results[index.getAndIncrement().coerceAtMost(results.lastIndex)]
        }
    }

    private fun guard(
        f: Fixture,
        preflight: AgentCoordinationDeliberationPreflightChecker,
        delegate: AgentCoordinationExecutionDelegate
    ) = ControlledAgentCoordinationExecution(
        foundation = f.foundation,
        preflight = preflight,
        planning = f.planning,
        reasoning = f.reasoning,
        decisions = f.decisions,
        orchestration = f.orchestration,
        delegate = delegate,
        testOnly = Unit
    )

    @Test
    fun stable_exact_chain_delegates_once_to_frozen_orchestration_boundary() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        var delegated: OrchestrationExecutionPreflightRequest? = null
        val calls = AtomicInteger(0)

        val result = guard(
            f,
            checker(
                AgentCoordinationDeliberationPreflightResult.Ready(ready),
                AgentCoordinationDeliberationPreflightResult.Ready(ready)
            ),
            AgentCoordinationExecutionDelegate { forwarded ->
                calls.incrementAndGet()
                delegated = forwarded
                ControlledOrchestrationExecutionResult.Succeeded
            }
        ).execute(request(inputs))

        assertIs<AgentCoordinationExecutionResult.Succeeded>(result)
        assertEquals(1, calls.get())
        val forwarded = delegated ?: error("expected orchestration delegation")
        assertEquals(inputs.orchestration.intent.id, forwarded.intentId)
        assertEquals(inputs.orchestration.generation, forwarded.generation)
        assertEquals(AuthorityPrincipal("agent-principal"), forwarded.principal)
        assertEquals(ExecutionActionId("send-message"), forwarded.actionId)
    }

    @Test
    fun initial_preflight_rejection_never_reaches_execution_delegate() {
        val f = fixture()
        val inputs = installInputs(f)
        val calls = AtomicInteger(0)

        assertIs<AgentCoordinationExecutionResult.Rejected>(
            guard(
                f,
                checker(AgentCoordinationDeliberationPreflightResult.Rejected("not ready")),
                AgentCoordinationExecutionDelegate {
                    calls.incrementAndGet()
                    ControlledOrchestrationExecutionResult.Succeeded
                }
            ).execute(request(inputs))
        )
        assertEquals(0, calls.get())
    }

    @Test
    fun changed_coordination_readiness_before_delegate_is_rejected() {
        val f = fixture()
        val initial = evidence(4)
        val changed = evidence(5)
        val inputs = installInputs(f, initial)
        val calls = AtomicInteger(0)

        assertIs<AgentCoordinationExecutionResult.Rejected>(
            guard(
                f,
                checker(
                    AgentCoordinationDeliberationPreflightResult.Ready(initial),
                    AgentCoordinationDeliberationPreflightResult.Ready(changed)
                ),
                AgentCoordinationExecutionDelegate {
                    calls.incrementAndGet()
                    ControlledOrchestrationExecutionResult.Succeeded
                }
            ).execute(request(inputs))
        )
        assertEquals(0, calls.get())
    }

    @Test
    fun stale_orchestration_generation_is_rejected_before_delegate() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        val calls = AtomicInteger(0)
        val stale = request(inputs).copy(
            orchestrationGeneration = pro.liliya.core.orchestration.OrchestrationGeneration(
                inputs.orchestration.generation.value + 1
            )
        )

        assertIs<AgentCoordinationExecutionResult.Rejected>(
            guard(
                f,
                checker(AgentCoordinationDeliberationPreflightResult.Ready(ready)),
                AgentCoordinationExecutionDelegate {
                    calls.incrementAndGet()
                    ControlledOrchestrationExecutionResult.Succeeded
                }
            ).execute(stale)
        )
        assertEquals(0, calls.get())
    }

    @Test
    fun downstream_failure_is_forwarded_without_hidden_retry() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        val calls = AtomicInteger(0)
        val boom = IllegalStateException("executor failed")

        val result = assertIs<AgentCoordinationExecutionResult.Failed>(
            guard(
                f,
                checker(
                    AgentCoordinationDeliberationPreflightResult.Ready(ready),
                    AgentCoordinationDeliberationPreflightResult.Ready(ready)
                ),
                AgentCoordinationExecutionDelegate {
                    calls.incrementAndGet()
                    ControlledOrchestrationExecutionResult.Failed("execution failed", boom)
                }
            ).execute(request(inputs))
        )

        assertEquals(1, calls.get())
        assertEquals("execution failed", result.reason)
        assertEquals(boom, result.throwable)
        assertNull(f.orchestration.inspect(OrchestrationIntentId("not-created-by-guard")))
    }
}
