package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.autonomy.AutonomyBudget
import pro.liliya.core.autonomy.AutonomyComposition
import pro.liliya.core.autonomy.AutonomyDeliberationComposition
import pro.liliya.core.autonomy.AutonomyDeliberationInstallResult
import pro.liliya.core.autonomy.AutonomyDeliberationRequest
import pro.liliya.core.autonomy.AutonomyDeliberationRequestId
import pro.liliya.core.autonomy.AutonomyAttemptReference
import pro.liliya.core.autonomy.AutonomyPriority
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.autonomy.ControlledAutonomyDeliberationGate
import pro.liliya.core.autonomy.ControlledAutonomyExecutionRequest
import pro.liliya.core.autonomy.ControlledAutonomyExecutionResult
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.execution.ExecutionActionId
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.orchestration.OrchestrationGeneration
import pro.liliya.core.orchestration.OrchestrationIntentId
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningGeneration

class ControlledAgentExecutionContractTest {
    private data class Fixture(
        val agents: AgentComposition,
        val autonomy: AutonomyComposition,
        val initiative: ControlledAgentInitiative,
        val autonomyGate: ControlledAutonomyDeliberationGate,
        val deliberation: AutonomyDeliberationComposition,
        val delegateCalls: AtomicInteger,
        val guard: ControlledAgentExecution
    )

    private data class Prepared(
        val agent: AgentOwnership,
        val autonomy: pro.liliya.core.autonomy.AutonomyOwnership,
        val deliberation: pro.liliya.core.autonomy.AutonomyDeliberationOwnership
    )

    private fun fixture(delegateResult: ControlledAutonomyExecutionResult = ControlledAutonomyExecutionResult.Succeeded): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "agent-execution-${sequence.incrementAndGet()}" }
        )
        val agents = AgentComposition(foundation)
        val autonomy = AutonomyComposition(foundation)
        val initiative = ControlledAgentInitiative(foundation, agents, autonomy)
        val autonomyGate = ControlledAutonomyDeliberationGate(foundation, autonomy)
        val deliberation = AutonomyDeliberationComposition(foundation)
        val delegateCalls = AtomicInteger(0)
        val guard = ControlledAgentExecution(
            agents = agents,
            autonomy = autonomy,
            deliberation = deliberation,
            executor = AgentAutonomyExecutionDelegate {
                delegateCalls.incrementAndGet()
                delegateResult
            }
        )
        return Fixture(agents, autonomy, initiative, autonomyGate, deliberation, delegateCalls, guard)
    }

    private fun prepare(f: Fixture): Prepared {
        val agent = assertIs<AgentInstallResult.Installed>(
            f.agents.install(
                AgentRecord(
                    id = AgentId("agent-1"),
                    origin = AgentOrigin.Declared(AgentSourceId("declared-source")),
                    role = "private role",
                    purpose = "private purpose",
                    createdAt = Instant.parse("2026-08-29T18:10:00Z")
                )
            )
        ).ownership
        val autonomy = assertIs<AgentInitiativeResult.Created>(
            f.initiative.create(
                AgentInitiativeRequest(
                    agentId = agent.agent.id,
                    agentGeneration = agent.generation,
                    autonomyProposalId = AutonomyProposalId("autonomy-agent-1"),
                    objective = "private objective",
                    triggerDescription = "private trigger",
                    priority = AutonomyPriority.NORMAL,
                    budget = AutonomyBudget(2),
                    createdAt = Instant.parse("2026-08-29T18:11:00Z")
                )
            )
        ).ownership
        val claim = assertIs<pro.liliya.core.autonomy.AutonomyDeliberationAttemptResult.Claimed>(
            f.autonomyGate.claimAttempt(autonomy.proposal.id, autonomy.generation)
        )
        val deliberation = assertIs<AutonomyDeliberationInstallResult.Installed>(
            f.deliberation.install(
                AutonomyDeliberationRequest(
                    id = AutonomyDeliberationRequestId("request-1"),
                    autonomy = AutonomyAttemptReference(
                        proposalId = autonomy.proposal.id,
                        proposalGeneration = autonomy.generation,
                        attemptNumber = claim.evidence.attemptNumber
                    ),
                    objective = "private deliberation objective",
                    createdAt = Instant.parse("2026-08-29T18:12:00Z")
                )
            )
        ).ownership
        return Prepared(agent, autonomy, deliberation)
    }

    private fun request(p: Prepared) = ControlledAutonomyExecutionRequest(
        deliberationRequestId = p.deliberation.request.id,
        deliberationGeneration = p.deliberation.generation,
        planningProposalId = PlanningProposalId("planning-1"),
        planningGeneration = PlanningGeneration(1),
        reasoningArtifactId = ReasoningArtifactId("reasoning-1"),
        reasoningGeneration = ReasoningGeneration(1),
        decisionId = DecisionId("decision-1"),
        decisionGeneration = DecisionGeneration(1),
        orchestrationIntentId = OrchestrationIntentId("orchestration-1"),
        orchestrationGeneration = OrchestrationGeneration(1),
        principal = AuthorityPrincipal("liliya"),
        actionId = ExecutionActionId("device.open.settings")
    )

    @Test
    fun exact_live_agent_provenance_delegates_once_to_frozen_autonomy_execution() {
        val f = fixture()
        val prepared = prepare(f)

        assertIs<ControlledAgentExecutionResult.Succeeded>(f.guard.execute(request(prepared)))
        assertEquals(1, f.delegateCalls.get())
    }

    @Test
    fun agent_removed_after_deliberation_creation_causes_zero_downstream_execution_calls() {
        val f = fixture()
        val prepared = prepare(f)
        assertTrue(prepared.agent.remove())

        assertIs<ControlledAgentExecutionResult.Rejected>(f.guard.execute(request(prepared)))
        assertEquals(0, f.delegateCalls.get())
    }

    @Test
    fun stale_agent_replacement_after_deliberation_creation_causes_zero_downstream_execution_calls() {
        val f = fixture()
        val prepared = prepare(f)
        assertTrue(prepared.agent.remove())
        assertIs<AgentInstallResult.Installed>(
            f.agents.install(
                AgentRecord(
                    id = AgentId("agent-1"),
                    origin = AgentOrigin.Declared(AgentSourceId("replacement")),
                    role = "replacement role",
                    purpose = "replacement purpose",
                    createdAt = Instant.parse("2026-08-29T18:13:00Z")
                )
            )
        )

        assertIs<ControlledAgentExecutionResult.Rejected>(f.guard.execute(request(prepared)))
        assertEquals(0, f.delegateCalls.get())
    }

    @Test
    fun stale_deliberation_generation_causes_zero_downstream_execution_calls() {
        val f = fixture()
        val prepared = prepare(f)
        assertTrue(prepared.deliberation.remove())

        assertIs<ControlledAgentExecutionResult.Rejected>(f.guard.execute(request(prepared)))
        assertEquals(0, f.delegateCalls.get())
    }

    @Test
    fun frozen_autonomy_rejection_is_propagated_without_turning_agent_into_authority() {
        val f = fixture(ControlledAutonomyExecutionResult.Rejected("authority denied"))
        val prepared = prepare(f)

        val result = assertIs<ControlledAgentExecutionResult.Rejected>(f.guard.execute(request(prepared)))
        assertEquals("authority denied", result.reason)
        assertEquals(1, f.delegateCalls.get())
    }
}
