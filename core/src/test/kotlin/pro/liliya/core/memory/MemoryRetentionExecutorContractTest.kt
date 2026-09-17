package pro.liliya.core.memory

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

class MemoryRetentionExecutorContractTest {
    private val principal = AuthorityPrincipal("memory-retention-executor")

    @Test
    fun keep_entry_never_requires_authority_or_mutates_memory() {
        val mutationPort = FakeMutationPort()
        val executor = MemoryRetentionExecutor(
            authorizer = MemoryRetentionAuthorizer(CapabilityAuthorityComposition(foundation())),
            mutationPort = mutationPort
        )

        val result = executor.execute(
            ledgerEntry(
                id = "keep",
                generation = 1,
                retentionClass = MemoryRetentionClass.WORKING,
                disposition = MemoryRetentionDisposition.RETAINED,
                action = MemoryRetentionShadowAction.KEEP
            ),
            principal
        )

        assertIs<MemoryRetentionExecutionResult.Kept>(result)
        assertEquals(0, mutationPort.removeCount)
    }

    @Test
    fun wrong_retention_scope_is_denied_before_exact_mutation() {
        val mutationPort = FakeMutationPort()
        val (authority, _) = configuredAuthority(MemoryRetentionClass.WORKING)
        val executor = MemoryRetentionExecutor(MemoryRetentionAuthorizer(authority), mutationPort)

        val result = executor.execute(
            pruneEntry("episodic", 2, MemoryRetentionClass.EPISODIC),
            principal
        )

        assertIs<MemoryRetentionExecutionResult.Denied>(result)
        assertEquals(0, mutationPort.removeCount)
    }

    @Test
    fun exact_scope_commits_only_requested_identity() {
        val mutationPort = FakeMutationPort()
        val (authority, _) = configuredAuthority(MemoryRetentionClass.EPISODIC)
        val executor = MemoryRetentionExecutor(MemoryRetentionAuthorizer(authority), mutationPort)

        val result = executor.execute(
            pruneEntry("exact", 11, MemoryRetentionClass.EPISODIC),
            principal
        )

        assertIs<MemoryRetentionExecutionResult.Committed>(result)
        assertEquals(1, mutationPort.removeCount)
        assertEquals(MemoryRecordId("exact"), mutationPort.lastRecordId)
        assertEquals(MemoryGeneration(11), mutationPort.lastGeneration)
    }

    @Test
    fun generation_bound_mutation_rejection_is_propagated() {
        val mutationPort = FakeMutationPort(
            PersistentMemoryMutationResult.Rejected("retention memory generation is stale")
        )
        val (authority, _) = configuredAuthority(MemoryRetentionClass.SEMANTIC)
        val executor = MemoryRetentionExecutor(MemoryRetentionAuthorizer(authority), mutationPort)

        val result = executor.execute(
            pruneEntry("stale", 7, MemoryRetentionClass.SEMANTIC),
            principal
        )

        val rejected = assertIs<MemoryRetentionExecutionResult.Rejected>(result)
        assertTrue(rejected.reason.contains("generation is stale"))
        assertEquals(1, mutationPort.removeCount)
    }

    @Test
    fun authority_is_rechecked_for_every_prune_mutation() {
        val mutationPort = FakeMutationPort()
        val (authority, grantOwnership) = configuredAuthority(MemoryRetentionClass.EPISODIC)
        val executor = MemoryRetentionExecutor(MemoryRetentionAuthorizer(authority), mutationPort)

        assertIs<MemoryRetentionExecutionResult.Committed>(
            executor.execute(pruneEntry("first", 21, MemoryRetentionClass.EPISODIC), principal)
        )
        assertTrue(grantOwnership.revoke())

        val denied = executor.execute(
            pruneEntry("second", 22, MemoryRetentionClass.EPISODIC),
            principal
        )

        assertIs<MemoryRetentionExecutionResult.Denied>(denied)
        assertEquals(1, mutationPort.removeCount)
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
        id: String,
        generation: Long,
        retentionClass: MemoryRetentionClass
    ): MemoryRetentionLedgerEntry = ledgerEntry(
        id = id,
        generation = generation,
        retentionClass = retentionClass,
        disposition = MemoryRetentionDisposition.RECORD_BUDGET_REJECTED,
        action = MemoryRetentionShadowAction.PRUNE_CANDIDATE
    )

    private fun ledgerEntry(
        id: String,
        generation: Long,
        retentionClass: MemoryRetentionClass,
        disposition: MemoryRetentionDisposition,
        action: MemoryRetentionShadowAction
    ): MemoryRetentionLedgerEntry = MemoryRetentionLedgerEntry(
        recordId = MemoryRecordId(id),
        generation = MemoryGeneration(generation),
        retentionClass = retentionClass,
        disposition = disposition,
        action = action
    )

    private fun foundation(): FoundationComposition {
        val logs = InMemoryLogWriter()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "memory-retention-executor-contract" }
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
}
