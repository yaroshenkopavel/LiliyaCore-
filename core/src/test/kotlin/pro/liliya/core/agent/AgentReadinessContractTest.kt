package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.autonomy.AutonomyGeneration
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class AgentReadinessContractTest {
    private fun fixture(): Pair<InMemoryLogWriter, AgentComposition> {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "agent-readiness-${sequence.incrementAndGet()}" }
        )
        return logs to AgentComposition(foundation)
    }

    private fun agent(
        id: String,
        createdAt: String,
        origin: AgentOrigin = AgentOrigin.Autonomy(
            proposalId = AutonomyProposalId("autonomy-exact"),
            generation = AutonomyGeneration(11)
        )
    ) = AgentRecord(
        id = AgentId(id),
        origin = origin,
        role = "private role for $id",
        purpose = "private purpose for $id",
        createdAt = Instant.parse(createdAt)
    )

    @Test
    fun snapshot_results_are_detached_list_views_of_store_state() {
        val (_, composition) = fixture()
        val a = agent("agent-a", "2026-08-29T17:00:00Z")
        val b = agent("agent-b", "2026-08-29T17:01:00Z")
        composition.install(a)

        val earlier = composition.snapshot()
        composition.install(b)

        assertEquals(listOf(a), earlier)
        assertEquals(listOf(a, b), composition.snapshot())
    }

    @Test
    fun exact_autonomy_origin_survives_as_data_only() {
        val (_, composition) = fixture()
        val origin = AgentOrigin.Autonomy(
            proposalId = AutonomyProposalId("autonomy-42"),
            generation = AutonomyGeneration(43)
        )
        val value = agent("agent-1", "2026-08-29T17:00:00Z", origin)

        composition.install(value)

        assertEquals(origin, composition.find(value.id)?.origin)
    }

    @Test
    fun agent_data_api_contains_no_authority_execution_scheduling_or_self_spawn_methods() {
        val forbidden = listOf(
            "approve", "authorize", "authority", "capability", "permission",
            "execute", "execution", "scheduler", "schedule", "spawn", "replicate",
            "delegate", "tool"
        )
        val types = listOf(
            AgentRecord::class.java,
            AgentOwnership::class.java,
            AgentSnapshot::class.java
        )

        types.forEach { type ->
            val names = type.declaredMethods.map { it.name.lowercase() }
            assertFalse(names.any { name -> forbidden.any(name::contains) })
        }
    }

    @Test
    fun lifecycle_metadata_contains_no_authority_execution_scheduling_or_self_spawn_semantics() {
        val (logs, composition) = fixture()
        val ownership = assertIs<AgentInstallResult.Installed>(
            composition.install(agent("agent-1", "2026-08-29T17:00:00Z"))
        ).ownership
        ownership.remove()

        val forbidden = setOf(
            "approved", "approval", "authority", "authorized", "capability", "permission",
            "execution", "execute", "executed", "executor", "scheduled", "scheduler",
            "spawn", "replicate", "delegation", "delegate", "tool", "trusted"
        )

        assertFalse(logs.snapshot().any { event ->
            event.metadata.keys.any { key ->
                forbidden.any { token -> key.lowercase().contains(token) }
            }
        })
        assertTrue(logs.snapshot().any { it.marker == "AGENT_REGISTERED" })
    }
}
