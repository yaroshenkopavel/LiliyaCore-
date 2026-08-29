package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class AgentDelegationCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: AgentDelegationComposition
    )

    private fun fixture(prefix: String = "delegation-composition"): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, AgentDelegationComposition(foundation))
    }

    private fun delegation(
        id: String = "delegation-1",
        purpose: String = "private purpose"
    ) = AgentDelegationRecord(
        id = AgentDelegationId(id),
        parent = ExactAgentReference(AgentId("parent-agent"), AgentGeneration(3)),
        child = ExactAgentReference(AgentId("child-agent"), AgentGeneration(7)),
        purpose = purpose,
        createdAt = Instant.parse("2026-08-29T20:00:00Z")
    )

    @Test
    fun install_exposes_exact_controlled_ownership_and_one_shot_remove() {
        val f = fixture()
        val value = delegation()
        val ownership = assertIs<AgentDelegationInstallResult.Installed>(
            f.composition.install(value)
        ).ownership

        assertEquals(value, ownership.delegation)
        assertEquals(ownership.generation, f.composition.inspect(value.id)?.generation)
        assertTrue(f.composition.contains(value.id))
        assertTrue(ownership.remove())
        assertFalse(f.composition.contains(value.id))
        assertFalse(ownership.remove())
    }

    @Test
    fun duplicate_install_rejects_without_replacement() {
        val f = fixture()
        val first = assertIs<AgentDelegationInstallResult.Installed>(
            f.composition.install(delegation(purpose = "first private purpose"))
        ).ownership

        assertIs<AgentDelegationInstallResult.Rejected>(
            f.composition.install(delegation(purpose = "second private purpose"))
        )

        assertEquals(first.delegation, f.composition.find(first.delegation.id))
        assertEquals(first.generation, f.composition.inspect(first.delegation.id)?.generation)
    }

    @Test
    fun stale_ownership_cannot_remove_replacement() {
        val f = fixture()
        val stale = assertIs<AgentDelegationInstallResult.Installed>(
            f.composition.install(delegation())
        ).ownership
        assertTrue(stale.remove())

        val replacement = assertIs<AgentDelegationInstallResult.Installed>(
            f.composition.install(delegation(purpose = "replacement private purpose"))
        ).ownership

        assertNotEquals(stale.generation, replacement.generation)
        assertFalse(stale.remove())
        assertEquals(replacement.delegation, f.composition.find(replacement.delegation.id))
    }

    @Test
    fun same_id_is_independent_across_compositions() {
        val first = fixture("delegation-first")
        val second = fixture("delegation-second")
        val value = delegation()

        val firstOwnership = assertIs<AgentDelegationInstallResult.Installed>(
            first.composition.install(value)
        ).ownership
        val secondOwnership = assertIs<AgentDelegationInstallResult.Installed>(
            second.composition.install(value)
        ).ownership

        assertTrue(firstOwnership.remove())
        assertFalse(first.composition.contains(value.id))
        assertTrue(second.composition.contains(value.id))
        assertEquals(secondOwnership.delegation, second.composition.find(value.id))
    }

    @Test
    fun purpose_is_absent_from_lifecycle_observability() {
        val f = fixture()
        val secret = "never-log-delegation-composition-purpose"
        val ownership = assertIs<AgentDelegationInstallResult.Installed>(
            f.composition.install(delegation(purpose = secret))
        ).ownership
        ownership.remove()

        assertFalse(f.logs.snapshot().any { event ->
            event.message == secret || event.metadata.values.any { it == secret }
        })
    }

    @Test
    fun remove_context_is_child_of_install_context() {
        val f = fixture()
        val ownership = assertIs<AgentDelegationInstallResult.Installed>(
            f.composition.install(delegation())
        ).ownership
        assertTrue(ownership.remove())

        val installEvent = f.logs.snapshot().first { it.marker == "AGENT_DELEGATION_REGISTERED" }
        val removeEvent = f.logs.snapshot().first { it.marker == "AGENT_DELEGATION_REMOVED" }

        assertEquals(installEvent.context.correlationId, removeEvent.context.parentCorrelationId)
        assertNotEquals(installEvent.context.correlationId, removeEvent.context.correlationId)
    }
}
