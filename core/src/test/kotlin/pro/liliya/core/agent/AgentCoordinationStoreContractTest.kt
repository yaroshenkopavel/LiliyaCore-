package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
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

class AgentCoordinationStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val store: AgentCoordinationStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "agent-coordination-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, foundation, AgentCoordinationStore(foundation.observability))
    }

    private fun record(
        id: String = "coordination-1",
        purpose: String = "private coordination purpose",
        createdAt: String = "2026-08-29T21:40:00Z",
        participants: List<ExactAgentReference> = listOf(
            ExactAgentReference(AgentId("agent-a"), AgentGeneration(3)),
            ExactAgentReference(AgentId("agent-b"), AgentGeneration(5))
        )
    ) = AgentCoordinationRecord(
        id = AgentCoordinationId(id),
        participants = participants,
        purpose = purpose,
        createdAt = Instant.parse(createdAt)
    )

    private fun context(f: Fixture, operation: String = "registerAgentCoordination") =
        f.foundation.rootContext(operation = operation, component = "Agent")

    @Test
    fun record_rejects_invalid_participant_structures() {
        assertFailsWith<IllegalArgumentException> {
            record(participants = listOf(ExactAgentReference(AgentId("only"), AgentGeneration(1))))
        }
        assertFailsWith<IllegalArgumentException> {
            val same = ExactAgentReference(AgentId("same"), AgentGeneration(1))
            record(participants = listOf(same, same))
        }
        assertFailsWith<IllegalArgumentException> {
            record(
                participants = listOf(
                    ExactAgentReference(AgentId("same-id"), AgentGeneration(1)),
                    ExactAgentReference(AgentId("same-id"), AgentGeneration(2))
                )
            )
        }
    }

    @Test
    fun exact_registration_read_and_remove_ownership() {
        val f = fixture()
        val value = record()
        val registration = assertIs<AgentCoordinationRegistrationResult.Registered>(
            f.store.register(value, context(f))
        ).registration

        assertEquals(value, registration.coordination)
        assertTrue(registration.generation.value > 0)
        assertEquals(value, f.store.find(value.id))
        assertEquals(registration.generation, f.store.inspect(value.id)?.generation)
        assertTrue(registration.remove(context(f, "removeAgentCoordination")))
        assertFalse(registration.remove(context(f, "removeAgentCoordinationAgain")))
        assertNull(f.store.find(value.id))
    }

    @Test
    fun duplicate_id_rejects_without_replacement() {
        val f = fixture()
        val first = record(purpose = "first private purpose")
        val second = record(purpose = "second private purpose")
        val firstRegistration = assertIs<AgentCoordinationRegistrationResult.Registered>(
            f.store.register(first, context(f))
        ).registration

        assertIs<AgentCoordinationRegistrationResult.Rejected>(f.store.register(second, context(f)))
        assertEquals(first, f.store.find(first.id))
        assertEquals(firstRegistration.generation, f.store.inspect(first.id)?.generation)
    }

    @Test
    fun stale_registration_cannot_remove_replacement() {
        val f = fixture()
        val first = assertIs<AgentCoordinationRegistrationResult.Registered>(
            f.store.register(record(), context(f))
        ).registration
        assertTrue(first.remove(context(f, "removeFirst")))

        val replacement = assertIs<AgentCoordinationRegistrationResult.Registered>(
            f.store.register(record(purpose = "replacement private purpose"), context(f))
        ).registration

        assertNotEquals(first.generation, replacement.generation)
        assertFalse(first.remove(context(f, "staleRemove")))
        assertEquals(replacement.coordination, f.store.find(replacement.coordination.id))
    }

    @Test
    fun participant_exact_generations_survive_as_data_only() {
        val f = fixture()
        val value = record()
        f.store.register(value, context(f))

        assertEquals(
            listOf(AgentGeneration(3), AgentGeneration(5)),
            f.store.find(value.id)?.participants?.map { it.generation }
        )
    }

    @Test
    fun private_purpose_is_redacted_from_rendering_and_observability() {
        val f = fixture()
        val secret = "never-log-coordination-purpose"
        val value = record(purpose = secret)
        val registration = assertIs<AgentCoordinationRegistrationResult.Registered>(
            f.store.register(value, context(f))
        ).registration
        registration.remove(context(f, "removeCoordination"))

        assertFalse(value.toString().contains(secret))
        assertFalse(f.logs.snapshot().any { event ->
            event.message == secret || event.metadata.values.any { it == secret }
        })
    }

    @Test
    fun snapshots_are_deterministic_detached_views() {
        val f = fixture()
        val laterIdEarlierTime = record(id = "coord-z", createdAt = "2026-08-29T21:40:00Z")
        val earlierIdSameTime = record(id = "coord-a", createdAt = "2026-08-29T21:40:00Z")
        val latest = record(id = "coord-b", createdAt = "2026-08-29T21:41:00Z")

        f.store.register(laterIdEarlierTime, context(f))
        f.store.register(earlierIdSameTime, context(f))
        val earlierSnapshot = f.store.snapshot()
        f.store.register(latest, context(f))

        assertEquals(listOf(earlierIdSameTime, laterIdEarlierTime), earlierSnapshot)
        assertEquals(listOf(earlierIdSameTime, laterIdEarlierTime, latest), f.store.snapshot())
    }

    @Test
    fun concurrent_same_id_registration_has_exactly_one_winner() {
        val f = fixture()
        val results = ConcurrentLinkedQueue<AgentCoordinationRegistrationResult>()
        val workers = List(24) { index ->
            thread(start = false) {
                results += f.store.register(
                    record(purpose = "private-purpose-$index"),
                    context(f, "concurrentRegisterCoordination")
                )
            }
        }

        workers.forEach { it.start() }
        workers.forEach { it.join() }

        assertEquals(1, results.count { it is AgentCoordinationRegistrationResult.Registered })
        assertEquals(23, results.count { it is AgentCoordinationRegistrationResult.Rejected })
        assertTrue(f.store.contains(AgentCoordinationId("coordination-1")))
    }
}
