package pro.liliya.core.memory

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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

class MemoryRetentionDurablePlanPreparerContractTest {
    private val createdAt = Instant.parse("2026-09-17T14:00:00Z")

    @Test
    fun keep_only_ledger_is_noop_and_writes_no_parent_state() {
        val backend = InMemoryPersistentRecordBackend()
        val journal = openParent(backend)
        val preparer = preparer(journal)

        val result = preparer.prepare(
            MemoryRetentionExecutionPlanId("noop"),
            MemoryRetentionLedger(listOf(keep("kept", 1))),
            createdAt
        )

        assertIs<MemoryRetentionDurablePreparationResult.NothingToPrune>(result)
        assertTrue(journal.snapshotEntries().isEmpty())
    }

    @Test
    fun single_target_is_normalized_into_parent_journal_and_survives_reopen() {
        val backend = InMemoryPersistentRecordBackend()
        val journal = openParent(backend)
        val id = MemoryRetentionExecutionPlanId("single-parent")
        val ledger = MemoryRetentionLedger(listOf(prune("single-record", 4)))

        val prepared = assertIs<MemoryRetentionDurablePreparationResult.Prepared>(
            preparer(journal).prepare(id, ledger, createdAt)
        )

        assertEquals(MemoryRetentionMultiTargetId(id.value), prepared.snapshot.plan.id)
        assertEquals(1, prepared.snapshot.plan.targets.size)
        assertEquals(MemoryRetentionMultiTargetState.ACTIVE, prepared.snapshot.state)
        assertEquals(0, prepared.snapshot.nextIndex)

        val reopened = openParent(backend)
        assertEquals(prepared.snapshot, reopened.inspect(prepared.snapshot.plan.id))
    }

    @Test
    fun exact_prepare_retry_after_reopen_is_idempotent_for_single_and_multi() {
        val backend = InMemoryPersistentRecordBackend()
        val first = openParent(backend)
        val singleId = MemoryRetentionExecutionPlanId("retry-single")
        val singleLedger = MemoryRetentionLedger(listOf(prune("single", 5)))
        val singlePrepared = assertIs<MemoryRetentionDurablePreparationResult.Prepared>(
            preparer(first).prepare(singleId, singleLedger, createdAt)
        )

        val reopened = openParent(backend)
        val singleRetried = assertIs<MemoryRetentionDurablePreparationResult.Prepared>(
            preparer(reopened).prepare(singleId, singleLedger, createdAt)
        )
        assertEquals(singlePrepared.snapshot, singleRetried.snapshot)

        val multiId = MemoryRetentionExecutionPlanId("retry-multi")
        val multiLedger = MemoryRetentionLedger(
            listOf(prune("a", 6), prune("b", 7))
        )
        val multiPrepared = assertIs<MemoryRetentionDurablePreparationResult.Prepared>(
            preparer(reopened).prepare(multiId, multiLedger, createdAt)
        )

        val reopenedAgain = openParent(backend)
        val multiRetried = assertIs<MemoryRetentionDurablePreparationResult.Prepared>(
            preparer(reopenedAgain).prepare(multiId, multiLedger, createdAt)
        )
        assertEquals(multiPrepared.snapshot, multiRetried.snapshot)
        assertEquals(2, reopenedAgain.snapshotEntries().size)
    }

    @Test
    fun changed_cardinality_or_targets_with_same_id_are_rejected_without_overwrite() {
        val backend = InMemoryPersistentRecordBackend()
        val journal = openParent(backend)
        val id = MemoryRetentionExecutionPlanId("same-id")
        val original = assertIs<MemoryRetentionDurablePreparationResult.Prepared>(
            preparer(journal).prepare(
                id,
                MemoryRetentionLedger(listOf(prune("original", 8))),
                createdAt
            )
        )

        val changed = assertIs<MemoryRetentionDurablePreparationResult.Rejected>(
            preparer(journal).prepare(
                id,
                MemoryRetentionLedger(listOf(prune("a", 9), prune("b", 10))),
                createdAt
            )
        )

        assertTrue(changed.reason.contains("conflicting"))
        assertEquals(original.snapshot, journal.inspect(original.snapshot.plan.id))
        assertEquals(1, journal.snapshotEntries().size)
    }

