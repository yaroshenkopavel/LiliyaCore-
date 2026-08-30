package pro.liliya.core.memory

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentStoreId

class PersistentMemoryCompositionContractTest {
    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            correlationIds = CorrelationIdGenerator {
                "persistent-memory-${sequence.incrementAndGet()}"
            }
        )
    }

    private fun record(
        id: String = "memory-1",
        content: String = "private durable memory",
        createdAt: Instant = Instant.parse("2026-08-30T14:25:00Z")
    ) = MemoryRecord(
        id = MemoryRecordId(id),
        provenance = MemoryProvenance(
            sourceId = MemorySourceId("conversation"),
            sourceReference = MemorySourceReference("turn-1")
        ),
        content = content,
        createdAt = createdAt
    )

    private fun open(
        backend: InMemoryPersistentRecordBackend,
        storeId: String = "memory-store"
    ): PersistentMemoryComposition = assertIs<PersistentMemoryOpenResult.Opened>(
        PersistentMemoryComposition.open(
            foundation = foundation(),
            storeId = PersistentStoreId(storeId),
            backend = backend
        )
    ).composition

    @Test
    fun remember_is_visible_only_after_durable_commit_and_reopen_restores_exact_generation() {
        val backend = InMemoryPersistentRecordBackend()
        val memory = record()
        val first = open(backend)

        val remembered = assertIs<PersistentMemoryRememberResult.Remembered>(
            first.remember(memory)
        )
        assertEquals(memory, first.find(memory.id))
        assertEquals(remembered.ownership.generation, first.inspect(memory.id)?.generation)

        val reopened = open(backend)
        assertEquals(memory, reopened.find(memory.id))
        assertEquals(
            remembered.ownership.generation,
            reopened.inspect(memory.id)?.generation
        )
    }

    @Test
    fun failed_durable_remember_commit_keeps_memory_locally_absent_and_reopen_empty() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val memory = record()
        backend.failNextCommit()

        assertIs<PersistentMemoryRememberResult.Failed>(composition.remember(memory))
        assertNull(composition.find(memory.id))
        assertTrue(composition.snapshot().isEmpty())

        val reopened = open(backend)
        assertFalse(reopened.contains(memory.id))
        assertTrue(reopened.snapshot().isEmpty())
    }

    @Test
    fun durable_remove_commits_before_local_exact_removal() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val memory = record()
        val ownership = assertIs<PersistentMemoryRememberResult.Remembered>(
            composition.remember(memory)
        ).ownership

        assertIs<PersistentMemoryMutationResult.Committed>(ownership.remove())
        assertFalse(composition.contains(memory.id))

        val reopened = open(backend)
        assertFalse(reopened.contains(memory.id))
    }

    @Test
    fun failed_durable_remove_keeps_local_and_reopened_memory_live() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val memory = record()
        val ownership = assertIs<PersistentMemoryRememberResult.Remembered>(
            composition.remember(memory)
        ).ownership
        backend.failNextCommit()

        assertIs<PersistentMemoryMutationResult.Failed>(ownership.remove())
        assertEquals(memory, composition.find(memory.id))

        val reopened = open(backend)
        assertEquals(memory, reopened.find(memory.id))
        assertEquals(ownership.generation, reopened.inspect(memory.id)?.generation)
    }

    @Test
    fun stale_owner_cannot_remove_newer_persisted_replacement_generation() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val firstRecord = record(content = "first private memory")
        val first = assertIs<PersistentMemoryRememberResult.Remembered>(
            composition.remember(firstRecord)
        ).ownership
        assertIs<PersistentMemoryMutationResult.Committed>(first.remove())

        val replacementRecord = record(content = "replacement private memory")
        val replacement = assertIs<PersistentMemoryRememberResult.Remembered>(
            composition.remember(replacementRecord)
        ).ownership
        assertTrue(replacement.generation.value > first.generation.value)

        assertIs<PersistentMemoryMutationResult.Rejected>(first.remove())
        assertEquals(replacementRecord, composition.find(replacementRecord.id))
        assertEquals(replacement.generation, composition.inspect(replacementRecord.id)?.generation)

        val reopened = open(backend)
        assertEquals(replacementRecord, reopened.find(replacementRecord.id))
        assertEquals(replacement.generation, reopened.inspect(replacementRecord.id)?.generation)
    }
}
