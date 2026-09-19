package pro.liliya.core.memory

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityOwnershipResult
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.authority.DirectAuthorityGrantOwnership
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

class MemoryRetentionTransactionExecutionRecoveryContractTest {
    private val principal = AuthorityPrincipal("retention-transaction-executor")
    private val createdAt = Instant.parse("2026-09-17T11:00:00Z")

    @Test
    fun fresh_execution_persists_authorized_before_exact_mutation_and_commits() {
        val backend = InMemoryPersistentRecordBackend()
        val journal = openJournal(backend)
        val prepared = prepare(journal, "tx-fresh", "memory-fresh", 11)
        val (authority, _) = configuredAuthority(MemoryRetentionClass.EPISODIC)
        val mutation = FakeMutationPort()
        val observation = FakeObservationPort(MemoryRetentionExactTargetObservation.EXACT)
        val executor = MemoryRetentionTransactionExecutor(
            journal,
            MemoryRetentionAuthorizer(authority),
            mutation,
            observation
        )

        val result = executor.execute(prepared.reference, principal)

        val committed = assertIs<MemoryRetentionTransactionExecutionResult.Committed>(result)
        assertEquals(MemoryRetentionTransactionState.COMMITTED, committed.snapshot.state)
        assertEquals(1, mutation.removeCount)
        assertEquals(MemoryRecordId("memory-fresh"), mutation.lastRecordId)
        assertEquals(MemoryGeneration(11), mutation.lastGeneration)
        assertEquals(0, observation.observeCount)
    }

    @Test
    fun restart_does_not_reuse_precrash_authorized_state_as_authority() {
        val backend = InMemoryPersistentRecordBackend()
        val journal = openJournal(backend)
        val prepared = prepare(journal, "tx-no-replay", "memory-no-replay", 21)
        authorizeDurably(journal, prepared)

        val reopened = openJournal(backend)
        assertEquals(
            MemoryRetentionTransactionState.RECOVERY_REQUIRED,
            reopened.inspect(prepared.plan.id)?.state
        )
        val authorityWithoutGrant = registeredAuthorityWithoutGrant()
        val mutation = FakeMutationPort()
        val executor = MemoryRetentionTransactionExecutor(
            reopened,
            MemoryRetentionAuthorizer(authorityWithoutGrant),
            mutation,
            FakeObservationPort(MemoryRetentionExactTargetObservation.EXACT)
        )

        val result = executor.recover(prepared.reference, principal)

        assertIs<MemoryRetentionTransactionExecutionResult.Denied>(result)
        assertEquals(0, mutation.removeCount)
        assertEquals(
            MemoryRetentionTransactionState.RECOVERY_REQUIRED,
            reopened.inspect(prepared.plan.id)?.state
        )
    }

    @Test
    fun fresh_authority_after_restart_allows_exact_retry() {
        val backend = InMemoryPersistentRecordBackend()
        val journal = openJournal(backend)
        val prepared = prepare(journal, "tx-retry", "memory-retry", 31)
        authorizeDurably(journal, prepared)
        val reopened = openJournal(backend)
        val (authority, _) = configuredAuthority(MemoryRetentionClass.EPISODIC)
        val mutation = FakeMutationPort()
        val executor = MemoryRetentionTransactionExecutor(
            reopened,
            MemoryRetentionAuthorizer(authority),
            mutation,
            FakeObservationPort(MemoryRetentionExactTargetObservation.EXACT)
        )

        val result = executor.recover(prepared.reference, principal)

        assertIs<MemoryRetentionTransactionExecutionResult.Committed>(result)
        assertEquals(1, mutation.removeCount)
        assertEquals(
            MemoryRetentionTransactionState.COMMITTED,
            reopened.inspect(prepared.plan.id)?.state
        )
    }

