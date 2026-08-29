package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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

class AgentCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: AgentComposition
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "agent-composition-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, AgentComposition(foundation))
    }

    private fun agent(
        id: String = "agent-1",
        role: String = "private role",
        purpose: String = "private purpose",
        origin: AgentOrigin = AgentOrigin.Autonomy(
            proposalId = AutonomyProposalId("autonomy-1"),
            generation = AutonomyGeneration(7)
        ),
        createdAt: String = "2026-08-29T17:00:00Z"
    ) = AgentRecord(
        id = AgentId(id),
        origin = origin,
        role = role,
        purpose = purpose,
        createdAt = Instant.parse(createdAt)
    )

    @Test
    fun install_exposes_exact_controlled_ownership() {
        val f = fixture()
        val value = agent()

        val installed = assertIs<AgentInstallResult.Installed>(f.composition.install(value))

        assertEquals(value, installed.ownership.agent)
        assertTrue(installed.ownership.generation.value > 0)
        assertEquals(value, f.composition.find(value.id))
        assertTrue(installed.ownership.remove())
        assertFalse(f.composition.contains(value.id))
        assertFalse(installed.ownership.remove())
    }

    @Test
    fun duplicate_install_rejects_without_replacement() {
        val f = fixture()
        val first = agent(role = "first secret role")
        val second = agent(role = "second secret role")

        val firstInstalled = assertIs<AgentInstallResult.Installed>(f.composition.install(first))
        assertIs<AgentInstallResult.Rejected>(f.composition.install(second))

        assertEquals(first, f.composition.find(first.id))
        assertEquals(firstInstalled.ownership.generation, f.composition.inspect(first.id)?.generation)
    }

    @Test
    fun stale_ownership_cannot_remove_replacement() {
        val f = fixture()
        val first = assertIs<AgentInstallResult.Installed>(f.composition.install(agent())).ownership
        assertTrue(first.remove())

        val replacement = assertIs<AgentInstallResult.Installed>(
            f.composition.install(agent(role = "replacement role"))
        ).ownership

        assertNotEquals(first.generation, replacement.generation)
        assertFalse(first.remove())
        assertTrue(f.composition.contains(replacement.agent.id))
        assertEquals(replacement.generation, f.composition.inspect(replacement.agent.id)?.generation)
    }

    @Test
    fun same_id_is_independent_across_compositions() {
        val first = fixture().composition
        val second = fixture().composition
        val value = agent()

        val firstOwnership = assertIs<AgentInstallResult.Installed>(first.install(value)).ownership
        val secondOwnership = assertIs<AgentInstallResult.Installed>(second.install(value)).ownership

        assertNotEquals(firstOwnership, secondOwnership)
        assertTrue(firstOwnership.remove())
        assertFalse(first.contains(value.id))
        assertTrue(second.contains(value.id))
    }

    @Test
    fun role_and_purpose_are_absent_from_lifecycle_metadata() {
        val f = fixture()
        val secretRole = "never-log-agent-role"
        val secretPurpose = "never-log-agent-purpose"
        val ownership = assertIs<AgentInstallResult.Installed>(
            f.composition.install(agent(role = secretRole, purpose = secretPurpose))
        ).ownership
        ownership.remove()

        assertFalse(f.logs.snapshot().any { event ->
            event.message == secretRole || event.message == secretPurpose ||
                event.metadata.values.any { it == secretRole || it == secretPurpose }
        })
    }

    @Test
    fun remove_context_is_child_of_install_context() {
        val f = fixture()
        val ownership = assertIs<AgentInstallResult.Installed>(f.composition.install(agent())).ownership
        assertTrue(ownership.remove())

        val registered = f.logs.snapshot().first { it.marker == "AGENT_REGISTERED" }
        val removed = f.logs.snapshot().first { it.marker == "AGENT_REMOVED" }

        assertEquals("installAgent", registered.context.operation)
        assertEquals("removeAgent", removed.context.operation)
        assertEquals(registered.context.correlationId, removed.context.parentCorrelationId)
        assertNotEquals(registered.context.correlationId, removed.context.correlationId)
    }
}
