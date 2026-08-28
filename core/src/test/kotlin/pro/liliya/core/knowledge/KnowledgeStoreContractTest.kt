package pro.liliya.core.knowledge

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.LogContextPropagation
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.observability.CoreObservability
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnowledgeStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink,
        val store: KnowledgeStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val observability = CoreObservability(
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            diagnostics = DiagnosticRecorder(diagnostics)
        )
        return Fixture(logs, diagnostics, KnowledgeStore(observability))
    }

    private fun memoryItem(
        id: String = "knowledge-1",
        content: String = "preferred map provider is offline-first",
        createdAt: Instant = Instant.parse("2026-08-28T20:30:00Z"),
        memoryGeneration: Long = 7L
    ) = KnowledgeItem(
        id = KnowledgeItemId(id),
        origin = KnowledgeOrigin.Memory(
            recordId = MemoryRecordId("memory-42"),
            generation = MemoryGeneration(memoryGeneration)
        ),
        content = content,
        createdAt = createdAt
    )

    @Test
    fun memory_origin_is_bound_to_exact_memory_generation_and_observable() {
        val f = fixture()
        val item = memoryItem()
        val registration = assertIs<KnowledgeRegistrationResult.Registered>(
            f.store.register(item, context("register"))
        ).registration

        val snapshot = assertNotNull(f.store.inspect(item.id))
        assertEquals(item, snapshot.item)
        assertEquals(registration.generation, snapshot.generation)

        val log = assertNotNull(f.logs.snapshot().lastOrNull { it.marker == "KNOWLEDGE_REGISTERED" })
        assertEquals("memory", log.metadata["knowledgeOriginType"])
        assertEquals("memory-42", log.metadata["memoryRecordId"])
        assertEquals("7", log.metadata["memoryGeneration"])
        assertFalse(log.metadata.containsKey("content"))
    }

    @Test
    fun declared_origin_is_attribution_only_and_preserved() {
        val f = fixture()
        val item = KnowledgeItem(
            id = KnowledgeItemId("declared-1"),
            origin = KnowledgeOrigin.Declared(
                sourceId = KnowledgeSourceId("operator"),
                sourceReference = KnowledgeSourceReference("note-9")
            ),
            content = "candidate preference",
            createdAt = Instant.parse("2026-08-28T20:31:00Z")
        )

        assertIs<KnowledgeRegistrationResult.Registered>(
            f.store.register(item, context("declared"))
        )

        assertEquals(item, f.store.find(item.id))
        val log = assertNotNull(f.logs.snapshot().lastOrNull { it.marker == "KNOWLEDGE_REGISTERED" })
        assertEquals("declared", log.metadata["knowledgeOriginType"])
        assertEquals("operator", log.metadata["knowledgeSourceId"])
        assertEquals("note-9", log.metadata["knowledgeSourceReference"])
        assertFalse(log.metadata.keys.any { it.contains("trust", ignoreCase = true) })
        assertFalse(log.metadata.keys.any { it.contains("confidence", ignoreCase = true) })
    }

    @Test
    fun duplicate_id_is_rejected_without_replacing_current_item() {
        val f = fixture()
        val first = memoryItem(content = "first")
        val second = memoryItem(content = "second", memoryGeneration = 8L)

        val firstRegistration = assertIs<KnowledgeRegistrationResult.Registered>(
            f.store.register(first, context("first"))
        ).registration
        assertIs<KnowledgeRegistrationResult.Rejected>(
            f.store.register(second, context("second"))
        )

        assertEquals(first, f.store.find(first.id))
        val rejection = assertNotNull(
            f.logs.snapshot().lastOrNull { it.marker == "KNOWLEDGE_REGISTRATION_REJECTED" }
        )
        assertTrue(rejection.metadata["knowledgeGeneration"]?.isNotBlank() == true)
        assertNotEquals(firstRegistration.generation.value.toString(), rejection.metadata["knowledgeGeneration"])
        assertTrue(f.diagnostics.snapshot().any { it.code == "KNOWLEDGE_REGISTRATION_REJECTED" })
    }

    @Test
    fun stale_registration_cannot_remove_same_id_replacement() {
        val f = fixture()
        val first = memoryItem(content = "first")
        val stale = assertIs<KnowledgeRegistrationResult.Registered>(
            f.store.register(first, context("first"))
        ).registration
        assertTrue(stale.remove(context("remove-first")))

        val replacement = memoryItem(content = "replacement", memoryGeneration = 8L)
        val current = assertIs<KnowledgeRegistrationResult.Registered>(
            f.store.register(replacement, context("replacement"))
        ).registration

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove(context("stale-remove")))
        assertEquals(replacement, f.store.find(replacement.id))
        assertEquals(current.generation, f.store.inspect(replacement.id)?.generation)
        assertTrue(f.diagnostics.snapshot().any { it.code == "KNOWLEDGE_REMOVAL_REJECTED" })
    }

    @Test
    fun removal_eliminates_item_and_generation_snapshot_together() {
        val f = fixture()
        val item = memoryItem()
        val registration = assertIs<KnowledgeRegistrationResult.Registered>(
            f.store.register(item, context("register"))
        ).registration

        assertTrue(registration.remove(context("remove")))

        assertNull(f.store.find(item.id))
        assertNull(f.store.inspect(item.id))
        assertFalse(f.store.contains(item.id))
    }

    @Test
    fun snapshot_order_is_deterministic_by_created_at_then_id() {
        val f = fixture()
        val earlier = Instant.parse("2026-08-28T20:30:00Z")
        val later = Instant.parse("2026-08-28T20:31:00Z")
        listOf(
            memoryItem(id = "b", createdAt = earlier),
            memoryItem(id = "c", createdAt = later),
            memoryItem(id = "a", createdAt = earlier)
        ).forEachIndexed { index, item ->
            assertIs<KnowledgeRegistrationResult.Registered>(
                f.store.register(item, context("register-$index"))
            )
        }

        assertEquals(listOf("a", "b", "c"), f.store.snapshot().map { it.id.value })
        assertEquals(listOf("a", "b", "c"), f.store.snapshotEntries().map { it.item.id.value })
    }

    @Test
    fun concurrent_same_id_registration_has_exactly_one_winner() {
        val f = fixture()
        val attempts = 32
        val executor = Executors.newFixedThreadPool(attempts)
        val ready = CountDownLatch(attempts)
        val start = CountDownLatch(1)
        val done = CountDownLatch(attempts)
        val winners = AtomicInteger(0)

        try {
            repeat(attempts) { index ->
                executor.submit {
                    try {
                        ready.countDown()
                        start.await()
                        if (
                            f.store.register(
                                memoryItem(content = "candidate-$index", memoryGeneration = index + 1L),
                                context("candidate-$index")
                            ) is KnowledgeRegistrationResult.Registered
                        ) {
                            winners.incrementAndGet()
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(1, winners.get())
            assertEquals(1, f.store.snapshot().size)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    private fun context(correlationId: String) = LogContextPropagation.root(
        module = "CORE",
        component = "Knowledge",
        operation = "knowledge-store-contract",
        generator = CorrelationIdGenerator { correlationId }
    )
}
