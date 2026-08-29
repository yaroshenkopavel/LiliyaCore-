package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.autonomy.AutonomyBudget
import pro.liliya.core.autonomy.AutonomyComposition
import pro.liliya.core.autonomy.AutonomyOrigin
import pro.liliya.core.autonomy.AutonomyPriority
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.autonomy.AutonomySourceId
import pro.liliya.core.autonomy.AutonomySourceReference
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class ControlledAgentInitiativeContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val agents: AgentComposition,
        val autonomy: AutonomyComposition,
        val bridge: ControlledAgentInitiative
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "agent-initiative-${sequence.incrementAndGet()}" }
        )
        val agents = AgentComposition(foundation)
        val autonomy = AutonomyComposition(foundation)
        return Fixture(
            logs = logs,
            agents = agents,
            autonomy = autonomy,
            bridge = ControlledAgentInitiative(foundation, agents, autonomy)
        )
    }

    private fun agent(
        role: String = "private agent role",
        purpose: String = "private agent purpose"
    ) = AgentRecord(
        id = AgentId("agent-1"),
        origin = AgentOrigin.Declared(
            AgentSourceId("declared-source"),
            AgentSourceReference("source-1")
        ),
        role = role,
        purpose = purpose,
        createdAt = Instant.parse("2026-08-29T17:15:00Z")
    )

    private fun installAgent(f: Fixture): AgentOwnership =
        assertIs<AgentInstallResult.Installed>(f.agents.install(agent())).ownership

    private fun request(generation: AgentGeneration) = AgentInitiativeRequest(
        agentId = AgentId("agent-1"),
        agentGeneration = generation,
        autonomyProposalId = AutonomyProposalId("autonomy-from-agent-1"),
        objective = "private initiative objective",
        triggerDescription = "private initiative trigger",
        priority = AutonomyPriority.NORMAL,
        budget = AutonomyBudget(2),
        createdAt = Instant.parse("2026-08-29T17:16:00Z")
    )

    @Test
    fun exact_live_agent_creates_one_bounded_autonomy_proposal_with_trusted_agent_provenance() {
        val f = fixture()
        val ownership = installAgent(f)

        val result = assertIs<AgentInitiativeResult.Created>(
            f.bridge.create(request(ownership.generation))
        )
        val proposal = result.ownership.proposal

        assertEquals(AutonomyProposalId("autonomy-from-agent-1"), proposal.id)
        assertEquals(AutonomyPriority.NORMAL, proposal.priority)
        assertEquals(AutonomyBudget(2), proposal.budget)
        assertEquals(
            AutonomyOrigin.Declared(
                sourceId = AutonomySourceId("agent"),
                sourceReference = AutonomySourceReference("agent:agent-1@${ownership.generation.value}")
            ),
            proposal.origin
        )
        assertEquals(1, f.autonomy.snapshot().size)
    }

    @Test
    fun stale_agent_generation_creates_zero_autonomy_writes() {
        val f = fixture()
        val stale = installAgent(f)
        assertTrue(stale.remove())
        installAgent(f)

        assertIs<AgentInitiativeResult.Rejected>(
            f.bridge.create(request(stale.generation))
        )
        assertTrue(f.autonomy.snapshot().isEmpty())
    }

    @Test
    fun removed_agent_creates_zero_autonomy_writes() {
        val f = fixture()
        val ownership = installAgent(f)
        assertTrue(ownership.remove())

        assertIs<AgentInitiativeResult.Rejected>(
            f.bridge.create(request(ownership.generation))
        )
        assertTrue(f.autonomy.snapshot().isEmpty())
    }

    @Test
    fun duplicate_autonomy_id_rejects_without_replacement() {
        val f = fixture()
        val ownership = installAgent(f)
        val first = assertIs<AgentInitiativeResult.Created>(
            f.bridge.create(request(ownership.generation))
        ).ownership

        assertIs<AgentInitiativeResult.Rejected>(
            f.bridge.create(request(ownership.generation))
        )

        assertEquals(first.generation, f.autonomy.inspect(first.proposal.id)?.generation)
        assertEquals(first.proposal, f.autonomy.find(first.proposal.id))
    }

    @Test
    fun agent_role_and_purpose_are_not_copied_into_initiative_or_observability() {
        val f = fixture()
        val secretRole = "never-copy-agent-role"
        val secretPurpose = "never-copy-agent-purpose"
        val installed = assertIs<AgentInstallResult.Installed>(
            f.agents.install(agent(role = secretRole, purpose = secretPurpose))
        ).ownership

        val created = assertIs<AgentInitiativeResult.Created>(
            f.bridge.create(request(installed.generation))
        ).ownership

        assertFalse(created.proposal.objective.contains(secretRole))
        assertFalse(created.proposal.objective.contains(secretPurpose))
        assertFalse(created.proposal.triggerDescription.contains(secretRole))
        assertFalse(created.proposal.triggerDescription.contains(secretPurpose))
        assertFalse(f.logs.snapshot().any { event ->
            event.message == secretRole || event.message == secretPurpose ||
                event.metadata.values.any { it == secretRole || it == secretPurpose }
        })
    }

    @Test
    fun bridge_adds_no_scheduler_authority_execution_or_tool_semantics_to_observability() {
        val f = fixture()
        val ownership = installAgent(f)
        assertIs<AgentInitiativeResult.Created>(f.bridge.create(request(ownership.generation)))

        val forbidden = setOf(
            "authority", "authorized", "permission", "capability", "execution", "execute",
            "executor", "scheduled", "scheduler", "spawn", "replicate", "tool"
        )
        assertFalse(f.logs.snapshot().any { event ->
            event.metadata.keys.any { key ->
                forbidden.any { token -> key.lowercase().contains(token) }
            }
        })
    }
}
