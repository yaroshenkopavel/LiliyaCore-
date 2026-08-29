package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.autonomy.AutonomyBudget
import pro.liliya.core.autonomy.AutonomyComposition
import pro.liliya.core.autonomy.AutonomyInstallResult
import pro.liliya.core.autonomy.AutonomyOrigin
import pro.liliya.core.autonomy.AutonomyPriority
import pro.liliya.core.autonomy.AutonomyProposal
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.autonomy.AutonomySourceId
import pro.liliya.core.autonomy.AutonomySourceReference
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
        val autonomy = AutonomyComposition(foundation)
        val autonomyGate = ControlledAutonomyDeliberationGate(foundation, autonomy)
        return Fixture(
            logs = logs,
            agents = agents,
            autonomy = autonomy,
            initiative = ControlledAgentInitiative(foundation, agents, autonomy),
            gate = ControlledAgentInitiativeGate(foundation, agents, autonomy, autonomyGate)
        )
    }

    private fun installAgent(f: Fixture): AgentOwnership =
        assertIs<AgentInstallResult.Installed>(
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
    fun exact_live_agent_and_trusted_provenance_claim_one_bounded_attempt() {
        val f = fixture()
        val agent = installAgent(f)
        val autonomy = createInitiative(f, agent)

        val claimed = assertIs<AgentInitiativeAttemptResult.Claimed>(
            f.gate.claimAttempt(
                agent.agent.id,
                agent.generation,
                autonomy.proposal.id,
                autonomy.generation
            )
        )

        assertEquals(1, claimed.attempt.evidence.attemptNumber)
        assertEquals(autonomy.generation, claimed.attempt.evidence.generation)
    }

    @Test
    fun removed_agent_causes_zero_attempt_claims() {
        val f = fixture()
        val agent = installAgent(f)
        val autonomy = createInitiative(f, agent)
        assertTrue(agent.remove())

        assertIs<AgentInitiativeAttemptResult.Rejected>(
            f.gate.claimAttempt(
                agent.agent.id,
                agent.generation,
                autonomy.proposal.id,
                autonomy.generation
            )
        )

        val attemptEvents = f.logs.snapshot().count {
            it.marker == "AUTONOMY_DELIBERATION_ATTEMPT_CLAIMED"
        }
        assertEquals(0, attemptEvents)
    }

    @Test
    fun stale_agent_replacement_causes_zero_attempt_claims() {
        val f = fixture()
        val stale = installAgent(f)
        val autonomy = createInitiative(f, stale)
        assertTrue(stale.remove())
        installAgent(f)

        assertIs<AgentInitiativeAttemptResult.Rejected>(
            f.gate.claimAttempt(
                stale.agent.id,
                stale.generation,
                autonomy.proposal.id,
                autonomy.generation
            )
        )
        assertEquals(
            0,
            f.logs.snapshot().count { it.marker == "AUTONOMY_DELIBERATION_ATTEMPT_CLAIMED" }
        )
    }

    @Test
    fun unrelated_or_forged_agent_origin_causes_zero_attempt_claims() {
        val f = fixture()
        val agent = installAgent(f)
        val forged = assertIs<AutonomyInstallResult.Installed>(
            f.autonomy.install(
                AutonomyProposal(
                    id = AutonomyProposalId("forged-autonomy"),
                    origin = AutonomyOrigin.Declared(
                        AutonomySourceId("agent"),
                        AutonomySourceReference("agent:someone-else@999")
                    ),
                    objective = "private forged objective",
                    triggerDescription = "private forged trigger",
                    priority = AutonomyPriority.NORMAL,
                    budget = AutonomyBudget(2),
                    createdAt = Instant.parse("2026-08-29T18:02:00Z")
                )
            )
        ).ownership

        assertIs<AgentInitiativeAttemptResult.Rejected>(
            f.gate.claimAttempt(
                agent.agent.id,
                agent.generation,
                forged.proposal.id,
                forged.generation
            )
        )
        assertEquals(
            0,
            f.logs.snapshot().count { it.marker == "AUTONOMY_DELIBERATION_ATTEMPT_CLAIMED" }
        )
    }

    @Test
    fun attempt_budget_remains_owned_by_frozen_autonomy_gate() {
        val f = fixture()
        val agent = installAgent(f)
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

        assertEquals(
            2,
            f.logs.snapshot().count { it.marker == "AUTONOMY_DELIBERATION_ATTEMPT_CLAIMED" }
        )
    }
}
