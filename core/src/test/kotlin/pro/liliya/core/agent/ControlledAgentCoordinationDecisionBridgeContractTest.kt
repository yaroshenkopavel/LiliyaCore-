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

class ControlledAgentCoordinationDecisionBridgeContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val planning: PlanningComposition,
        val reasoning: ReasoningComposition,
        val decisions: DecisionComposition
    )

    private data class CognitiveInputs(
        val planning: PlanningOwnership,
        val reasoning: ReasoningOwnership
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "coord-decision-${sequence.incrementAndGet()}" }
        )
        return Fixture(
            logs = logs,
            foundation = foundation,
            planning = PlanningComposition(foundation),
            reasoning = ReasoningComposition(foundation),
            decisions = DecisionComposition(foundation)
        )
    }

    private fun evidence(
        coordinationGeneration: Long = 3L,
        bindingGeneration: Long = 4L
    ) = AgentCoordinationDeliberationReadyEvidence(
        coordination = ExactAgentCoordinationReference(
            AgentCoordinationId("coord-decision"),
            AgentCoordinationGeneration(coordinationGeneration)
        ),
        attemptBindingGeneration = AgentCoordinationAttemptBindingGeneration(bindingGeneration),
        participant = ExactAgentReference(AgentId("agent-a"), AgentGeneration(7)),
        requestId = AutonomyDeliberationRequestId("delib-decision"),
        requestGeneration = AutonomyDeliberationGeneration(5),
        attempt = AutonomyAttemptReference(
            AutonomyProposalId("autonomy-decision"),
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

    private fun reasoningReference(
        evidence: AgentCoordinationDeliberationReadyEvidence,
        planning: PlanningOwnership
    ): String = planningReference(evidence) +
        ";planning=${planning.proposal.id.value}@${planning.generation.value}"

    private fun installInputs(
        f: Fixture,
        ready: AgentCoordinationDeliberationReadyEvidence = evidence(),
        reasoningReady: AgentCoordinationDeliberationReadyEvidence = ready
    ): CognitiveInputs {
        val planning = assertIs<PlanningInstallResult.Installed>(
            f.planning.install(
                PlanningProposal(
                    id = PlanningProposalId("planning-coord-decision"),
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
        val reasoning = assertIs<ReasoningInstallResult.Installed>(
            f.reasoning.install(
                ReasoningArtifact(
                    id = ReasoningArtifactId("reasoning-coord-decision"),
                    origin = ReasoningOrigin(
                        sourceId = ReasoningSourceId("agent-coordination-planning"),
                        sourceReference = ReasoningSourceReference(reasoningReference(reasoningReady, planning))
                    ),
                    premises = listOf(
                        ReasoningPremise(ReasoningPremiseId("premise-1"), "private premise")
                    ),
                    analysis = "private analysis",
                    conclusion = "private conclusion",
                    createdAt = Instant.parse("2026-08-30T08:01:00Z")
                )
            )
        ).ownership
        return CognitiveInputs(planning, reasoning)
    }

    private fun request(
        inputs: CognitiveInputs,
        secret: String = "private coordinated decision content"
    ) = AgentCoordinationDecisionRequest(
        deliberationRequestId = AutonomyDeliberationRequestId("delib-decision"),
        deliberationGeneration = AutonomyDeliberationGeneration(5),
        planningProposalId = inputs.planning.proposal.id,
        planningGeneration = inputs.planning.generation,
        reasoningArtifactId = inputs.reasoning.artifact.id,
        reasoningGeneration = inputs.reasoning.generation,
        decisionId = DecisionId("decision-coord"),
        options = listOf(
            DecisionOption(DecisionOptionId("option-a"), secret),
            DecisionOption(DecisionOptionId("option-b"), "private alternative")
        ),
        selectedOptionId = DecisionOptionId("option-a"),
        rationale = secret,
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
    fun stable_exact_reasoning_and_readiness_install_one_decision() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        val installed = assertIs<AgentCoordinationDecisionResult.Installed>(
            bridge(
                f,
                checker(
                    AgentCoordinationDeliberationPreflightResult.Ready(ready),
                    AgentCoordinationDeliberationPreflightResult.Ready(ready)
                )
            ).install(request(inputs))
        )

        assertEquals(1, f.decisions.snapshot().size)
        assertEquals(
            listOf(
                DecisionInputReference.Planning(inputs.planning.proposal.id, inputs.planning.generation),
                DecisionInputReference.Reasoning(inputs.reasoning.artifact.id, inputs.reasoning.generation)
            ),
            installed.decision.decision.inputs
        )
    }

    @Test
    fun stale_reasoning_generation_rejects_before_decision_write() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        val stale = AgentCoordinationDecisionRequest(
            deliberationRequestId = AutonomyDeliberationRequestId("delib-decision"),
            deliberationGeneration = AutonomyDeliberationGeneration(5),
            planningProposalId = inputs.planning.proposal.id,
            planningGeneration = inputs.planning.generation,
            reasoningArtifactId = inputs.reasoning.artifact.id,
            reasoningGeneration = pro.liliya.core.reasoning.ReasoningGeneration(inputs.reasoning.generation.value + 1),
            decisionId = DecisionId("decision-coord"),
            options = listOf(DecisionOption(DecisionOptionId("option-a"), "private option")),
            selectedOptionId = DecisionOptionId("option-a"),
            rationale = "private rationale",
            createdAt = Instant.parse("2026-08-30T08:02:00Z")
        )

        assertIs<AgentCoordinationDecisionResult.Rejected>(
            bridge(f, checker(AgentCoordinationDeliberationPreflightResult.Ready(ready))).install(stale)
        )
        assertEquals(0, f.decisions.snapshot().size)
    }

    @Test
    fun mismatched_reasoning_provenance_rejects_before_decision_write() {
        val f = fixture()
        val ready = evidence()
        val other = evidence(bindingGeneration = 99)
        val inputs = installInputs(f, ready, reasoningReady = other)

        assertIs<AgentCoordinationDecisionResult.Rejected>(
            bridge(f, checker(AgentCoordinationDeliberationPreflightResult.Ready(ready))).install(request(inputs))
        )
        assertEquals(0, f.decisions.snapshot().size)
    }

    @Test
    fun governance_change_after_decision_write_rolls_back_exact_created_generation() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        assertIs<AgentCoordinationDecisionResult.Rejected>(
            bridge(
                f,
                checker(
                    AgentCoordinationDeliberationPreflightResult.Ready(ready),
                    AgentCoordinationDeliberationPreflightResult.Rejected("participant stopped")
                )
            ).install(request(inputs))
        )
        assertNull(f.decisions.inspect(DecisionId("decision-coord")))
    }

    @Test
    fun changed_exact_readiness_after_write_rolls_back_decision() {
        val f = fixture()
        val initial = evidence()
        val changed = evidence(bindingGeneration = 5)
        val inputs = installInputs(f, initial)
        assertIs<AgentCoordinationDecisionResult.Rejected>(
            bridge(
                f,
                checker(
                    AgentCoordinationDeliberationPreflightResult.Ready(initial),
                    AgentCoordinationDeliberationPreflightResult.Ready(changed)
                )
            ).install(request(inputs))
        )
        assertEquals(0, f.decisions.snapshot().size)
    }

    @Test
    fun reasoning_removed_after_decision_write_compensates_decision() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        val installer = AgentCoordinationDecisionInstaller { record ->
            val installed = f.decisions.install(record)
            assertEquals(true, inputs.reasoning.remove())
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
        assertNull(f.decisions.inspect(DecisionId("decision-coord")))
    }

    @Test
    fun compensation_failure_is_explicit_failed_and_critical_observable() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        val installer = AgentCoordinationDecisionInstaller { record ->
            val real = assertIs<DecisionInstallResult.Installed>(f.decisions.install(record)).ownership
            DecisionInstallResult.Installed(
                object : DecisionOwnership {
                    override val decision: DecisionRecord = real.decision
                    override val generation = real.generation
                    override fun remove(): Boolean = false
                }
            )
        }

        assertIs<AgentCoordinationDecisionResult.Failed>(
            bridge(
                f,
                checker(
                    AgentCoordinationDeliberationPreflightResult.Ready(ready),
                    AgentCoordinationDeliberationPreflightResult.Rejected("simulated race")
                ),
                installer
            ).install(request(inputs))
        )
        assertNotNull(f.decisions.inspect(DecisionId("decision-coord")))
        assertEquals(
            1,
            f.logs.snapshot().count { it.marker == "AGENT_COORDINATION_DECISION_COMPENSATION_FAILED" }
        )
    }

    @Test
    fun stale_compensation_ownership_does_not_remove_newer_decision_generation() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        var replacementGeneration: pro.liliya.core.decision.DecisionGeneration? = null
        val installer = AgentCoordinationDecisionInstaller { record ->
            val original = assertIs<DecisionInstallResult.Installed>(f.decisions.install(record)).ownership
            assertEquals(true, original.remove())
            val replacement = DecisionRecord(
                id = record.id,
                inputs = record.inputs,
                options = listOf(DecisionOption(DecisionOptionId("replacement"), "private replacement")),
                selectedOptionId = DecisionOptionId("replacement"),
                rationale = "private replacement rationale",
                createdAt = Instant.parse("2026-08-30T08:03:00Z")
            )
            replacementGeneration = assertIs<DecisionInstallResult.Installed>(
                f.decisions.install(replacement)
            ).ownership.generation
            DecisionInstallResult.Installed(original)
        }

        assertIs<AgentCoordinationDecisionResult.Rejected>(
            bridge(
                f,
                checker(
                    AgentCoordinationDeliberationPreflightResult.Ready(ready),
                    AgentCoordinationDeliberationPreflightResult.Rejected("simulated race")
                ),
                installer
            ).install(request(inputs))
        )
        assertEquals(
            assertNotNull(replacementGeneration),
            assertNotNull(f.decisions.inspect(DecisionId("decision-coord"))).generation
        )
    }

    @Test
    fun private_decision_content_does_not_enter_bridge_observability_and_api_has_no_execution_semantics() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        val secret = "never-log-coordination-decision-secret"
        assertIs<AgentCoordinationDecisionResult.Installed>(
            bridge(
                f,
                checker(
                    AgentCoordinationDeliberationPreflightResult.Ready(ready),
                    AgentCoordinationDeliberationPreflightResult.Ready(ready)
                )
            ).install(request(inputs, secret))
        )
        assertFalse(f.logs.snapshot().any { event ->
            event.message == secret || event.metadata.values.any { it == secret }
        })

        val forbidden = setOf(
            "authority", "authorize", "permission", "capability", "execution", "execute",
            "executor", "scheduler", "schedule", "orchestration"
        )
        val names = listOf(
            AgentCoordinationDecisionRequest::class.java,
            AgentCoordinationDecisionResult::class.java
        ).flatMap { type -> type.methods.map { it.name.lowercase() } }
        assertFalse(names.any { name -> forbidden.any { token -> name.contains(token) } })
    }
}
