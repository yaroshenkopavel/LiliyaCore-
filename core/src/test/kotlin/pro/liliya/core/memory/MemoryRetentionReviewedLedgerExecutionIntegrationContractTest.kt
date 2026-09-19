package pro.liliya.core.memory

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityOwnershipResult
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.authority.DirectAuthorityGrantOwnershipResult
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentStoreId

class MemoryRetentionReviewedLedgerExecutionIntegrationContractTest {
    private val principal = AuthorityPrincipal("retention-reviewed-ledger-integration")
    private val baseTime = Instant.parse("2026-09-17T15:00:00Z")

    @Test
    fun single_prune_prepare_is_non_mutating_and_explicit_execution_commits_exact_target() {
        val memoryBackend = InMemoryPersistentRecordBackend()
        val parentBackend = InMemoryPersistentRecordBackend()
        val childBackend = InMemoryPersistentRecordBackend()
        val memory = openMemory(memoryBackend, "single-memory")
        val old = remember(memory, "old", "old-content", baseTime.minusSeconds(20))
        val newest = remember(memory, "new", "new-content", baseTime.minusSeconds(10))
        val ledger = shadowLedger(listOf(old, newest))
        val parent = openParent(parentBackend, "single-parent")
        val prepared = assertIs<MemoryRetentionDurablePreparationResult.Prepared>(
            MemoryRetentionDurablePlanPreparer(
                MemoryRetentionLedgerExecutionPlanBuilder(),
                parent
            ).prepare(
                MemoryRetentionExecutionPlanId("single-reviewed"),
                ledger,
                baseTime
            )
        ).snapshot

        assertEquals(1, prepared.plan.targets.size)
        assertEquals(old.record.id, prepared.plan.targets.single().recordId)
        assertTrue(memory.contains(old.record.id))
        assertTrue(memory.contains(newest.record.id))

        val reopenedMemory = openMemory(memoryBackend, "single-memory")
        val reopenedParent = openParent(parentBackend, "single-parent")
        val coordinator = coordinator(
            reopenedParent,
            openChild(childBackend, "single-child"),
            reopenedMemory
        )
        val completed = assertIs<MemoryRetentionMultiTargetExecutionResult.Completed>(
            coordinator.executeNext(prepared.reference, principal)
        )

        assertEquals(MemoryRetentionMultiTargetState.COMPLETED, completed.snapshot.state)
        assertFalse(reopenedMemory.contains(old.record.id))
        assertTrue(reopenedMemory.contains(newest.record.id))
    }

    @Test
    fun multiple_prunes_require_separate_explicit_execution_calls_and_preserve_retained_record() {
        val memoryBackend = InMemoryPersistentRecordBackend()
        val parentBackend = InMemoryPersistentRecordBackend()
        val childBackend = InMemoryPersistentRecordBackend()
        val memory = openMemory(memoryBackend, "multi-memory")
        val oldest = remember(memory, "a-oldest", "oldest-content", baseTime.minusSeconds(30))
        val middle = remember(memory, "b-middle", "middle-content", baseTime.minusSeconds(20))
        val newest = remember(memory, "c-newest", "newest-content", baseTime.minusSeconds(10))
        val ledger = shadowLedger(listOf(oldest, middle, newest))
        val parent = openParent(parentBackend, "multi-parent")
        val prepared = assertIs<MemoryRetentionDurablePreparationResult.Prepared>(
            MemoryRetentionDurablePlanPreparer(
                MemoryRetentionLedgerExecutionPlanBuilder(),
                parent
            ).prepare(
                MemoryRetentionExecutionPlanId("multi-reviewed"),
                ledger,
                baseTime
            )
        ).snapshot

        assertEquals(
            listOf(oldest.record.id, middle.record.id),
            prepared.plan.targets.map { it.recordId }
        )
        assertTrue(memory.contains(oldest.record.id))
        assertTrue(memory.contains(middle.record.id))
        assertTrue(memory.contains(newest.record.id))

        val coordinator = coordinator(
            parent,
            openChild(childBackend, "multi-child"),
            memory
        )
        val first = assertIs<MemoryRetentionMultiTargetExecutionResult.Advanced>(
            coordinator.executeNext(prepared.reference, principal)
        )
        assertEquals(1, first.snapshot.nextIndex)
        assertFalse(memory.contains(oldest.record.id))
        assertTrue(memory.contains(middle.record.id))
        assertTrue(memory.contains(newest.record.id))

        val completed = assertIs<MemoryRetentionMultiTargetExecutionResult.Completed>(
            coordinator.executeNext(prepared.reference, principal)
        )
        assertEquals(2, completed.snapshot.nextIndex)
        assertFalse(memory.contains(oldest.record.id))
        assertFalse(memory.contains(middle.record.id))
        assertTrue(memory.contains(newest.record.id))
    }