    @Test
    fun post_mutation_crash_resolves_absent_target_without_second_mutation_or_authority() {
        val backend = InMemoryPersistentRecordBackend()
        val journal = openJournal(backend)
        val prepared = prepare(journal, "tx-post-mutation", "memory-gone", 41)
        authorizeDurably(journal, prepared)
        val reopened = openJournal(backend)
        val mutation = FakeMutationPort()
        val executor = MemoryRetentionTransactionExecutor(
            reopened,
            MemoryRetentionAuthorizer(CapabilityAuthorityComposition(foundation())),
            mutation,
            FakeObservationPort(MemoryRetentionExactTargetObservation.MISSING)
        )

        val result = executor.recover(prepared.reference, principal)

        val resolved = assertIs<MemoryRetentionTransactionExecutionResult.ResolvedAbsent>(result)
        assertEquals(MemoryRetentionTransactionState.COMMITTED, resolved.snapshot.state)
        assertEquals(0, mutation.removeCount)
        assertEquals(
            MemoryRetentionTransactionState.COMMITTED,
            reopened.inspect(prepared.plan.id)?.state
        )
    }

    @Test
    fun reused_record_id_with_new_generation_is_rejected_without_mutation() {
        val backend = InMemoryPersistentRecordBackend()
        val journal = openJournal(backend)
        val prepared = prepare(journal, "tx-reused", "memory-reused", 51)
        authorizeDurably(journal, prepared)
        val reopened = openJournal(backend)
        val mutation = FakeMutationPort()
        val executor = MemoryRetentionTransactionExecutor(
            reopened,
            MemoryRetentionAuthorizer(CapabilityAuthorityComposition(foundation())),
            mutation,
            FakeObservationPort(MemoryRetentionExactTargetObservation.GENERATION_CHANGED)
        )

        val result = executor.recover(prepared.reference, principal)

        assertIs<MemoryRetentionTransactionExecutionResult.Stale>(result)
        assertEquals(0, mutation.removeCount)
        assertEquals(
            MemoryRetentionTransactionState.REJECTED,
            reopened.inspect(prepared.plan.id)?.state
        )
    }

    @Test
    fun unknown_mutation_failure_moves_transaction_to_recovery_required() {
        val backend = InMemoryPersistentRecordBackend()
        val journal = openJournal(backend)
        val prepared = prepare(journal, "tx-failure", "memory-failure", 61)
        val (authority, _) = configuredAuthority(MemoryRetentionClass.EPISODIC)
        val mutation = FakeMutationPort(
            PersistentMemoryMutationResult.Failed("storage outcome unknown")
        )
        val executor = MemoryRetentionTransactionExecutor(
            journal,
            MemoryRetentionAuthorizer(authority),
            mutation,
            FakeObservationPort(MemoryRetentionExactTargetObservation.EXACT)
        )

        val result = executor.execute(prepared.reference, principal)

        assertIs<MemoryRetentionTransactionExecutionResult.RecoveryRequired>(result)
        assertEquals(1, mutation.removeCount)
        assertEquals(
            MemoryRetentionTransactionState.RECOVERY_REQUIRED,
            journal.inspect(prepared.plan.id)?.state
        )
    }

    @Test
    fun revoked_authority_after_first_attempt_must_be_granted_again_for_recovery() {
        val backend = InMemoryPersistentRecordBackend()
        val journal = openJournal(backend)
        val prepared = prepare(journal, "tx-regrant", "memory-regrant", 71)
        authorizeDurably(journal, prepared)
        val reopened = openJournal(backend)
        val (authority, grant) = configuredAuthority(MemoryRetentionClass.EPISODIC)
        assertTrue(grant.revoke())
        val mutation = FakeMutationPort()
        val executor = MemoryRetentionTransactionExecutor(
            reopened,
            MemoryRetentionAuthorizer(authority),
            mutation,
            FakeObservationPort(MemoryRetentionExactTargetObservation.EXACT)
        )

        assertIs<MemoryRetentionTransactionExecutionResult.Denied>(
            executor.recover(prepared.reference, principal)
        )
        assertEquals(0, mutation.removeCount)
    }

