package pro.liliya.core.memory

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentStoreId

class MemoryRetentionTransactionPersistenceContractTest {
    private val createdAt = Instant.parse("2026-09-17T10:20:00Z")

    @Test
    fun codec_is_deterministic_identity_only_and_order_independent() {
        val first = target("z", 7, MemoryRetentionClass.SEMANTIC)
        val second = target("a", 3, MemoryRetentionClass.WORKING)
        val left = plan("tx-deterministic", listOf(first, second))
        val right = plan("tx-deterministic", listOf(second, first))

        val leftRecord = MemoryRetentionTransactionPersistentCodec.encode(
            left,
            MemoryRetentionTransactionState.PLANNED
        )
        val rightRecord = MemoryRetentionTransactionPersistentCodec.encode(
            right,
            MemoryRetentionTransactionState.PLANNED
        )

        assertEquals(left, right)
        assertContentEquals(leftRecord.payload.copyBytes(), rightRecord.payload.copyBytes())
        val rendered = left.toString() + leftRecord.toString()
        assertFalse(rendered.contains("private"))
        assertFalse(rendered.contains("content"))
    }

    @Test
    fun checksum_corruption_is_rejected_before_restoration() {
        val encoded = MemoryRetentionTransactionPersistentCodec.encode(
            plan("tx-checksum", listOf(target("memory-1", 4, MemoryRetentionClass.EPISODIC))),
            MemoryRetentionTransactionState.PLANNED
        )
        val damagedBytes = encoded.payload.copyBytes().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }
        val damaged = encoded.copy(payload = PersistentPayload(damagedBytes))

