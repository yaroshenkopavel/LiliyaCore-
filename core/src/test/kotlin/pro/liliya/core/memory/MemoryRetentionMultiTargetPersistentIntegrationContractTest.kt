package pro.liliya.core.memory

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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

class MemoryRetentionMultiTargetPersistentIntegrationContractTest {
    private val principal = AuthorityPrincipal("retention-multi-target-integration")
    private val createdAt = Instant.parse("2026-09-17T12:00:00Z")

    @Test
    fun restart_requires_fresh_authority_then_continues_real_persistent_progress() {
        val memoryBackend = InMemoryPersistentRecordBackend()
        val parentBackend = InMemoryPersistentRecordBackend()
        val childBackend = InMemoryPersistentRecordBackend()

        val memory = openMemory(memoryBackend)
        val first = remember(memory, "a-first", "first")
        val second = remember(memory, "b-second", "second")
        val parent = openParent(parentBackend)
        val prepared = prepareParent(parent, "real-restart", first, second)
        val child = openChild(childBackend)
        val authority = configuredAuthority()
        val coordinator = realCoordinator(parent, child, memory, authority)

        memoryBackend.failNextCommit()
        assertIs<MemoryRetentionMultiTargetExecutionResult.RecoveryRequired>(
            coordinator.executeNext(prepared.reference, principal)
        )
        assertEquals(0, checkNotNull(parent.inspect(prepared.plan.id)).nextIndex)
        assertTrue(memory.contains(first.record.id))

        val reopenedMemory = openMemory(memoryBackend)
        val reopenedParent = openParent(parentBackend)
        val reopenedChild = openChild(childBackend)
        val deniedCoordinator = realCoordinator(
            reopenedParent,
            reopenedChild,
            reopenedMemory,
            registeredAuthorityWithoutGrant()
        )
        assertIs<MemoryRetentionMultiTargetExecutionResult.Denied>(
            deniedCoordinator.executeNext(prepared.reference, principal)
        )
        assertTrue(reopenedMemory.contains(first.record.id))
        assertEquals(0, checkNotNull(reopenedParent.inspect(prepared.plan.id)).nextIndex)

        val recoveredCoordinator = realCoordinator(
            reopenedParent,
            reopenedChild,
            reopenedMemory,
            configuredAuthority()
        )
        val advanced = assertIs<MemoryRetentionMultiTargetExecutionResult.Advanced>(
            recoveredCoordinator.executeNext(prepared.reference, principal)
        )
        assertEquals(1, advanced.snapshot.nextIndex)
        assertFalse(reopenedMemory.contains(first.record.id))
        assertTrue(reopenedMemory.contains(second.record.id))

        val completed = assertIs<MemoryRetentionMultiTargetExecutionResult.Completed>(
            recoveredCoordinator.executeNext(prepared.reference, principal)
        )
        assertEquals(2, completed.snapshot.nextIndex)
        assertEquals(MemoryRetentionMultiTargetState.COMPLETED, completed.snapshot.state)
        assertFalse(reopenedMemory.contains(second.record.id))

        val finalMemory = openMemory(memoryBackend)
        val finalParent = openParent(parentBackend)
        assertFalse(finalMemory.contains(first.record.id))
        assertFalse(finalMemory.contains(second.record.id))
        assertEquals(
            MemoryRetentionMultiTargetState.COMPLETED,
            finalParent.inspect(prepared.plan.id)?.state
        )
    }

    @Test
    fun reused_generation_after_restart_is_never_deleted_and_rejects_parent() {
        val memoryBackend = InMemoryPersistentRecordBackend()
        val parentBackend = InMemoryPersistentRecordBackend()
        val childBackend = InMemoryPersistentRecordBackend()

        val memory = openMemory(memoryBackend)
        val first = remember(memory, "a-reused", "old")
        val second = remember(memory, "b-stays", "other")
        val parent = openParent(parentBackend)
        val prepared = prepareParent(parent, "real-reuse", first, second)
        val child = openChild(childBackend)
        val coordinator = realCoordinator(parent, child, memory, configuredAuthority())

        memoryBackend.failNextCommit()
        assertIs<MemoryRetentionMultiTargetExecutionResult.RecoveryRequired>(
            coordinator.executeNext(prepared.reference, principal)
        )
        assertEquals(0, checkNotNull(parent.inspect(prepared.plan.id)).nextIndex)

        assertIs<PersistentMemoryMutationResult.Committed>(memory.removeExact(first))
        val replacementRemembered = assertIs<PersistentMemoryRememberResult.Remembered>(
            memory.remember(record("a-reused", "replacement"))
        )
        val replacement = checkNotNull(memory.inspect(replacementRemembered.ownership.record.id))
        assertNotEquals(first.generation, replacement.generation)

        val reopenedMemory = openMemory(memoryBackend)
        val reopenedParent = openParent(parentBackend)
        val reopenedChild = openChild(childBackend)
        val recoveredCoordinator = realCoordinator(
            reopenedParent,
            reopenedChild,
            reopenedMemory,
            configuredAuthority()
        )

        val rejected = assertIs<MemoryRetentionMultiTargetExecutionResult.Rejected>(
            recoveredCoordinator.executeNext(prepared.reference, principal)
        )
        assertEquals(MemoryRetentionMultiTargetState.REJECTED, rejected.snapshot?.state)
        assertEquals(0, rejected.snapshot?.nextIndex)
        assertEquals(replacement, reopenedMemory.inspect(replacement.record.id))
        assertTrue(reopenedMemory.contains(second.record.id))

        val finalMemory = openMemory(memoryBackend)
        assertEquals(replacement, finalMemory.inspect(replacement.record.id))
        assertTrue(finalMemory.contains(second.record.id))
    }