    private fun shadowLedger(snapshots: List<MemoryRecordSnapshot>): MemoryRetentionLedger {
        val policy = MemoryRetentionPolicy(
            mapOf(
                MemoryRetentionClass.WORKING to MemoryRetentionBudget(100, 100_000),
                MemoryRetentionClass.EPISODIC to MemoryRetentionBudget(1, 100_000),
                MemoryRetentionClass.SEMANTIC to MemoryRetentionBudget(100, 100_000)
            )
        )
        return MemoryRetentionShadowConsolidator(MemoryRetentionPlanner(policy))
            .simulate(snapshots.map { MemoryRetentionCandidate(it, MemoryRetentionClass.EPISODIC) })
            .ledger
    }

    private fun coordinator(
        parent: PersistentMemoryRetentionMultiTargetJournal,
        child: PersistentMemoryRetentionTransactionJournal,
        memory: PersistentMemoryComposition
    ): MemoryRetentionMultiTargetCoordinator = MemoryRetentionMultiTargetCoordinator.create(
        journal = parent,
        childJournal = child,
        executor = MemoryRetentionTransactionExecutor.persistent(
            journal = child,
            authorizer = MemoryRetentionAuthorizer(configuredAuthority()),
            memory = memory
        )
    )

    private fun remember(
        memory: PersistentMemoryComposition,
        id: String,
        content: String,
        createdAt: Instant
    ): MemoryRecordSnapshot {
        val remembered = assertIs<PersistentMemoryRememberResult.Remembered>(
            memory.remember(
                MemoryRecord(
                    id = MemoryRecordId(id),
                    sourceId = MemorySourceId("retention-reviewed-ledger-integration"),
                    content = content,
                    createdAt = createdAt
                )
            )
        )
        return checkNotNull(memory.inspect(remembered.ownership.record.id))
    }

    private fun openMemory(
        backend: InMemoryPersistentRecordBackend,
        store: String
    ): PersistentMemoryComposition = assertIs<PersistentMemoryOpenResult.Opened>(
        PersistentMemoryComposition.open(foundation(), PersistentStoreId(store), backend)
    ).composition

    private fun openParent(
        backend: InMemoryPersistentRecordBackend,
        store: String
    ): PersistentMemoryRetentionMultiTargetJournal =
        assertIs<PersistentMemoryRetentionMultiTargetJournalOpenResult.Opened>(
            PersistentMemoryRetentionMultiTargetJournal.open(
                foundation(),
                PersistentStoreId(store),
                backend
            )
        ).journal

    private fun openChild(
        backend: InMemoryPersistentRecordBackend,
        store: String
    ): PersistentMemoryRetentionTransactionJournal =
        assertIs<PersistentMemoryRetentionTransactionJournalOpenResult.Opened>(
            PersistentMemoryRetentionTransactionJournal.open(
                foundation(),
                PersistentStoreId(store),
                backend
            )
        ).journal

    private fun configuredAuthority(): CapabilityAuthorityComposition {
        val authority = CapabilityAuthorityComposition(foundation())
        assertIs<CapabilityOwnershipResult.Registered>(
            authority.registerCapability(
                CapabilityDescriptor(
                    id = MemoryRetentionAuthorityContract.capability,
                    providerId = CapabilityProviderId("retention-reviewed-ledger-integration")
                )
            )
        )
        assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            authority.registerDirectGrant(
                DirectAuthorityGrant(
                    principal = principal,
                    capability = MemoryRetentionAuthorityContract.capability,
                    scope = MemoryRetentionAuthorityContract.scopeFor(MemoryRetentionClass.EPISODIC)
                )
            )
        )
        return authority
    }

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
            correlationIds = CorrelationIdGenerator {
                "retention-reviewed-ledger-integration-${sequence.incrementAndGet()}"
            }
        )
    }
}
