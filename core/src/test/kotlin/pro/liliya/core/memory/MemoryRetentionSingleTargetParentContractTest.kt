package pro.liliya.core.memory

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
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

class MemoryRetentionSingleTargetParentContractTest {
    @Test
    fun one_target_parent_round_trips_and_completes_after_one_advance() {
        val backend = InMemoryPersistentRecordBackend()
        val journal = openParent(backend)
        val plan = MemoryRetentionMultiTargetPlan(
            id = MemoryRetentionMultiTargetId("single-parent"),
            targets = listOf(
                MemoryRetentionTransactionTarget(
                    recordId = MemoryRecordId("only-record"),
                    generation = MemoryGeneration(3),
                    retentionClass = MemoryRetentionClass.EPISODIC,
                    disposition = MemoryRetentionDisposition.RECORD_BUDGET_REJECTED
                )
            ),
            createdAt = Instant.parse("2026-09-17T14:30:00Z")
        )

        val prepared = assertIs<PersistentMemoryRetentionMultiTargetPrepareResult.Prepared>(
            journal.prepare(plan)
        ).snapshot
        assertEquals(MemoryRetentionMultiTargetState.ACTIVE, prepared.state)
        assertEquals(0, prepared.nextIndex)
        assertEquals(plan.targets.single(), prepared.currentTarget)

        val reopened = openParent(backend)
        val restored = checkNotNull(reopened.inspect(plan.id))
        assertEquals(prepared, restored)
        assertEquals(plan.targets.single(), restored.currentTarget)

        val completed = assertIs<PersistentMemoryRetentionMultiTargetProgressResult.Committed>(
            reopened.advance(restored.reference, expectedIndex = 0)
        ).snapshot
        assertEquals(MemoryRetentionMultiTargetState.COMPLETED, completed.state)
        assertEquals(1, completed.nextIndex)
        assertEquals(null, completed.currentTarget)
    }

    private fun openParent(
        backend: InMemoryPersistentRecordBackend
    ): PersistentMemoryRetentionMultiTargetJournal =
        assertIs<PersistentMemoryRetentionMultiTargetJournalOpenResult.Opened>(
            PersistentMemoryRetentionMultiTargetJournal.open(
                foundation(),
                PersistentStoreId("retention-single-target-parent"),
                backend
            )
        ).journal

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            correlationIds = CorrelationIdGenerator {
                "retention-single-target-parent-${sequence.incrementAndGet()}"
            }
        )
    }
}
