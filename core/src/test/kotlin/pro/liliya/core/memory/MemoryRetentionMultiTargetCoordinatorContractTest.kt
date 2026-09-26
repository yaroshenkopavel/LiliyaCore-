package pro.liliya.core.memory

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentStoreId

class MemoryRetentionMultiTargetCoordinatorContractTest {
    private val createdAt = Instant.parse("2026-09-17T11:30:00Z")
    private val principal = AuthorityPrincipal("multi-target-retention")

    @Test
    fun durable_progress_survives_reopen_and_stale_index_cannot_skip_target() {
        val backend = InMemoryPersistentRecordBackend()
        val journal = openParent(backend)
        val prepared = assertIs<PersistentMemoryRetentionMultiTargetPrepareResult.Prepared>(
            journal.prepare(plan("progress", targets()))
        ).snapshot

        val advanced = assertIs<PersistentMemoryRetentionMultiTargetProgressResult.Committed>(
            journal.advance(prepared.reference, 0)
        ).snapshot
        assertEquals(1, advanced.nextIndex)
        assertEquals(MemoryRetentionMultiTargetState.ACTIVE, advanced.state)

        assertIs<PersistentMemoryRetentionMultiTargetProgressResult.Rejected>(
            journal.advance(prepared.reference, 0)
        )

        val restored = checkNotNull(openParent(backend).inspect(prepared.plan.id))
        assertEquals(1, restored.nextIndex)
        assertEquals(prepared.generation, restored.generation)
    }

    @Test
    fun coordinator_executes_each_deterministic_child_once_and_completes_in_order() {
        val parentBackend = InMemoryPersistentRecordBackend()
        val childBackend = InMemoryPersistentRecordBackend()
        val parentJournal = openParent(parentBackend)
        val childJournal = openChild(childBackend)
        val prepared = assertIs<PersistentMemoryRetentionMultiTargetPrepareResult.Prepared>(
            parentJournal.prepare(plan("ordered", targets()))
        ).snapshot
        val execution = CommittingExecutionPort(childJournal)
        val coordinator = MemoryRetentionMultiTargetCoordinator(
            parentJournal,
            childJournal,
            execution
        )

        val first = assertIs<MemoryRetentionMultiTargetExecutionResult.Advanced>(
            coordinator.executeNext(prepared.reference, principal)
        )
        assertEquals(1, first.snapshot.nextIndex)
        assertEquals("ordered:target:0", first.childReference.transactionId.value)

        val second = assertIs<MemoryRetentionMultiTargetExecutionResult.Advanced>(
            coordinator.executeNext(prepared.reference, principal)
        )
        assertEquals(2, second.snapshot.nextIndex)
        assertEquals("ordered:target:1", second.childReference.transactionId.value)

        val third = assertIs<MemoryRetentionMultiTargetExecutionResult.Completed>(
            coordinator.executeNext(prepared.reference, principal)
        )
        assertEquals(3, third.snapshot.nextIndex)
        assertEquals(MemoryRetentionMultiTargetState.COMPLETED, third.snapshot.state)
        assertEquals("ordered:target:2", third.childReference?.transactionId?.value)
        assertEquals(
            listOf("ordered:target:0", "ordered:target:1", "ordered:target:2"),
            execution.executed.map { it.transactionId.value }
        )
    }

    @Test
    fun committed_child_after_crash_advances_parent_without_second_execution() {
        val parentBackend = InMemoryPersistentRecordBackend()
        val childBackend = InMemoryPersistentRecordBackend()
        val parentJournal = openParent(parentBackend)
        val childJournal = openChild(childBackend)
        val prepared = assertIs<PersistentMemoryRetentionMultiTargetPrepareResult.Prepared>(
            parentJournal.prepare(plan("crash-after-child", targets()))
        ).snapshot
        val target = checkNotNull(prepared.currentTarget)
        val child = assertIs<PersistentMemoryRetentionTransactionPrepareResult.Prepared>(
            childJournal.prepare(
                MemoryRetentionTransactionPlan(
                    prepared.childTransactionId(),
                    listOf(target),
                    prepared.plan.createdAt
                )
            )
        ).snapshot
        val authorized = assertIs<PersistentMemoryRetentionTransactionTransitionResult.Committed>(
            childJournal.transition(
                child.reference,
                MemoryRetentionTransactionState.PLANNED,
                MemoryRetentionTransactionState.AUTHORIZED
            )
        ).snapshot
        assertIs<PersistentMemoryRetentionTransactionTransitionResult.Committed>(
            childJournal.transition(
                authorized.reference,
                MemoryRetentionTransactionState.AUTHORIZED,
                MemoryRetentionTransactionState.COMMITTED
            )
        )

        val execution = RecordingExecutionPort()
        val coordinator = MemoryRetentionMultiTargetCoordinator(
            openParent(parentBackend),
            openChild(childBackend),
            execution
        )
        val advanced = assertIs<MemoryRetentionMultiTargetExecutionResult.Advanced>(
            coordinator.executeNext(prepared.reference, principal)
        )

        assertEquals(1, advanced.snapshot.nextIndex)
        assertTrue(execution.executed.isEmpty())
        assertTrue(execution.recovered.isEmpty())
    }

