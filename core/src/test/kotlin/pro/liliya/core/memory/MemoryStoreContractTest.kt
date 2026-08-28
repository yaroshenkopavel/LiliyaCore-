package pro.liliya.core.memory

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
import pro.liliya.core.observability.CoreObservability
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink,
        val store: MemoryStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val observability = CoreObservability(
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            diagnostics = DiagnosticRecorder(diagnostics)
        )
        return Fixture(logs, diagnostics, MemoryStore(observability))
    }

    private fun record(
        id: String = "memory-1",
        source: String = "conversation",
        content: String = "maps preference",
        createdAt: Instant = Instant.parse("2026-08-28T18:30:00Z")
    ) = MemoryRecord(
        id = MemoryRecordId(id),
        sourceId = MemorySourceId(source),
        content = content,
        createdAt = createdAt
    )

    @Test
    fun register_find_snapshot_and_remove_are_observable() {
        val f = fixture()
        val memory = record()
        val registration = assertIs<MemoryRegistrationResult.Registered>(
            f.store.register(memory, context("register"))
        ).registration

        assertEquals(memory, f.store.find(memory.id))
        assertEquals(listOf(memory), f.store.snapshot())
        assertTrue(registration.remove(context("remove")))
        assertNull(f.store.find(memory.id))
        assertFalse(f.store.contains(memory.id))
        assertEquals(
            listOf("MEMORY_REGISTERED", "MEMORY_REMOVED"),
            f.logs.snapshot().map { it.marker }
        )
        assertEquals(
            listOf("MEMORY_REGISTERED", "MEMORY_REMOVED"),
            f.diagnostics.snapshot().map { it.code }
        )
        assertEquals(
            registration.generation.value.toString(),
            f.logs.snapshot().first().metadata["memoryGeneration"]
        )
    }

    @Test
    fun duplicate_id_is_rejected_without_replacing_current_record_and_is_observable() {
        val f = fixture()
        val first = record(content = "first")
        val second = record(content = "second")

        assertIs<MemoryRegistrationResult.Registered>(
            f.store.register(first, context("first"))
        )
        assertIs<MemoryRegistrationResult.Rejected>(
            f.store.register(second, context("second"))
        )

        assertEquals(first, f.store.find(first.id))
        val rejectionLog = assertNotNull(
            f.logs.snapshot().lastOrNull { it.marker == "MEMORY_REGISTRATION_REJECTED" }
        )
        assertTrue(rejectionLog.metadata["memoryGeneration"]?.isNotBlank() == true)
        assertEquals(first.id.value, rejectionLog.metadata["memoryRecordId"])
        assertTrue(
            f.diagnostics.snapshot().any { it.code == "MEMORY_REGISTRATION_REJECTED" }
        )
    }

    @Test
    fun stale_registration_cannot_remove_replacement_record_and_rejection_is_observable() {
        val f = fixture()
        val first = record(content = "first")
        val firstRegistration = assertIs<MemoryRegistrationResult.Registered>(
            f.store.register(first, context("first"))
        ).registration
        assertTrue(firstRegistration.remove(context("first-remove")))

        val replacement = record(content = "replacement")
        assertIs<MemoryRegistrationResult.Registered>(
            f.store.register(replacement, context("replacement"))
        )

        assertFalse(firstRegistration.remove(context("stale-remove")))
        assertEquals(replacement, f.store.find(replacement.id))
        val rejectionLog = assertNotNull(
            f.logs.snapshot().lastOrNull { it.marker == "MEMORY_REMOVAL_REJECTED" }
        )
        assertEquals(
            firstRegistration.generation.value.toString(),
            rejectionLog.metadata["memoryGeneration"]
        )
        assertTrue(
            f.diagnostics.snapshot().any { it.code == "MEMORY_REMOVAL_REJECTED" }
        )
    }

    @Test
    fun snapshot_order_is_deterministic_by_created_at_then_id() {
        val f = fixture()
        val later = Instant.parse("2026-08-28T18:31:00Z")
        val earlier = Instant.parse("2026-08-28T18:30:00Z")
        val records = listOf(
            record(id = "b", createdAt = earlier),
            record(id = "c", createdAt = later),
            record(id = "a", createdAt = earlier)
        )
        records.forEachIndexed { index, memory ->
            assertIs<MemoryRegistrationResult.Registered>(
                f.store.register(memory, context("register-$index"))
            )
        }

        assertEquals(listOf("a", "b", "c"), f.store.snapshot().map { it.id.value })
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
                                record(content = "candidate-$index"),
                                context("candidate-$index")
                            ) is MemoryRegistrationResult.Registered
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
        component = "Memory",
        operation = "memory-store-contract",
        generator = CorrelationIdGenerator { correlationId }
    )
}
