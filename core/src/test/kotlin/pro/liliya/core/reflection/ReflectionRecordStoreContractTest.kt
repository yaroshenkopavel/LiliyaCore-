package pro.liliya.core.reflection

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReflectionRecordStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val store: ReflectionRecordStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "reflection-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, foundation, ReflectionRecordStore(foundation.observability))
    }

    private fun record(
        id: String = "reflection-1",
        content: String = "caller reflection",
        origin: ReflectionOrigin = ReflectionOrigin.Declared(
            ReflectionSourceId("caller"),
            ReflectionSourceReference("contract")
        ),
        createdAt: Instant = Instant.parse("2026-08-29T01:00:00Z")
    ) = ReflectionRecord(ReflectionRecordId(id), origin, content, createdAt)

    private fun context(f: Fixture, operation: String) = f.foundation.rootContext(operation, "Reflection")

    @Test
    fun register_read_and_remove_use_exact_ownership() {
        val f = fixture()
        val r = record()
        val registration = assertIs<ReflectionRecordRegistrationResult.Registered>(
            f.store.register(r, context(f, "register"))
        ).registration

        assertEquals(r, f.store.find(r.id))
        assertEquals(registration.generation, f.store.inspect(r.id)?.generation)
        assertTrue(registration.remove(context(f, "remove")))
        assertNull(f.store.find(r.id))
    }

    @Test
    fun duplicate_id_is_rejected_without_replacement() {
        val f = fixture()
        val first = record(content = "first")
        val second = record(content = "second")
        assertIs<ReflectionRecordRegistrationResult.Registered>(f.store.register(first, context(f, "first")))
        assertIs<ReflectionRecordRegistrationResult.Rejected>(f.store.register(second, context(f, "second")))
        assertEquals(first, f.store.find(first.id))
    }

    @Test
    fun stale_registration_cannot_remove_replacement() {
        val f = fixture()
        val stale = assertIs<ReflectionRecordRegistrationResult.Registered>(
            f.store.register(record(), context(f, "first"))
        ).registration
        assertTrue(stale.remove(context(f, "remove-first")))
        val replacement = record(content = "replacement")
        val current = assertIs<ReflectionRecordRegistrationResult.Registered>(
            f.store.register(replacement, context(f, "replacement"))
        ).registration
        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove(context(f, "stale-remove")))
        assertEquals(replacement, f.store.find(replacement.id))
    }

    @Test
    fun memory_and_knowledge_origins_are_structural_without_lookup() {
        val f = fixture()
        val memory = record(
            id = "memory-origin",
            origin = ReflectionOrigin.Memory(MemoryRecordId("missing-memory"), MemoryGeneration(999L))
        )
        val knowledge = record(
            id = "knowledge-origin",
            origin = ReflectionOrigin.Knowledge(KnowledgeItemId("missing-knowledge"), KnowledgeGeneration(999L))
        )
        assertIs<ReflectionRecordRegistrationResult.Registered>(f.store.register(memory, context(f, "memory")))
        assertIs<ReflectionRecordRegistrationResult.Registered>(f.store.register(knowledge, context(f, "knowledge")))
        assertEquals(memory, f.store.find(memory.id))
        assertEquals(knowledge, f.store.find(knowledge.id))
    }

    @Test
    fun content_is_redacted_from_to_string_and_observability_metadata() {
        val f = fixture()
        val secret = "private-reflection-content"
        val r = record(content = secret)
        assertFalse(r.toString().contains(secret))
        assertIs<ReflectionRecordRegistrationResult.Registered>(f.store.register(r, context(f, "register")))
        assertFalse(f.logs.snapshot().flatMap { it.metadata.values }.any { it == secret })
    }

    @Test
    fun snapshot_is_deterministic_by_created_at_then_id() {
        val f = fixture()
        val time = Instant.parse("2026-08-29T02:00:00Z")
        listOf(record("b", createdAt = time), record("a", createdAt = time)).forEach {
            assertIs<ReflectionRecordRegistrationResult.Registered>(f.store.register(it, context(f, it.id.value)))
        }
        assertEquals(listOf("a", "b"), f.store.snapshot().map { it.id.value })
    }

    @Test
    fun concurrent_same_id_registration_has_exactly_one_winner() {
        val f = fixture()
        val threads = 16
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val winners = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threads)
        repeat(threads) { index ->
            executor.execute {
                ready.countDown()
                start.await()
                if (f.store.register(record(content = "r-$index"), context(f, "concurrent-$index")) is ReflectionRecordRegistrationResult.Registered) {
                    winners.incrementAndGet()
                }
                done.countDown()
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertEquals(1, winners.get())
        assertTrue(f.store.contains(ReflectionRecordId("reflection-1")))
    }
}
