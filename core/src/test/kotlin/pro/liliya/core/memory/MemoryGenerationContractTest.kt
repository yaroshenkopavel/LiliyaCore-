package pro.liliya.core.memory

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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryGenerationContractTest {
    private fun composition(): MemoryComposition {
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            correlationIds = CorrelationIdGenerator {
                "memory-generation-${sequence.incrementAndGet()}"
            }
        )
        return MemoryComposition(foundation)
    }

    private fun record(content: String) = MemoryRecord(
        id = MemoryRecordId("memory-1"),
        provenance = MemoryProvenance(
            sourceId = MemorySourceId("conversation"),
            sourceReference = MemorySourceReference("message-42")
        ),
        content = content,
        createdAt = Instant.parse("2026-08-28T20:00:00Z")
    )

    @Test
    fun ownership_generation_matches_read_only_snapshot() {
        val memory = composition()
        val record = record("first")
        val ownership = (memory.remember(record) as MemoryRememberResult.Remembered).ownership

        val snapshot = memory.inspect(record.id)!!

        assertEquals(record, snapshot.record)
        assertEquals(ownership.generation, snapshot.generation)
        assertEquals(listOf(snapshot), memory.snapshotEntries())
        assertEquals(listOf(record), memory.snapshot())
    }

    @Test
    fun replacement_with_same_record_id_receives_new_generation() {
        val memory = composition()
        val firstOwnership = (
            memory.remember(record("first")) as MemoryRememberResult.Remembered
        ).ownership
        val firstGeneration = firstOwnership.generation

        assertTrue(firstOwnership.remove())

        val replacement = record("replacement")
        val replacementOwnership = (
            memory.remember(replacement) as MemoryRememberResult.Remembered
        ).ownership

        assertNotEquals(firstGeneration, replacementOwnership.generation)
        assertEquals(replacementOwnership.generation, memory.inspect(replacement.id)?.generation)
        assertEquals(replacement, memory.find(replacement.id))
    }

    @Test
    fun stale_ownership_generation_cannot_remove_replacement() {
        val memory = composition()
        val stale = (memory.remember(record("first")) as MemoryRememberResult.Remembered).ownership
        assertTrue(stale.remove())

        val replacement = record("replacement")
        val current = (memory.remember(replacement) as MemoryRememberResult.Remembered).ownership

        assertTrue(stale.generation != current.generation)
        assertTrue(!stale.remove())
        assertEquals(current.generation, memory.inspect(replacement.id)?.generation)
        assertEquals(replacement, memory.find(replacement.id))
    }

    @Test
    fun removal_eliminates_record_and_generation_snapshot_together() {
        val memory = composition()
        val ownership = (memory.remember(record("first")) as MemoryRememberResult.Remembered).ownership

        assertTrue(ownership.remove())

        assertNull(memory.find(ownership.record.id))
        assertNull(memory.inspect(ownership.record.id))
    }

    @Test
    fun memory_generation_must_be_positive() {
        assertFailsWith<IllegalArgumentException> { MemoryGeneration(0) }
        assertFailsWith<IllegalArgumentException> { MemoryGeneration(-1) }
    }
}
