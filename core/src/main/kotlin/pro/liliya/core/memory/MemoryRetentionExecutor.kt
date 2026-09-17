package pro.liliya.core.memory

import pro.liliya.core.authority.AuthorityPrincipal

internal interface MemoryRetentionExecutionTarget {
    fun inspect(id: MemoryRecordId): MemoryRecordSnapshot?
    fun removeExact(snapshot: MemoryRecordSnapshot): PersistentMemoryMutationResult
}

private class PersistentRetentionExecutionTarget(
    private val composition: PersistentMemoryComposition
) : MemoryRetentionExecutionTarget {
    override fun inspect(id: MemoryRecordId): MemoryRecordSnapshot? = composition.inspect(id)
    override fun removeExact(snapshot: MemoryRecordSnapshot): PersistentMemoryMutationResult =
        composition.removeExact(snapshot)
}

private class EncryptedRetentionExecutionTarget(
    private val composition: EncryptedPersistentMemoryComposition
) : MemoryRetentionExecutionTarget {
    override fun inspect(id: MemoryRecordId): MemoryRecordSnapshot? = composition.inspect(id)
    override fun removeExact(snapshot: MemoryRecordSnapshot): PersistentMemoryMutationResult =
        composition.removeExact(snapshot)
}

sealed interface MemoryRetentionExecutionResult {
    data object Kept : MemoryRetentionExecutionResult
    data object Pruned : MemoryRetentionExecutionResult
    data class Denied(val reason: String) : MemoryRetentionExecutionResult
    data class Stale(val reason: String) : MemoryRetentionExecutionResult
    data class Rejected(val reason: String) : MemoryRetentionExecutionResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : MemoryRetentionExecutionResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

/**
 * Executes one read-only retention-ledger decision against authoritative durable Memory.
 *
 * The ledger itself carries no Authority. Every prune candidate is re-inspected, its exact
 * generation is compared with the shadow decision, and fresh class-scoped Authority is checked
 * immediately before the exact-generation mutation. A stale ledger can therefore never delete a
 * newer reused record even when the principal still has retention Authority.
 */
internal class MemoryRetentionExecutor(
    private val authorizer: MemoryRetentionAuthorizer,
    private val target: MemoryRetentionExecutionTarget
) {
    fun execute(
        entry: MemoryRetentionLedgerEntry,
        principal: AuthorityPrincipal
    ): MemoryRetentionExecutionResult {
        if (entry.action == MemoryRetentionShadowAction.KEEP) {
            return MemoryRetentionExecutionResult.Kept
        }

        val current = target.inspect(entry.recordId)
            ?: return MemoryRetentionExecutionResult.Stale("retention target is no longer live")
        if (current.generation != entry.generation) {
            return MemoryRetentionExecutionResult.Stale("retention target generation changed")
        }

        val request = MemoryRetentionPruneRequest(
            snapshot = current,
            retentionClass = entry.retentionClass,
            disposition = entry.disposition
        )
        when (val authorization = authorizer.authorize(request, principal)) {
            is MemoryRetentionAuthorizationResult.Denied ->
                return MemoryRetentionExecutionResult.Denied(authorization.reason)
            is MemoryRetentionAuthorizationResult.Authorized -> Unit
        }

        return when (val removal = target.removeExact(current)) {
            PersistentMemoryMutationResult.Committed -> MemoryRetentionExecutionResult.Pruned
            is PersistentMemoryMutationResult.Rejected ->
                MemoryRetentionExecutionResult.Rejected(removal.reason)
            is PersistentMemoryMutationResult.Failed ->
                MemoryRetentionExecutionResult.Failed(removal.reason, removal.throwable)
        }
    }

    companion object {
        fun persistent(
            authorizer: MemoryRetentionAuthorizer,
            composition: PersistentMemoryComposition
        ): MemoryRetentionExecutor = MemoryRetentionExecutor(
            authorizer = authorizer,
            target = PersistentRetentionExecutionTarget(composition)
        )

        fun encrypted(
            authorizer: MemoryRetentionAuthorizer,
            composition: EncryptedPersistentMemoryComposition
        ): MemoryRetentionExecutor = MemoryRetentionExecutor(
            authorizer = authorizer,
            target = EncryptedRetentionExecutionTarget(composition)
        )
    }
}