        assertIs<MemoryRetentionTransactionPersistentDecodeResult.Corrupt>(
            MemoryRetentionTransactionPersistentCodec.decode(damaged)
        )
    }

    @Test
    fun prepared_transaction_round_trips_exact_targets_and_generation() {
        val backend = InMemoryPersistentRecordBackend()
        val journal = open(backend)
        val transaction = plan(
            "tx-round-trip",
            listOf(
                target("memory-2", 9, MemoryRetentionClass.EPISODIC),
                target("memory-1", 5, MemoryRetentionClass.WORKING)
            )
        )

        val prepared = assertIs<PersistentMemoryRetentionTransactionPrepareResult.Prepared>(
            journal.prepare(transaction)
        ).snapshot
        val reopened = open(backend)
        val restored = checkNotNull(reopened.inspect(transaction.id))

        assertEquals(transaction, restored.plan)
        assertEquals(prepared.generation, restored.generation)
        assertEquals(MemoryRetentionTransactionState.PLANNED, restored.state)
        assertEquals(listOf("memory-1", "memory-2"), restored.plan.targets.map { it.recordId.value })
    }

    @Test
    fun restart_invalidates_authorized_state_to_recovery_required_without_memory_mutation() {
        val backend = InMemoryPersistentRecordBackend()
        val journal = open(backend)
        val transaction = plan(
            "tx-authorized-crash",
            listOf(target("memory-7", 17, MemoryRetentionClass.SEMANTIC))
        )
        val prepared = assertIs<PersistentMemoryRetentionTransactionPrepareResult.Prepared>(
            journal.prepare(transaction)
        ).snapshot
        val authorized = assertIs<PersistentMemoryRetentionTransactionTransitionResult.Committed>(
            journal.transition(
                reference = prepared.reference,
                expectedState = MemoryRetentionTransactionState.PLANNED,
                nextState = MemoryRetentionTransactionState.AUTHORIZED
            )
        ).snapshot
        assertEquals(MemoryRetentionTransactionState.AUTHORIZED, authorized.state)

        val reopened = open(backend)
        val recovered = checkNotNull(reopened.inspect(transaction.id))
        assertEquals(prepared.generation, recovered.generation)
        assertEquals(MemoryRetentionTransactionState.RECOVERY_REQUIRED, recovered.state)

        val reopenedAgain = open(backend)
        assertEquals(
            MemoryRetentionTransactionState.RECOVERY_REQUIRED,
            reopenedAgain.inspect(transaction.id)?.state
        )
    }

    @Test
    fun stale_transaction_generation_and_terminal_replay_fail_closed() {
        val backend = InMemoryPersistentRecordBackend()
        val journal = open(backend)
        val transaction = plan(
            "tx-stale",
            listOf(target("memory-stale", 22, MemoryRetentionClass.EPISODIC))
        )
        val prepared = assertIs<PersistentMemoryRetentionTransactionPrepareResult.Prepared>(
            journal.prepare(transaction)
        ).snapshot
        val staleReference = prepared.reference.copy(
            generation = MemoryRetentionTransactionGeneration(prepared.generation.value + 1)
        )

        assertIs<PersistentMemoryRetentionTransactionTransitionResult.Rejected>(
            journal.transition(
                staleReference,
                MemoryRetentionTransactionState.PLANNED,
                MemoryRetentionTransactionState.AUTHORIZED
            )
        )

        val authorized = assertIs<PersistentMemoryRetentionTransactionTransitionResult.Committed>(
            journal.transition(
                prepared.reference,
                MemoryRetentionTransactionState.PLANNED,
                MemoryRetentionTransactionState.AUTHORIZED
            )
        ).snapshot
        val committed = assertIs<PersistentMemoryRetentionTransactionTransitionResult.Committed>(
            journal.transition(
                authorized.reference,
                MemoryRetentionTransactionState.AUTHORIZED,
                MemoryRetentionTransactionState.COMMITTED
            )
        ).snapshot
        assertEquals(MemoryRetentionTransactionState.COMMITTED, committed.state)

        assertIs<PersistentMemoryRetentionTransactionTransitionResult.Rejected>(
            journal.transition(
                committed.reference,
                MemoryRetentionTransactionState.COMMITTED,
                MemoryRetentionTransactionState.AUTHORIZED
            )
        )
    }

    @Test
    fun failed_authorized_recovery_transition_fails_open_instead_of_replaying_authority() {
        val backend = InMemoryPersistentRecordBackend()
        val journal = open(backend)
        val transaction = plan(
            "tx-recovery-failure",
            listOf(target("memory-9", 29, MemoryRetentionClass.WORKING))
        )
        val prepared = assertIs<PersistentMemoryRetentionTransactionPrepareResult.Prepared>(
            journal.prepare(transaction)
        ).snapshot
        assertIs<PersistentMemoryRetentionTransactionTransitionResult.Committed>(
            journal.transition(
                prepared.reference,
                MemoryRetentionTransactionState.PLANNED,
                MemoryRetentionTransactionState.AUTHORIZED
            )
        )
        backend.failNextCommit()

        assertIs<PersistentMemoryRetentionTransactionJournalOpenResult.Failed>(
            PersistentMemoryRetentionTransactionJournal.open(
                foundation(),
                PersistentStoreId("retention-journal"),
                backend
            )
        )
    }

    @Test
    fun duplicate_record_ids_are_rejected_even_when_generations_differ() {
        val first = target("duplicate", 1, MemoryRetentionClass.WORKING)
        val second = target("duplicate", 2, MemoryRetentionClass.EPISODIC)
        var failed = false
        try {
            plan("tx-duplicate", listOf(first, second))
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
        assertNotEquals(first.generation, second.generation)
    }

    private fun plan(
        id: String,
        targets: List<MemoryRetentionTransactionTarget>
    ): MemoryRetentionTransactionPlan = MemoryRetentionTransactionPlan(
        id = MemoryRetentionTransactionId(id),
        targets = targets,
        createdAt = createdAt
    )

    private fun target(
        id: String,
        generation: Long,
        retentionClass: MemoryRetentionClass
    ): MemoryRetentionTransactionTarget = MemoryRetentionTransactionTarget(
        recordId = MemoryRecordId(id),
        generation = MemoryGeneration(generation),
        retentionClass = retentionClass,
        disposition = MemoryRetentionDisposition.RECORD_BUDGET_REJECTED
    )

    private fun open(
        backend: InMemoryPersistentRecordBackend
    ): PersistentMemoryRetentionTransactionJournal =
        assertIs<PersistentMemoryRetentionTransactionJournalOpenResult.Opened>(
            PersistentMemoryRetentionTransactionJournal.open(
                foundation = foundation(),
                storeId = PersistentStoreId("retention-journal"),
                backend = backend
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
                "retention-journal-${sequence.incrementAndGet()}"
            }
        )
    }
}
