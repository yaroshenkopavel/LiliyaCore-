package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.autonomy.AutonomyAttemptReference
import pro.liliya.core.autonomy.AutonomyBudget
import pro.liliya.core.autonomy.AutonomyComposition
import pro.liliya.core.autonomy.AutonomyDeliberationComposition
import pro.liliya.core.autonomy.AutonomyDeliberationInstallResult
import pro.liliya.core.autonomy.AutonomyDeliberationOwnership
import pro.liliya.core.autonomy.AutonomyDeliberationRequest
import pro.liliya.core.autonomy.AutonomyDeliberationRequestId
import pro.liliya.core.autonomy.AutonomyPriority
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.autonomy.ControlledAutonomyDeliberationGate
import pro.liliya.core.autonomy.ControlledAutonomyExecutionRequest
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

class ControlledAgentDelegatedExecutionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val agents: AgentComposition,
        val lifecycle: ControlledAgentLifecycle,
        val delegations: AgentDelegationComposition,
        val autonomy: AutonomyComposition,
        val bindings: AgentDelegatedWorkBindingComposition,
        val deliberation: AutonomyDeliberationComposition,
        val autonomyGate: ControlledAutonomyDeliberationGate,
        val delegatedInitiative: ControlledAgentDelegatedInitiative,
        val delegatedAttempt: ControlledAgentDelegatedInitiativeGate,
        val preflight: ControlledAgentDelegationPreflight,
        val delegateCalls: AtomicInteger,
        val guard: ControlledAgentDelegatedExecution
    )

    private data class Prepared(
        val parent: AgentOwnership,
        val parentLifecycle: AgentLifecycleOwnership,
        val child: AgentOwnership,
        val childLifecycle: AgentLifecycleOwnership,
        val delegation: AgentDelegationOwnership,
        val delegated: AgentDelegatedInitiativeOwnership,
        val deliberation: AutonomyDeliberationOwnership
    )

    private fun fixture(
        delegateResult: ControlledAgentExecutionResult = ControlledAgentExecutionResult.Succeeded
    ): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "delegated-execution-${sequence.incrementAndGet()}" }
        )
        val agents = AgentComposition(foundation)
        val lifecycle = ControlledAgentLifecycle(foundation, agents)
        val delegations = AgentDelegationComposition(foundation)
        val autonomy = AutonomyComposition(foundation)
        val bindings = AgentDelegatedWorkBindingComposition(foundation)
        val deliberation = AutonomyDeliberationComposition(foundation)
        val autonomyGate = ControlledAutonomyDeliberationGate(foundation, autonomy)
        val initiative = ControlledAgentInitiative(foundation, agents, lifecycle, autonomy)
        val agentGate = ControlledAgentInitiativeGate(
            foundation,
            agents,
            lifecycle,
            autonomy,
            autonomyGate
        )
        val preflight = ControlledAgentDelegationPreflight(
            foundation,
            delegations,
            agents,
            lifecycle
        )
        val delegatedInitiative = ControlledAgentDelegatedInitiative(
            foundation,
            preflight,
            initiative,
            bindings
        )
        val delegatedAttempt = ControlledAgentDelegatedInitiativeGate(
            foundation,
            bindings,
            preflight,
            agentGate,
            autonomyGate
        )
        val delegateCalls = AtomicInteger(0)
        val guard = ControlledAgentDelegatedExecution(
            foundation = foundation,
            deliberation = deliberation,
            bindings = bindings,
            preflight = AgentDelegationPreflightChecker(preflight::check),
            delegate = AgentControlledExecutionDelegate {
                delegateCalls.incrementAndGet()
                delegateResult
            },
            testOnly = Unit
        )
        return Fixture(
            logs,
            foundation,
            agents,
            lifecycle,
            delegations,
            autonomy,
            bindings,
            deliberation,
            autonomyGate,
            delegatedInitiative,
            delegatedAttempt,
            preflight,
            delegateCalls,
            guard
        )
    }

    private fun installAgent(f: Fixture, id: String): AgentOwnership =
        assertIs<AgentInstallResult.Installed>(
            f.agents.install(
                AgentRecord(
                    id = AgentId(id),
                    origin = AgentOrigin.Declared(AgentSourceId("declared-$id")),
                    role = "private $id role",
                    purpose = "private $id purpose",
                    createdAt = Instant.parse("2026-08-29T22:30:00Z")
                )
            )
        ).ownership

    private fun activate(f: Fixture, agent: AgentOwnership): AgentLifecycleOwnership =
        assertIs<AgentLifecycleActivationResult.Activated>(
            f.lifecycle.activate(agent.agent.id, agent.generation)
        ).ownership

    private fun prepare(f: Fixture): Prepared {
        val parent = installAgent(f, "parent")
        val child = installAgent(f, "child")
        val parentLifecycle = activate(f, parent)
        val childLifecycle = activate(f, child)
        val delegation = assertIs<AgentDelegationInstallResult.Installed>(
            f.delegations.install(
                AgentDelegationRecord(
                    id = AgentDelegationId("delegation-execution"),
                    parent = ExactAgentReference(parent.agent.id, parent.generation),
                    child = ExactAgentReference(child.agent.id, child.generation),
                    purpose = "private delegated execution purpose",
                    createdAt = Instant.parse("2026-08-29T22:31:00Z")
                )
            )
        ).ownership
        val delegated = assertIs<AgentDelegatedInitiativeResult.Created>(
            f.delegatedInitiative.create(
                AgentDelegatedInitiativeRequest(
                    delegationId = delegation.delegation.id,
                    delegationGeneration = delegation.generation,
                    autonomyProposalId = AutonomyProposalId("delegated-execution-autonomy"),
                    objective = "private delegated objective",
                    triggerDescription = "private delegated trigger",
                    priority = AutonomyPriority.NORMAL,
                    budget = AutonomyBudget(2),
                    createdAt = Instant.parse("2026-08-29T22:32:00Z")
                )
            )
        ).ownership
        val autonomy = delegated.receipt.autonomy
        val claimed = assertIs<AgentDelegatedInitiativeAttemptResult.Claimed>(
            f.delegatedAttempt.claimAttempt(autonomy.proposalId, autonomy.generation)
        )
        val deliberation = assertIs<AutonomyDeliberationInstallResult.Installed>(
            f.deliberation.install(
                AutonomyDeliberationRequest(
                    id = AutonomyDeliberationRequestId("delegated-execution-request"),
                    autonomy = AutonomyAttemptReference(
                        proposalId = autonomy.proposalId,
                        proposalGeneration = autonomy.generation,
                        attemptNumber = claimed.attempt.attempt.evidence.attemptNumber
                    ),
                    objective = "private delegated deliberation objective",
                    createdAt = Instant.parse("2026-08-29T22:33:00Z")
                )
            )
        ).ownership
        return Prepared(
            parent,
            parentLifecycle,
            child,
            childLifecycle,
            delegation,
            delegated,
            deliberation
        )
    }

    private fun request(p: Prepared) = ControlledAutonomyExecutionRequest(
        deliberationRequestId = p.deliberation.request.id,
        deliberationGeneration = p.deliberation.generation,
        planningProposalId = PlanningProposalId("planning-delegated"),
        planningGeneration = PlanningGeneration(1),
        reasoningArtifactId = ReasoningArtifactId("reasoning-delegated"),
        reasoningGeneration = ReasoningGeneration(1),
        decisionId = DecisionId("decision-delegated"),
        decisionGeneration = DecisionGeneration(1),
        orchestrationIntentId = OrchestrationIntentId("orchestration-delegated"),
        orchestrationGeneration = OrchestrationGeneration(1),
        principal = AuthorityPrincipal("liliya"),
        actionId = ExecutionActionId("device.open.settings")
    )

    @Test
    fun exact_live_delegated_chain_reaches_controlled_agent_delegate_once() {
        val f = fixture()
        val p = prepare(f)

        assertIs<ControlledAgentDelegatedExecutionResult.Succeeded>(
            f.guard.execute(request(p))
        )
        assertEquals(1, f.delegateCalls.get())
    }

    @Test
    fun parent_cancelled_after_deliberation_creation_causes_zero_delegate_calls() {
        val f = fixture()
        val p = prepare(f)
        assertTrue(p.parentLifecycle.cancel())

        assertIs<ControlledAgentDelegatedExecutionResult.Rejected>(
            f.guard.execute(request(p))
        )
        assertEquals(0, f.delegateCalls.get())
    }

    @Test
    fun child_stopped_after_deliberation_creation_causes_zero_delegate_calls() {
        val f = fixture()
        val p = prepare(f)
        assertTrue(p.childLifecycle.stop())

        assertIs<ControlledAgentDelegatedExecutionResult.Rejected>(
            f.guard.execute(request(p))
        )
        assertEquals(0, f.delegateCalls.get())
    }

    @Test
    fun delegation_removed_after_deliberation_creation_causes_zero_delegate_calls() {
        val f = fixture()
        val p = prepare(f)
        assertTrue(p.delegation.remove())

        assertIs<ControlledAgentDelegatedExecutionResult.Rejected>(
            f.guard.execute(request(p))
        )
        assertEquals(0, f.delegateCalls.get())
    }

    @Test
    fun binding_or_autonomy_removed_after_deliberation_creation_causes_zero_delegate_calls() {
        val f = fixture()
        val p = prepare(f)
        assertTrue(p.delegated.remove())

        assertIs<ControlledAgentDelegatedExecutionResult.Rejected>(
            f.guard.execute(request(p))
        )
        assertEquals(0, f.delegateCalls.get())
    }

    @Test
    fun stale_deliberation_generation_causes_zero_delegate_calls() {
        val f = fixture()
        val p = prepare(f)
        assertTrue(p.deliberation.remove())

        assertIs<ControlledAgentDelegatedExecutionResult.Rejected>(
            f.guard.execute(request(p))
        )
        assertEquals(0, f.delegateCalls.get())
    }

    @Test
    fun downstream_controlled_agent_rejection_is_propagated_without_granting_delegation_power() {
        val f = fixture(ControlledAgentExecutionResult.Rejected("authority denied downstream"))
        val p = prepare(f)

        val result = assertIs<ControlledAgentDelegatedExecutionResult.Rejected>(
            f.guard.execute(request(p))
        )
        assertEquals("authority denied downstream", result.reason)
        assertEquals(1, f.delegateCalls.get())
    }

    @Test
    fun delegation_private_purpose_never_enters_execution_observability() {
        val f = fixture()
        val p = prepare(f)
        val secret = p.delegation.delegation.purpose

        assertIs<ControlledAgentDelegatedExecutionResult.Succeeded>(
            f.guard.execute(request(p))
        )
        assertFalse(f.logs.snapshot().any { event ->
            event.message == secret || event.metadata.values.any { it == secret }
        })
    }
}
