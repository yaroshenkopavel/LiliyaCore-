package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.autonomy.AutonomyBudget
import pro.liliya.core.autonomy.AutonomyComposition
import pro.liliya.core.autonomy.AutonomyPriority
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.autonomy.ControlledAutonomyDeliberationGate
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class ControlledAgentInitiativeGateContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val agents: AgentComposition,
        val lifecycle: ControlledAgentLifecycle,
        val autonomy: AutonomyComposition,
        val initiative: ControlledAgentInitiative,
        val gate: ControlledAgentInitiativeGate
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "agent-attempt-${sequence.incrementAndGet()}" }
        )
        val agents = AgentComposition(foundation)
        val lifecycle = ControlledAgentLifecycle(foundation, agents)
        val autonomy = AutonomyComposition(foundation)
        val autonomyGate = ControlledAutonomyDeliberationGate(foundation, autonomy)
        return Fixture(
            logs = logs,
            agents = agents,
            lifecycle = lifecycle,
            autonomy = autonomy,
            initiative = ControlledAgentInitiative(foundation, agents, lifecycle, autonomy),
            gate = ControlledAgentInitiativeGate(foundation, agents, lifecycle, autonomy, autonomyGate)
        )
    }

    private fun installActiveAgent(f: Fixture): Pair<AgentOwnership, AgentLifecycleOwnership> {
        val agent = assertIs<AgentInstallResult.Installed>(
            f.agents.install(
                AgentRecord(
                    id = AgentId("agent-1"),
                    origin = AgentOrigin.Declared(AgentSourceId("declared-source")),
                    role = "private role",
                    purpose = "private purpose",
                    createdAt = Instant.parse("2026-08-29T18:00:00Z")
                )
            )
        ).ownership
        val lifecycle = assertIs<AgentLifecycleActivationResult.Activated>(
            f.lifecycle.activate(agent.agent.id, agent.generation)
        ).ownership
        return agent to lifecycle
    }

    private fun createInitiative(f: Fixture, agent: AgentOwnership) =
        assertIs<AgentInitiativeResult.Created>(
            f.initiative.create(
                AgentInitiativeRequest(
                    agentId = agent.agent.id,
                    agentGeneration = agent.generation,
                    autonomyProposalId = AutonomyProposalId("autonomy-agent-1"),
                    objective = "private objective",
                    triggerDescription = "private trigger",
                    priority = AutonomyPriority.NORMAL,
                    budget = AutonomyBudget(2),
                    createdAt = Instant.parse("2026-08-29T18:01:00Z")
                )
            )
        ).ownership

    @Test
    fun exact_live_active_agent_claims_one_bounded_attempt() {
        val f = fixture()
        val (agent, _) = installActiveAgent(f)
        val autonomy = createInitiative(f, agent)

        val claimed = assertIs<AgentInitiativeAttemptResult.Claimed>(
            f.gate.claimAttempt(agent.agent.id, agent.generation, autonomy.proposal.id, autonomy.generation)
        )

        assertEquals(1, claimed.attempt.evidence.attemptNumber)
    }

    @Test
    fun cancelled_or_stopped_lifecycle_after_initiative_creation_causes_zero_attempt_claims() {
        listOf("cancelled", "stopped").forEach { mode ->
            val f = fixture()
            val (agent, lifecycle) = installActiveAgent(f)
            val autonomy = createInitiative(f, agent)
            if (mode == "cancelled") assertTrue(lifecycle.cancel()) else assertTrue(lifecycle.stop())

            assertIs<AgentInitiativeAttemptResult.Rejected>(
                f.gate.claimAttempt(agent.agent.id, agent.generation, autonomy.proposal.id, autonomy.generation)
            )
            assertEquals(
                0,
                f.logs.snapshot().count { it.marker == "AUTONOMY_DELIBERATION_ATTEMPT_CLAIMED" }
            )
        }
    }

    @Test
    fun removed_or_stale_agent_causes_zero_attempt_claims() {
        val f = fixture()
        val (stale, _) = installActiveAgent(f)
        val autonomy = createInitiative(f, stale)
        assertTrue(stale.remove())

        assertIs<AgentInitiativeAttemptResult.Rejected>(
            f.gate.claimAttempt(stale.agent.id, stale.generation, autonomy.proposal.id, autonomy.generation)
        )
        assertEquals(0, f.logs.snapshot().count { it.marker == "AUTONOMY_DELIBERATION_ATTEMPT_CLAIMED" })
    }

    @Test
    fun attempt_budget_remains_owned_by_frozen_autonomy_gate() {
        val f = fixture()
        val (agent, _) = installActiveAgent(f)
        val autonomy = createInitiative(f, agent)

        assertIs<AgentInitiativeAttemptResult.Claimed>(
            f.gate.claimAttempt(agent.agent.id, agent.generation, autonomy.proposal.id, autonomy.generation)
        )
        assertIs<AgentInitiativeAttemptResult.Claimed>(
            f.gate.claimAttempt(agent.agent.id, agent.generation, autonomy.proposal.id, autonomy.generation)
        )
        assertIs<AgentInitiativeAttemptResult.Rejected>(
            f.gate.claimAttempt(agent.agent.id, agent.generation, autonomy.proposal.id, autonomy.generation)
        )

        assertEquals(2, f.logs.snapshot().count { it.marker == "AUTONOMY_DELIBERATION_ATTEMPT_CLAIMED" })
    }
}
