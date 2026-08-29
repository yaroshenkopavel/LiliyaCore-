package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class AgentDelegationStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val store: AgentDelegationStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        var sequence = 0
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "agent-delegation-${++sequence}" }
        )
        return Fixture(logs, foundation, AgentDelegationStore(foundation.observability))
    }

    private fun delegation(
        id: String = "delegation-1",
        parentId: String = "agent-parent",
        parentGeneration: Long = 3,
        childId: String = "agent-child",
        childGeneration: Long = 7,
        purpose: String = "private delegation purpose",
        createdAt: String = "2026-08-29T19:45:00Z"
    ) = AgentDelegationRecord(
        id = AgentDelegationId(id),
        parent = ExactAgentReference(AgentId(parentId), AgentGeneration(parentGeneration)),
        child = ExactAgentReference(AgentId(childId), AgentGeneration(childGeneration)),
        purpose = purpose,
        createdAt = Instant.parse(createdAt)
    )

    private fun context(f: Fixture, operation: String = "registerAgentDelegation") =
        f.foundation.rootContext(operation = operation, component = "AgentDelegation")

    @Test
    fun exact_registration_read_and_remove_ownership() {
        val f = fixture()
        val value = delegation()
        val registered = assertIs<AgentDelegationRegistrationResult.Registered>(
            f.store.register(value, context(f))
        ).registration

        assertEquals(value, registered.delegation)
        assertTrue(registered.generation.value > 0)
        assertEquals(value, f.store.find(value.id))
        assertEquals(registered.generation, f.store.inspect(value.id)?.generation)
        assertTrue(f.store.contains(value.id))

        assertTrue(registered.remove(context(f, "removeAgentDelegation")))
        assertFalse(f.store.contains(value.id))
        assertNull(f.store.find(value.id))
        assertFalse(registered.remove(context(f, "removeAgentDelegationAgain")))
    }

    @Test
    fun duplicate_id_rejects_without_replacement() {
        val f = fixture()
        val first = delegation(purpose = "first private purpose")
        val second = delegation(purpose = "second private purpose")

        val firstRegistration = assertIs<AgentDelegationRegistrationResult.Registered>(
            f.store.register(first, context(f))
        ).registration
        assertIs<AgentDelegationRegistrationResult.Rejected>(
            f.store.register(second, context(f))
        )

        assertEquals(first, f.store.find(first.id))
        assertEquals(firstRegistration.generation, f.store.inspect(first.id)?.generation)
    }

    @Test
    fun stale_registration_cannot_remove_replacement() {
        val f = fixture()
        val first = assertIs<AgentDelegationRegistrationResult.Registered>(
            f.store.register(delegation(), context(f))
        ).registration
        assertTrue(first.remove(context(f, "removeFirstDelegation")))

        val replacement = assertIs<AgentDelegationRegistrationResult.Registered>(
            f.store.register(delegation(purpose = "replacement private purpose"), context(f))
        ).registration

        assertNotEquals(first.generation, replacement.generation)
        assertFalse(first.remove(context(f, "staleRemoveDelegation")))
        assertEquals(replacement.delegation, f.store.find(replacement.delegation.id))
    }

    @Test
    fun self_delegation_is_rejected_by_structural_model() {
        assertFailsWith<IllegalArgumentException> {
            delegation(parentId = "same-agent", childId = "same-agent")
        }
    }

    @Test
    fun purpose_is_redacted_from_rendering_and_observability() {
        val f = fixture()
        val secret = "never-log-delegation-purpose"
        val value = delegation(purpose = secret)
        val registration = assertIs<AgentDelegationRegistrationResult.Registered>(
            f.store.register(value, context(f))
        ).registration
        registration.remove(context(f, "removeAgentDelegation"))

        assertFalse(value.toString().contains(secret))
        assertFalse(f.logs.snapshot().any { event ->
            event.message == secret || event.metadata.values.any { it == secret }
        })
    }

    @Test
    fun snapshots_are_deterministic_detached_views() {
        val f = fixture()
        val z = delegation(id = "delegation-z", createdAt = "2026-08-29T19:45:00Z")
        val a = delegation(id = "delegation-a", createdAt = "2026-08-29T19:45:00Z")
        val later = delegation(id = "delegation-b", createdAt = "2026-08-29T19:46:00Z")

        f.store.register(z, context(f))
        f.store.register(a, context(f))
        val earlierSnapshot = f.store.snapshot()
        f.store.register(later, context(f))

        assertEquals(listOf(a, z), earlierSnapshot)
        assertEquals(listOf(a, z, later), f.store.snapshot())
    }

    @Test
    fun concurrent_same_id_registration_has_exactly_one_winner() {
        val f = fixture()
        val results = ConcurrentLinkedQueue<AgentDelegationRegistrationResult>()
        val workers = List(24) { index ->
            thread(start = false) {
                results += f.store.register(
                    delegation(purpose = "private purpose $index"),
                    context(f, "concurrentRegisterAgentDelegation")
                )
            }
        }

        workers.forEach { it.start() }
        workers.forEach { it.join() }

        assertEquals(1, results.count { it is AgentDelegationRegistrationResult.Registered })
        assertEquals(23, results.count { it is AgentDelegationRegistrationResult.Rejected })
        assertTrue(f.store.contains(AgentDelegationId("delegation-1")))
    }
}
