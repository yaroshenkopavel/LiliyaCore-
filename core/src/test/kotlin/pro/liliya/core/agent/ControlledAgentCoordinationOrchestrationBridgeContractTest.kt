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
import pro.liliya.core.orchestration.OrchestrationComposition
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

class ControlledAgentCoordinationOrchestrationBridgeContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val planning: PlanningComposition,
        val reasoning: ReasoningComposition,
        val decisions: DecisionComposition,
        val orchestration: OrchestrationComposition
    )

    private data class Inputs(
        val planning: PlanningOwnership,
        val reasoning: ReasoningOwnership,
        val decision: DecisionOwnership
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "coord-orch-${sequence.incrementAndGet()}" }
        )
        return Fixture(
            logs,
            foundation,
            PlanningComposition(foundation),
            ReasoningComposition(foundation),
            DecisionComposition(foundation),
            OrchestrationComposition(foundation)
        )
    }

    private fun evidence(bindingGeneration: Long = 4L) = AgentCoordinationDeliberationReadyEvidence(
        coordination = ExactAgentCoordinationReference(
            AgentCoordinationId("coord-orch"),
            AgentCoordinationGeneration(3)
        ),
        attemptBindingGeneration = AgentCoordinationAttemptBindingGeneration(bindingGeneration),
        participant = ExactAgentReference(AgentId("agent-a"), AgentGeneration(7)),
        requestId = AutonomyDeliberationRequestId("delib-orch"),
        requestGeneration = AutonomyDeliberationGeneration(5),
        attempt = AutonomyAttemptReference(
            AutonomyProposalId("autonomy-orch"),
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
                    id = PlanningProposalId("planning-coord-orch"),
                    origin = PlanningOrigin(
                        PlanningSourceId("agent-coordination-deliberation"),
                        PlanningSourceReference(planningReference(ready))
                    ),
                    goal = "private planning goal",
                    steps = listOf(PlanningStep(PlanningStepId("step-1"), "private planning step")),
                    createdAt = Instant.parse("2026-08-30T09:00:00Z")
                )
            )
        ).ownership
        val reasoning = assertIs<ReasoningInstallResult.Installed>(
            f.reasoning.install(
                ReasoningArtifact(
                    id = ReasoningArtifactId("reasoning-coord-orch"),
                    origin = ReasoningOrigin(
                        ReasoningSourceId("agent-coordination-planning"),
                        ReasoningSourceReference(
                            planningReference(ready) + ";planning=${planning.proposal.id.value}@${planning.generation.value}"
                        )
                    ),
                    premises = listOf(ReasoningPremise(ReasoningPremiseId("premise-1"), "private premise")),
                    analysis = "private analysis",
                    conclusion = "private conclusion",
                    createdAt = Instant.parse("2026-08-30T09:01:00Z")
                )
            )
        ).ownership
        val decision = assertIs<DecisionInstallResult.Installed>(
            f.decisions.install(
                DecisionRecord(
                    id = DecisionId("decision-coord-orch"),
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
                    createdAt = Instant.parse("2026-08-30T09:02:00Z")
                )
            )
        ).ownership
        return Inputs(planning, reasoning, decision)
    }

    private fun request(inputs: Inputs, secret: String = "private orchestration description") =
        AgentCoordinationOrchestrationRequest(
            deliberationRequestId = AutonomyDeliberationRequestId("delib-orch"),
            deliberationGeneration = AutonomyDeliberationGeneration(5),
            planningProposalId = inputs.planning.proposal.id,
            planningGeneration = inputs.planning.generation,
            reasoningArtifactId = inputs.reasoning.artifact.id,
            reasoningGeneration = inputs.reasoning.generation,
            decisionId = inputs.decision.decision.id,
            decisionGeneration = inputs.decision.generation,
            orchestrationIntentId = OrchestrationIntentId("orch-coord"),
            description = secret,
            createdAt = Instant.parse("2026-08-30T09:03:00Z")
        )

    private fun checker(vararg results: AgentCoordinationDeliberationPreflightResult):
        AgentCoordinationDeliberationPreflightChecker {
        val index = AtomicInteger(0)
        return AgentCoordinationDeliberationPreflightChecker { _, _ ->
            results[index.getAndIncrement().coerceAtMost(results.lastIndex)]
        }
    }

    private fun bridge(
        f: Fixture,
        preflight: AgentCoordinationDeliberationPreflightChecker,
        installer: AgentCoordinationOrchestrationInstaller = AgentCoordinationOrchestrationInstaller(f.orchestration::install)
    ) = ControlledAgentCoordinationOrchestrationBridge(
        foundation = f.foundation,
        preflight = preflight,
        planning = f.planning,
        reasoning = f.reasoning,
        decisions = f.decisions,
        orchestration = f.orchestration,
        installer = installer,
        testOnly = Unit
    )

    @Test
    fun stable_exact_chain_installs_one_orchestration_intent() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        val installed = assertIs<AgentCoordinationOrchestrationResult.Installed>(
            bridge(
                f,
                checker(
                    AgentCoordinationDeliberationPreflightResult.Ready(ready),
                    AgentCoordinationDeliberationPreflightResult.Ready(ready)
                )
            ).install(request(inputs))
        )
        assertEquals(1, f.orchestration.snapshot().size)
        assertEquals(inputs.decision.decision.id, installed.orchestration.intent.decision.decisionId)
        assertEquals(inputs.decision.generation, installed.orchestration.intent.decision.generation)
        assertEquals(inputs.decision.decision.selectedOptionId, installed.orchestration.intent.decision.selectedOptionId)
    }

    @Test
    fun stale_decision_generation_rejects_before_write() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        val stale = AgentCoordinationOrchestrationRequest(
            deliberationRequestId = AutonomyDeliberationRequestId("delib-orch"),
            deliberationGeneration = AutonomyDeliberationGeneration(5),
            planningProposalId = inputs.planning.proposal.id,
            planningGeneration = inputs.planning.generation,
            reasoningArtifactId = inputs.reasoning.artifact.id,
            reasoningGeneration = inputs.reasoning.generation,
            decisionId = inputs.decision.decision.id,
            decisionGeneration = pro.liliya.core.decision.DecisionGeneration(inputs.decision.generation.value + 1),
            orchestrationIntentId = OrchestrationIntentId("orch-coord"),
            description = "private description",
            createdAt = Instant.parse("2026-08-30T09:03:00Z")
        )
        assertIs<AgentCoordinationOrchestrationResult.Rejected>(
            bridge(f, checker(AgentCoordinationDeliberationPreflightResult.Ready(ready))).install(stale)
        )
        assertEquals(0, f.orchestration.snapshot().size)
    }

    @Test
    fun initial_preflight_rejection_writes_nothing() {
        val f = fixture()
        val inputs = installInputs(f)
        assertIs<AgentCoordinationOrchestrationResult.Rejected>(
            bridge(
                f,
                checker(AgentCoordinationDeliberationPreflightResult.Rejected("not ready"))
            ).install(request(inputs))
        )
        assertEquals(0, f.orchestration.snapshot().size)
    }

    @Test
    fun governance_change_after_write_compensates_exact_intent() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        assertIs<AgentCoordinationOrchestrationResult.Rejected>(
            bridge(
                f,
                checker(
                    AgentCoordinationDeliberationPreflightResult.Ready(ready),
                    AgentCoordinationDeliberationPreflightResult.Rejected("participant stopped")
                )
            ).install(request(inputs))
        )
        assertNull(f.orchestration.inspect(OrchestrationIntentId("orch-coord")))
    }

    @Test
    fun decision_removed_after_write_compensates_intent() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        val installer = AgentCoordinationOrchestrationInstaller { intent ->
            val installed = f.orchestration.install(intent)
            assertEquals(true, inputs.decision.remove())
            installed
        }
        assertIs<AgentCoordinationOrchestrationResult.Rejected>(
            bridge(
                f,
                checker(
                    AgentCoordinationDeliberationPreflightResult.Ready(ready),
                    AgentCoordinationDeliberationPreflightResult.Ready(ready)
                ),
                installer
            ).install(request(inputs))
        )
        assertNull(f.orchestration.inspect(OrchestrationIntentId("orch-coord")))
    }

    @Test
    fun compensation_failure_is_explicit_and_critical() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        val installer = AgentCoordinationOrchestrationInstaller { intent ->
            val real = assertIs<OrchestrationInstallResult.Installed>(f.orchestration.install(intent)).ownership
            OrchestrationInstallResult.Installed(
                object : OrchestrationOwnership {
                    override val intent: OrchestrationIntent = real.intent
                    override val generation = real.generation
                    override fun remove(): Boolean = false
                }
            )
        }
        assertIs<AgentCoordinationOrchestrationResult.Failed>(
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
            1,
            f.logs.snapshot().count { it.marker == "AGENT_COORDINATION_ORCHESTRATION_COMPENSATION_FAILED" }
        )
    }

    @Test
    fun stale_compensation_owner_preserves_newer_orchestration_generation() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        var replacementGeneration: pro.liliya.core.orchestration.OrchestrationGeneration? = null
        val installer = AgentCoordinationOrchestrationInstaller { intent ->
            val original = assertIs<OrchestrationInstallResult.Installed>(f.orchestration.install(intent)).ownership
            assertEquals(true, original.remove())
            val replacement = OrchestrationIntent(
                id = intent.id,
                decision = intent.decision,
                description = "private replacement",
                createdAt = Instant.parse("2026-08-30T09:04:00Z")
            )
            replacementGeneration = assertIs<OrchestrationInstallResult.Installed>(
                f.orchestration.install(replacement)
            ).ownership.generation
            OrchestrationInstallResult.Installed(original)
        }
        assertIs<AgentCoordinationOrchestrationResult.Rejected>(
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
            assertNotNull(f.orchestration.inspect(OrchestrationIntentId("orch-coord"))).generation
        )
    }

    @Test
    fun private_description_is_not_observable_and_api_has_no_authority_or_execution_semantics() {
        val f = fixture()
        val ready = evidence()
        val inputs = installInputs(f, ready)
        val secret = "never-log-coordinated-orchestration-secret"
        assertIs<AgentCoordinationOrchestrationResult.Installed>(
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
        val forbidden = setOf("authority", "authorize", "permission", "capability", "execution", "execute", "executor", "scheduler", "schedule")
        val names = listOf(
            AgentCoordinationOrchestrationRequest::class.java,
            AgentCoordinationOrchestrationResult::class.java
        ).flatMap { type -> type.methods.map { it.name.lowercase() } }
        assertFalse(names.any { name -> forbidden.any { token -> name.contains(token) } })
    }
}