    @Test
    fun keep_only_retry_cannot_mask_existing_durable_parent() {
        val backend = InMemoryPersistentRecordBackend()
        val journal = openParent(backend)
        val id = MemoryRetentionExecutionPlanId("mask")
        assertIs<MemoryRetentionDurablePreparationResult.Prepared>(
            preparer(journal).prepare(
                id,
                MemoryRetentionLedger(listOf(prune("live", 11))),
                createdAt
            )
        )

        val masked = assertIs<MemoryRetentionDurablePreparationResult.Rejected>(
            preparer(journal).prepare(
                id,
                MemoryRetentionLedger(listOf(keep("kept", 12))),
                createdAt
            )
        )

        assertTrue(masked.reason.contains("durable state"))
        assertEquals(1, journal.snapshotEntries().size)
    }

    @Test
    fun duplicate_record_ids_fail_before_any_durable_write() {
        val backend = InMemoryPersistentRecordBackend()
        val journal = openParent(backend)
        val duplicate = MemoryRecordId("duplicate")
        val ledger = MemoryRetentionLedger(
            listOf(
                MemoryRetentionLedgerEntry(
                    recordId = duplicate,
                    generation = MemoryGeneration(1),
                    retentionClass = MemoryRetentionClass.EPISODIC,
                    disposition = MemoryRetentionDisposition.RECORD_BUDGET_REJECTED,
                    action = MemoryRetentionShadowAction.PRUNE_CANDIDATE
                ),
                MemoryRetentionLedgerEntry(
                    recordId = duplicate,
                    generation = MemoryGeneration(2),
                    retentionClass = MemoryRetentionClass.EPISODIC,
                    disposition = MemoryRetentionDisposition.CONTENT_BUDGET_REJECTED,
                    action = MemoryRetentionShadowAction.PRUNE_CANDIDATE
                )
            )
        )

        assertFailsWith<IllegalArgumentException> {
            preparer(journal).prepare(
                MemoryRetentionExecutionPlanId("duplicate-plan"),
                ledger,
                createdAt
            )
        }
        assertTrue(journal.snapshotEntries().isEmpty())
    }

    private fun preparer(
        journal: PersistentMemoryRetentionMultiTargetJournal
    ): MemoryRetentionDurablePlanPreparer = MemoryRetentionDurablePlanPreparer(
        builder = MemoryRetentionLedgerExecutionPlanBuilder(),
        parentJournal = journal
    )

    private fun keep(id: String, generation: Long): MemoryRetentionLedgerEntry =
        MemoryRetentionLedgerEntry(
            recordId = MemoryRecordId(id),
            generation = MemoryGeneration(generation),
            retentionClass = MemoryRetentionClass.EPISODIC,
            disposition = MemoryRetentionDisposition.RETAINED,
            action = MemoryRetentionShadowAction.KEEP
        )

    private fun prune(id: String, generation: Long): MemoryRetentionLedgerEntry =
        MemoryRetentionLedgerEntry(
            recordId = MemoryRecordId(id),
            generation = MemoryGeneration(generation),
            retentionClass = MemoryRetentionClass.EPISODIC,
            disposition = MemoryRetentionDisposition.RECORD_BUDGET_REJECTED,
            action = MemoryRetentionShadowAction.PRUNE_CANDIDATE
        )

    private fun openParent(
        backend: InMemoryPersistentRecordBackend
    ): PersistentMemoryRetentionMultiTargetJournal =
        assertIs<PersistentMemoryRetentionMultiTargetJournalOpenResult.Opened>(
            PersistentMemoryRetentionMultiTargetJournal.open(
                foundation(),
                PersistentStoreId("retention-durable-unified-parent"),
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
                "retention-durable-unified-${sequence.incrementAndGet()}"
            }
        )
    }
}