    private fun prepare(
        journal: PersistentMemoryRetentionTransactionJournal,
        transactionId: String,
        recordId: String,
        generation: Long
    ): MemoryRetentionTransactionSnapshot {
        val plan = MemoryRetentionTransactionPlan(
            id = MemoryRetentionTransactionId(transactionId),
            targets = listOf(
                MemoryRetentionTransactionTarget(
                    recordId = MemoryRecordId(recordId),
                    generation = MemoryGeneration(generation),
                    retentionClass = MemoryRetentionClass.EPISODIC,
                    disposition = MemoryRetentionDisposition.RECORD_BUDGET_REJECTED
                )
            ),
            createdAt = createdAt
        )
        return assertIs<PersistentMemoryRetentionTransactionPrepareResult.Prepared>(
            journal.prepare(plan)
        ).snapshot
    }

    private fun authorizeDurably(
        journal: PersistentMemoryRetentionTransactionJournal,
        prepared: MemoryRetentionTransactionSnapshot
    ) {
        assertIs<PersistentMemoryRetentionTransactionTransitionResult.Committed>(
            journal.transition(
                prepared.reference,
                MemoryRetentionTransactionState.PLANNED,
                MemoryRetentionTransactionState.AUTHORIZED
            )
        )
    }

    private fun openJournal(
        backend: InMemoryPersistentRecordBackend
    ): PersistentMemoryRetentionTransactionJournal =
        assertIs<PersistentMemoryRetentionTransactionJournalOpenResult.Opened>(
            PersistentMemoryRetentionTransactionJournal.open(
                foundation(),
                PersistentStoreId("retention-transaction-execution"),
                backend
            )
        ).journal

    private fun registeredAuthorityWithoutGrant(): CapabilityAuthorityComposition {
        val authority = CapabilityAuthorityComposition(foundation())
        assertIs<CapabilityOwnershipResult.Registered>(
            authority.registerCapability(
                CapabilityDescriptor(
                    id = MemoryRetentionAuthorityContract.capability,
                    providerId = CapabilityProviderId("retention-transaction-execution")
                )
            )
        )
        return authority
    }

    private fun configuredAuthority(
        retentionClass: MemoryRetentionClass
    ): Pair<CapabilityAuthorityComposition, DirectAuthorityGrantOwnership> {
        val authority = registeredAuthorityWithoutGrant()
        val grant = assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            authority.registerDirectGrant(
                DirectAuthorityGrant(
                    principal = principal,
                    capability = MemoryRetentionAuthorityContract.capability,
                    scope = MemoryRetentionAuthorityContract.scopeFor(retentionClass)
                )
            )
        )
        return authority to grant.ownership
    }

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        val logs = InMemoryLogWriter()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator {
                "retention-transaction-execution-${sequence.incrementAndGet()}"
            }
        )
    }

    private class FakeMutationPort(
        private val result: PersistentMemoryMutationResult = PersistentMemoryMutationResult.Committed
    ) : MemoryRetentionMutationPort {
        var removeCount: Int = 0
            private set
        var lastRecordId: MemoryRecordId? = null
            private set
        var lastGeneration: MemoryGeneration? = null
            private set

        override fun removeExact(
            recordId: MemoryRecordId,
            generation: MemoryGeneration
        ): PersistentMemoryMutationResult {
            removeCount += 1
            lastRecordId = recordId
            lastGeneration = generation
            return result
        }
    }

    private class FakeObservationPort(
        private val observation: MemoryRetentionExactTargetObservation
    ) : MemoryRetentionExactTargetObservationPort {
        var observeCount: Int = 0
            private set

        override fun observe(
            recordId: MemoryRecordId,
            generation: MemoryGeneration
        ): MemoryRetentionExactTargetObservation {
            observeCount += 1
            return observation
        }
    }
}
