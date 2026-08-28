package pro.liliya.core.memory

import java.lang.reflect.Modifier
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink,
        val composition: MemoryComposition
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "memory-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, diagnostics, MemoryComposition(foundation))
    }

    private fun record(content: String = "maps preference") = MemoryRecord(
        id = MemoryRecordId("memory-1"),
        sourceId = MemorySourceId("conversation"),
        content = content,
        createdAt = Instant.parse("2026-08-28T18:30:00Z")
    )

    @Test
    fun remember_read_and_remove_are_owned_by_composition() {
        val f = fixture()
        val memory = record()
        val ownership = assertIs<MemoryRememberResult.Remembered>(
            f.composition.remember(memory)
        ).ownership

        assertEquals(memory, f.composition.find(memory.id))
        assertEquals(listOf(memory), f.composition.snapshot())
        assertTrue(ownership.remove())
        assertNull(f.composition.find(memory.id))
        assertFalse(f.composition.contains(memory.id))
        assertEquals(
            listOf("MEMORY_REGISTERED", "MEMORY_REMOVED"),
            f.logs.snapshot().map { it.marker }
        )
    }

    @Test
    fun duplicate_remember_does_not_replace_current_memory() {
        val f = fixture()
        val first = record("first")
        val second = record("second")

        assertIs<MemoryRememberResult.Remembered>(f.composition.remember(first))
        assertIs<MemoryRememberResult.Rejected>(f.composition.remember(second))
        assertEquals(first, f.composition.find(first.id))
    }

    @Test
    fun stale_ownership_cannot_remove_replacement_memory() {
        val f = fixture()
        val first = record("first")
        val firstOwnership = assertIs<MemoryRememberResult.Remembered>(
            f.composition.remember(first)
        ).ownership
        assertTrue(firstOwnership.remove())

        val replacement = record("replacement")
        assertIs<MemoryRememberResult.Remembered>(f.composition.remember(replacement))

        assertFalse(firstOwnership.remove())
        assertEquals(replacement, f.composition.find(replacement.id))
    }

    @Test
    fun public_api_does_not_expose_raw_store_or_registration() {
        val forbidden = setOf(
            MemoryStore::class.java,
            MemoryRegistration::class.java
        )
        val exposed = MemoryComposition::class.java.methods.filter { method ->
            Modifier.isPublic(method.modifiers) && method.returnType in forbidden
        }
        assertTrue(exposed.isEmpty(), "memory API must not expose raw store internals: $exposed")
    }
}
