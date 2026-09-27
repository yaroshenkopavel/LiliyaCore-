package pro.liliya.core.memory

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentStoreId

class PersistentMemoryExactRemovalContractTest {
    @Test
    fun reopened_exact_snapshot_can_be_removed_without_original_ownership_handle() {
        val backend = InMemoryPersistentRecordBackend()
        val first = open(backend)
        val remembered = assertIs<PersistentMemoryRememberResult.Remembered>(
            first.remember(record("memory-exact", "first"))
        )
        val snapshot = first.inspect(remembered.ownership.record.id)!!

        val reopened = open(backend)
        assertEquals(snapshot, reopened.inspect(snapshot.record.id))
        assertIs<PersistentMemoryMutationResult.Committed>(reopened.removeExact(snapshot))
        assertFalse(reopened.contains(snapshot.record.id))
        assertFalse(open(backend).contains(snapshot.record.id))
    }

    @Test
    fun stale_snapshot_cannot_remove_newer_reused_record_generation() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val first = assertIs<PersistentMemoryRememberResult.Remembered>(
            composition.remember(record("memory-reused", "first"))
        )
        val staleSnapshot = composition.inspect(first.ownership.record.id)!!
        assertIs<PersistentMemoryMutationResult.Committed>(first.ownership.remove())

        val replacement = assertIs<PersistentMemoryRememberResult.Remembered>(
            composition.remember(record("memory-reused", "replacement"))
        )
        val live = composition.inspect(replacement.ownership.record.id)!!

        assertIs<PersistentMemoryMutationResult.Rejected>(composition.removeExact(staleSnapshot))
        assertEquals(live, composition.inspect(live.record.id))
        assertEquals(live, open(backend).inspect(live.record.id))
    }

    @Test
    fun failed_exact_durable_remove_keeps_local_and_reopened_memory_live() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val remembered = assertIs<PersistentMemoryRememberResult.Remembered>(
            composition.remember(record("memory-failed", "survives"))
        )
        val snapshot = composition.inspect(remembered.ownership.record.id)!!
        backend.failNextCommit()

        assertIs<PersistentMemoryMutationResult.Failed>(composition.removeExact(snapshot))
        assertEquals(snapshot, composition.inspect(snapshot.record.id))
        assertEquals(snapshot, open(backend).inspect(snapshot.record.id))
    }

    private fun record(id: String, content: String): MemoryRecord = MemoryRecord(
        id = MemoryRecordId(id),
        sourceId = MemorySourceId("retention-exact-removal"),
        content = content,
        createdAt = Instant.parse("2026-09-17T00:00:00Z")
    )

    private fun open(backend: InMemoryPersistentRecordBackend): PersistentMemoryComposition =
        assertIs<PersistentMemoryOpenResult.Opened>(
            PersistentMemoryComposition.open(
                foundation = foundation(),
                storeId = PersistentStoreId("retention-exact-memory"),
                backend = backend
            )
        ).composition

    private fun foundation(): FoundationComposition {
        val correlation = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
            correlationIds = CorrelationIdGenerator { "retention-exact-${correlation.incrementAndGet()}" }
        )
    }
}
