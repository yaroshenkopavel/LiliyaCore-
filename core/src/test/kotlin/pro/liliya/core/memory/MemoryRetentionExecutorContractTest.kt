package pro.liliya.core.memory

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

class MemoryRetentionExecutorContractTest {
    private val principal = AuthorityPrincipal("memory-retention-executor")

    @Test
    fun keep_entry_never_requires_authority_or_mutates_memory() {
        val target = FakeTarget()
        val executor = MemoryRetentionExecutor(
            authorizer = MemoryRetentionAuthorizer(CapabilityAuthorityComposition(foundation())),
            target = target
        )

        val result = executor.execute(
            ledgerEntry(
                snapshot("keep", 1),
                MemoryRetentionClass.WORKING,
                MemoryRetentionDisposition.RETAINED,
                MemoryRetentionShadowAction.KEEP
            ),
            principal
        )

        assertIs<MemoryRetentionExecutionResult.Kept>(result)
        assertEquals(0, target.inspectCount)
        assertEquals(0, target.removeCount)
    }

    @Test
    fun wrong_retention_scope_is_denied_before_exact_mutation() {
        val current = snapshot("episodic", 2)
        val target = FakeTarget(current)
        val (authority, _) = configuredAuthority(MemoryRetentionClass.WORKING)
        val executor = MemoryRetentionExecutor(MemoryRetentionAuthorizer(authority), target)

        val result = executor.execute(
            pruneEntry(current, MemoryRetentionClass.EPISODIC),
            principal
        )

        assertIs<MemoryRetentionExecutionResult.Denied>(result)
        assertEquals(1, target.inspectCount)
        assertEquals(0, target.removeCount)
        assertTrue(target.contains(current.record.id))
    }

    @Test
    fun stale_ledger_generation_is_rejected_even_with_valid_authority() {
        val current = snapshot("stale", 8)
        val stale = snapshot("stale", 7)
        val target = FakeTarget(current)
        val (authority, _) = configuredAuthority(MemoryRetentionClass.SEMANTIC)
        val executor = MemoryRetentionExecutor(MemoryRetentionAuthorizer(authority), target)

        val result = executor.execute(
            pruneEntry(stale, MemoryRetentionClass.SEMANTIC),
            principal
        )

        assertIs<MemoryRetentionExecutionResult.Stale>(result)
        assertEquals(0, target.removeCount)
        assertTrue(target.contains(current.record.id))
    }

    @Test
    fun exact_scope_prunes_only_the_exact_live_generation() {
        val current = snapshot("exact", 11)
        val target = FakeTarget(current)
        val (authority, _) = configuredAuthority(MemoryRetentionClass.EPISODIC)
        val executor = MemoryRetentionExecutor(MemoryRetentionAuthorizer(authority), target)

        val result = executor.execute(
            pruneEntry(current, MemoryRetentionClass.EPISODIC),
            principal
        )

        assertIs<MemoryRetentionExecutionResult.Pruned>(result)
        assertEquals(1, target.removeCount)
        assertFalse(target.contains(current.record.id))
    }

    @Test
    fun authority_is_rechecked_for_every_prune_mutation() {
        val first = snapshot("first", 21)
        val second = snapshot("second", 22)
        val target = FakeTarget(first, second)
        val (authority, grantOwnership) = configuredAuthority(MemoryRetentionClass.EPISODIC)
        val executor = MemoryRetentionExecutor(MemoryRetentionAuthorizer(authority), target)

        assertIs<MemoryRetentionExecutionResult.Pruned>(
            executor.execute(pruneEntry(first, MemoryRetentionClass.EPISODIC), principal)
        )
        assertTrue(grantOwnership.revoke())

        val denied = executor.execute(
            pruneEntry(second, MemoryRetentionClass.EPISODIC),
            principal
        )

        assertIs<MemoryRetentionExecutionResult.Denied>(denied)
        assertEquals(1, target.removeCount)
        assertTrue(target.contains(second.record.id))
    }

    private fun configuredAuthority(
        retentionClass: MemoryRetentionClass
    ): Pair<CapabilityAuthorityComposition, DirectAuthorityGrantOwnership> {
        val authority = CapabilityAuthorityComposition(foundation())
        assertIs<CapabilityOwnershipResult.Registered>(
            authority.registerCapability(
                CapabilityDescriptor(
                    id = MemoryRetentionAuthorityContract.capability,
                    providerId = CapabilityProviderId("memory-retention-executor")
                )
            )
        )
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

    private fun pruneEntry(
        snapshot: MemoryRecordSnapshot,
        retentionClass: MemoryRetentionClass
    ): MemoryRetentionLedgerEntry = ledgerEntry(
        snapshot = snapshot,
        retentionClass = retentionClass,
        disposition = MemoryRetentionDisposition.RECORD_BUDGET_REJECTED,
        action = MemoryRetentionShadowAction.PRUNE_CANDIDATE
    )

    private fun ledgerEntry(
        snapshot: MemoryRecordSnapshot,
        retentionClass: MemoryRetentionClass,
        disposition: MemoryRetentionDisposition,
        action: MemoryRetentionShadowAction
    ): MemoryRetentionLedgerEntry = MemoryRetentionLedgerEntry(
        recordId = snapshot.record.id,
        generation = snapshot.generation,
        retentionClass = retentionClass,
        disposition = disposition,
        action = action
    )

    private fun snapshot(id: String, generation: Long): MemoryRecordSnapshot = MemoryRecordSnapshot(
        record = MemoryRecord(
            id = MemoryRecordId(id),
            sourceId = MemorySourceId("retention-executor-contract"),
            content = "private-$id-content",
            createdAt = Instant.parse("2026-09-01T10:00:00Z")
        ),
        generation = MemoryGeneration(generation)
    )

    private fun foundation(): FoundationComposition {
        val logs = InMemoryLogWriter()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "memory-retention-executor-contract" }
        )
    }

    private class FakeTarget(
        vararg initial: MemoryRecordSnapshot
    ) : MemoryRetentionExecutionTarget {
        private val live = initial.associateBy { it.record.id }.toMutableMap()
        var inspectCount: Int = 0
            private set
        var removeCount: Int = 0
            private set

        override fun inspect(id: MemoryRecordId): MemoryRecordSnapshot? {
            inspectCount += 1
            return live[id]
        }

        override fun removeExact(snapshot: MemoryRecordSnapshot): PersistentMemoryMutationResult {
            removeCount += 1
            val current = live[snapshot.record.id]
                ?: return PersistentMemoryMutationResult.Rejected("memory is not live")
            if (current.generation != snapshot.generation) {
                return PersistentMemoryMutationResult.Rejected("memory generation changed")
            }
            live.remove(snapshot.record.id)
            return PersistentMemoryMutationResult.Committed
        }

        fun contains(id: MemoryRecordId): Boolean = live.containsKey(id)
    }
}