    @Test
    fun denial_and_recovery_required_do_not_advance_parent_progress() {
        val deniedFixture = fixture("denied", RecordingExecutionPort(
            executeResult = MemoryRetentionTransactionExecutionResult.Denied("no grant")
        ))
        assertIs<MemoryRetentionMultiTargetExecutionResult.Denied>(
            deniedFixture.coordinator.executeNext(deniedFixture.reference, principal)
        )
        assertEquals(0, checkNotNull(deniedFixture.parent.inspect(deniedFixture.reference.id)).nextIndex)

        val recoveryFixture = fixture("recovery", RecordingExecutionPort(
            executeResult = MemoryRetentionTransactionExecutionResult.RecoveryRequired("unknown mutation")
        ))
        assertIs<MemoryRetentionMultiTargetExecutionResult.RecoveryRequired>(
            recoveryFixture.coordinator.executeNext(recoveryFixture.reference, principal)
        )
        assertEquals(0, checkNotNull(recoveryFixture.parent.inspect(recoveryFixture.reference.id)).nextIndex)
    }

    @Test
    fun rejected_child_result_rejects_parent_without_rolling_back_prior_progress() {
        val parentBackend = InMemoryPersistentRecordBackend()
        val childBackend = InMemoryPersistentRecordBackend()
        val parent = openParent(parentBackend)
        val child = openChild(childBackend)
        val prepared = assertIs<PersistentMemoryRetentionMultiTargetPrepareResult.Prepared>(
            parent.prepare(plan("partial", targets()))
        ).snapshot
        assertIs<PersistentMemoryRetentionMultiTargetProgressResult.Committed>(
            parent.advance(prepared.reference, 0)
        )
        val execution = RecordingExecutionPort(
            executeResult = MemoryRetentionTransactionExecutionResult.Rejected("stale target")
        )
        val coordinator = MemoryRetentionMultiTargetCoordinator(parent, child, execution)

        val rejected = assertIs<MemoryRetentionMultiTargetExecutionResult.Rejected>(
            coordinator.executeNext(prepared.reference, principal)
        )
        assertEquals(1, rejected.snapshot?.nextIndex)
        assertEquals(MemoryRetentionMultiTargetState.REJECTED, rejected.snapshot?.state)
    }

    @Test
    fun conflicting_deterministic_child_identity_fails_closed() {
        val parentBackend = InMemoryPersistentRecordBackend()
        val childBackend = InMemoryPersistentRecordBackend()
        val parent = openParent(parentBackend)
        val child = openChild(childBackend)
        val prepared = assertIs<PersistentMemoryRetentionMultiTargetPrepareResult.Prepared>(
            parent.prepare(plan("conflict", targets()))
        ).snapshot
        val wrongTarget = target("wrong", 99, MemoryRetentionClass.SEMANTIC)
        assertIs<PersistentMemoryRetentionTransactionPrepareResult.Prepared>(
            child.prepare(
                MemoryRetentionTransactionPlan(
                    prepared.childTransactionId(),
                    listOf(wrongTarget),
                    prepared.plan.createdAt
                )
            )
        )

        val coordinator = MemoryRetentionMultiTargetCoordinator(
            parent,
            child,
            RecordingExecutionPort()
        )
        val rejected = assertIs<MemoryRetentionMultiTargetExecutionResult.Rejected>(
            coordinator.executeNext(prepared.reference, principal)
        )
        assertEquals(MemoryRetentionMultiTargetState.REJECTED, rejected.snapshot?.state)
    }