    private fun realCoordinator(
        parent: PersistentMemoryRetentionMultiTargetJournal,
        child: PersistentMemoryRetentionTransactionJournal,
        memory: PersistentMemoryComposition,
        authority: CapabilityAuthorityComposition
    ): MemoryRetentionMultiTargetCoordinator = MemoryRetentionMultiTargetCoordinator.create(
        journal = parent,
        childJournal = child,
        executor = MemoryRetentionTransactionExecutor.persistent(
            journal = child,
            authorizer = MemoryRetentionAuthorizer(authority),
            memory = memory
        )
    )

    private fun prepareParent(
        parent: PersistentMemoryRetentionMultiTargetJournal,
        id: String,
        first: MemoryRecordSnapshot,
        second: MemoryRecordSnapshot
    ): MemoryRetentionMultiTargetSnapshot =
        assertIs<PersistentMemoryRetentionMultiTargetPrepareResult.Prepared>(
            parent.prepare(
                MemoryRetentionMultiTargetPlan(
                    id = MemoryRetentionMultiTargetId(id),
                    targets = listOf(target(first), target(second)),
                    createdAt = createdAt
                )
            )
        ).snapshot

    private fun target(snapshot: MemoryRecordSnapshot): MemoryRetentionTransactionTarget =
        MemoryRetentionTransactionTarget(
            recordId = snapshot.record.id,
            generation = snapshot.generation,
            retentionClass = MemoryRetentionClass.EPISODIC,
            disposition = MemoryRetentionDisposition.RECORD_BUDGET_REJECTED
        )

    private fun remember(
        memory: PersistentMemoryComposition,
        id: String,
        content: String
    ): MemoryRecordSnapshot {
        val remembered = assertIs<PersistentMemoryRememberResult.Remembered>(
            memory.remember(record(id, content))
        )
        return checkNotNull(memory.inspect(remembered.ownership.record.id))
    }

    private fun record(id: String, content: String): MemoryRecord = MemoryRecord(
        id = MemoryRecordId(id),
        sourceId = MemorySourceId("retention-multi-target-integration"),
        content = content,
        createdAt = createdAt
    )

    private fun openMemory(
        backend: InMemoryPersistentRecordBackend
    ): PersistentMemoryComposition = assertIs<PersistentMemoryOpenResult.Opened>(
        PersistentMemoryComposition.open(
            foundation(),
            PersistentStoreId("retention-integration-memory"),
            backend
        )
    ).composition

    private fun openParent(
        backend: InMemoryPersistentRecordBackend
    ): PersistentMemoryRetentionMultiTargetJournal =
        assertIs<PersistentMemoryRetentionMultiTargetJournalOpenResult.Opened>(
            PersistentMemoryRetentionMultiTargetJournal.open(
                foundation(),
                PersistentStoreId("retention-integration-parent"),
                backend
            )
        ).journal

    private fun openChild(
        backend: InMemoryPersistentRecordBackend
    ): PersistentMemoryRetentionTransactionJournal =
        assertIs<PersistentMemoryRetentionTransactionJournalOpenResult.Opened>(
            PersistentMemoryRetentionTransactionJournal.open(
                foundation(),
                PersistentStoreId("retention-integration-child"),
                backend
            )
        ).journal

    private fun configuredAuthority(): CapabilityAuthorityComposition {
        val authority = registeredAuthorityWithoutGrant()
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

    private fun registeredAuthorityWithoutGrant(): CapabilityAuthorityComposition {
        val authority = CapabilityAuthorityComposition(foundation())
        assertIs<CapabilityOwnershipResult.Registered>(
            authority.registerCapability(
                CapabilityDescriptor(
                    id = MemoryRetentionAuthorityContract.capability,
                    providerId = CapabilityProviderId("retention-multi-target-integration")
                )
            )
        )
        return authority
    }

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            correlationIds = CorrelationIdGenerator {
                "retention-multi-target-integration-${sequence.incrementAndGet()}"
            }
        )
    }
}
