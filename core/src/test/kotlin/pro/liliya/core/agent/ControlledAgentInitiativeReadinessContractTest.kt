package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

class ControlledAgentInitiativeReadinessContractTest {
    @Test
    fun controlled_agent_data_api_contains_no_authority_execution_scheduler_or_self_spawn_semantics() {
        val forbidden = setOf(
            "authority", "authorize", "permission", "capability", "execution", "execute",
            "executor", "scheduler", "schedule", "spawn", "replicate", "tool", "delegate"
        )
        val dataTypes = listOf(
            AgentRecord::class.java,
            AgentInitiativeRequest::class.java,
            AgentInitiativeResult::class.java,
            AgentInitiativeAttemptResult::class.java
        )

        dataTypes.forEach { type ->
            val methodNames = type.methods.map { it.name.lowercase() }
            assertFalse(methodNames.any { name -> forbidden.any { token -> name.contains(token) } })
        }
    }

    @Test
    fun bridge_constructs_exact_agent_generation_provenance_without_copying_agent_private_payload() {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "agent-readiness-${sequence.incrementAndGet()}" }
        )
        val agents = AgentComposition(foundation)
        val autonomy = AutonomyComposition(foundation)
        val bridge = ControlledAgentInitiative(foundation, agents, autonomy)
        val secretRole = "private-role-secret"
        val secretPurpose = "private-purpose-secret"
        val agent = assertIs<AgentInstallResult.Installed>(
            agents.install(
                AgentRecord(
                    id = AgentId("agent-ready"),
                    origin = AgentOrigin.Declared(AgentSourceId("declared")),
                    role = secretRole,
                    purpose = secretPurpose,
                    createdAt = Instant.parse("2026-08-29T18:20:00Z")
                )
            )
        ).ownership

        val initiative = assertIs<AgentInitiativeResult.Created>(
            bridge.create(
                AgentInitiativeRequest(
                    agentId = agent.agent.id,
                    agentGeneration = agent.generation,
                    autonomyProposalId = AutonomyProposalId("agent-ready-autonomy"),
                    objective = "private initiative objective",
                    triggerDescription = "private initiative trigger",
                    priority = AutonomyPriority.NORMAL,
                    budget = AutonomyBudget(1),
                    createdAt = Instant.parse("2026-08-29T18:21:00Z")
                )
            )
        ).ownership

        assertEquals(
            AutonomyOrigin.Declared(
                sourceId = AutonomySourceId("agent"),
                sourceReference = AutonomySourceReference(
                    "agent:${agent.agent.id.value}@${agent.generation.value}"
                )
            ),
            initiative.proposal.origin
        )
        assertFalse(initiative.proposal.objective.contains(secretRole))
        assertFalse(initiative.proposal.objective.contains(secretPurpose))
        assertFalse(initiative.proposal.triggerDescription.contains(secretRole))
        assertFalse(initiative.proposal.triggerDescription.contains(secretPurpose))
        assertFalse(logs.snapshot().any { event ->
            event.message == secretRole || event.message == secretPurpose ||
                event.metadata.values.any { it == secretRole || it == secretPurpose }
        })
    }

    @Test
    fun controlled_agent_execution_exposes_only_one_execution_entrypoint_and_no_permission_api() {
        val publicNames = ControlledAgentExecution::class.java.methods
            .filter { it.declaringClass == ControlledAgentExecution::class.java }
            .map { it.name.lowercase() }

        assertEquals(listOf("execute"), publicNames.distinct().sorted())
        assertFalse(publicNames.any { it.contains("authorize") || it.contains("grant") || it.contains("permission") })
    }
}