    private data class Fixture(
        val parent: PersistentMemoryRetentionMultiTargetJournal,
        val coordinator: MemoryRetentionMultiTargetCoordinator,
        val reference: MemoryRetentionMultiTargetReference
    )

    private fun fixture(
        id: String,
        execution: MemoryRetentionSingleTargetExecutionPort
    ): Fixture {
        val parent = openParent(InMemoryPersistentRecordBackend())
        val child = openChild(InMemoryPersistentRecordBackend())
        val prepared = assertIs<PersistentMemoryRetentionMultiTargetPrepareResult.Prepared>(
            parent.prepare(plan(id, targets()))
        ).snapshot
        return Fixture(
            parent,
            MemoryRetentionMultiTargetCoordinator(parent, child, execution),
            prepared.reference
        )
    }

    private fun targets(): List<MemoryRetentionTransactionTarget> = listOf(
        target("z-last", 30, MemoryRetentionClass.SEMANTIC),
        target("a-first", 10, MemoryRetentionClass.WORKING),
        target("m-middle", 20, MemoryRetentionClass.EPISODIC)
    )

    private fun plan(
        id: String,
        targets: List<MemoryRetentionTransactionTarget>
    ): MemoryRetentionMultiTargetPlan = MemoryRetentionMultiTargetPlan(
        MemoryRetentionMultiTargetId(id),
        targets,
        createdAt
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

    private fun openParent(
        backend: InMemoryPersistentRecordBackend
    ): PersistentMemoryRetentionMultiTargetJournal =
        assertIs<PersistentMemoryRetentionMultiTargetJournalOpenResult.Opened>(
            PersistentMemoryRetentionMultiTargetJournal.open(
                foundation(),
                PersistentStoreId("retention-multi-target"),
                backend
            )
        ).journal

    private fun openChild(
        backend: InMemoryPersistentRecordBackend
    ): PersistentMemoryRetentionTransactionJournal =
        assertIs<PersistentMemoryRetentionTransactionJournalOpenResult.Opened>(
            PersistentMemoryRetentionTransactionJournal.open(
                foundation(),
                PersistentStoreId("retention-child-transactions"),
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
                "retention-multi-target-${sequence.incrementAndGet()}"
            }
        )
    }

    private open class RecordingExecutionPort(
        private val executeResult: MemoryRetentionTransactionExecutionResult =
            MemoryRetentionTransactionExecutionResult.Denied("not configured"),
        private val recoverResult: MemoryRetentionTransactionExecutionResult = executeResult
    ) : MemoryRetentionSingleTargetExecutionPort {
        val executed = mutableListOf<MemoryRetentionTransactionReference>()
        val recovered = mutableListOf<MemoryRetentionTransactionReference>()

        override fun execute(
            reference: MemoryRetentionTransactionReference,
            principal: AuthorityPrincipal
        ): MemoryRetentionTransactionExecutionResult {
            executed += reference
            return executeResult
        }

        override fun recover(
            reference: MemoryRetentionTransactionReference,
            principal: AuthorityPrincipal
        ): MemoryRetentionTransactionExecutionResult {
            recovered += reference
            return recoverResult
        }
    }

    private class CommittingExecutionPort(
        private val childJournal: PersistentMemoryRetentionTransactionJournal
    ) : MemoryRetentionSingleTargetExecutionPort {
        val executed = mutableListOf<MemoryRetentionTransactionReference>()

        override fun execute(
            reference: MemoryRetentionTransactionReference,
            principal: AuthorityPrincipal
        ): MemoryRetentionTransactionExecutionResult {
            executed += reference
            val authorized = assertIs<PersistentMemoryRetentionTransactionTransitionResult.Committed>(
                childJournal.transition(
                    reference,
                    MemoryRetentionTransactionState.PLANNED,
                    MemoryRetentionTransactionState.AUTHORIZED
                )
            ).snapshot
            val committed = assertIs<PersistentMemoryRetentionTransactionTransitionResult.Committed>(
                childJournal.transition(
                    authorized.reference,
                    MemoryRetentionTransactionState.AUTHORIZED,
                    MemoryRetentionTransactionState.COMMITTED
                )
            ).snapshot
            return MemoryRetentionTransactionExecutionResult.Committed(committed)
        }

        override fun recover(
            reference: MemoryRetentionTransactionReference,
            principal: AuthorityPrincipal
        ): MemoryRetentionTransactionExecutionResult =
            error("recovery was not expected in ordered execution contract")
    }
}
