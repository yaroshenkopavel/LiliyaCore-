package pro.liliya.core.memory

import pro.liliya.core.authority.AuthorityPrincipal

internal fun interface MemoryRetentionMutationPort {
    fun removeExact(
        recordId: MemoryRecordId,
        generation: MemoryGeneration
    ): PersistentMemoryMutationResult
}

internal fun PersistentMemoryComposition.retentionMutationPort(): MemoryRetentionMutationPort =
    MemoryRetentionMutationPort { recordId, generation ->
        val snapshot = inspect(recordId)
            ?: return@MemoryRetentionMutationPort PersistentMemoryMutationResult.Rejected(
                "retention memory record is not live"
            )
        if (snapshot.generation != generation) {
            PersistentMemoryMutationResult.Rejected("retention memory generation is stale")
        } else {
            removeExact(snapshot)
        }
    }

internal fun EncryptedPersistentMemoryComposition.retentionMutationPort(): MemoryRetentionMutationPort =
    MemoryRetentionMutationPort { recordId, generation ->
        val snapshot = inspect(recordId)
            ?: return@MemoryRetentionMutationPort PersistentMemoryMutationResult.Rejected(
                "retention memory record is not live"
            )
        if (snapshot.generation != generation) {
            PersistentMemoryMutationResult.Rejected("retention memory generation is stale")
        } else {
            removeExact(snapshot)
        }
    }

sealed interface MemoryRetentionExecutionResult {
    data object Kept : MemoryRetentionExecutionResult
    data class Denied(val reason: String) : MemoryRetentionExecutionResult
    data object Committed : MemoryRetentionExecutionResult
    data class Rejected(val reason: String) : MemoryRetentionExecutionResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : MemoryRetentionExecutionResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

/**
 * Executes exactly one shadow-ledger decision.
 *
 * There is intentionally no batch API here. Every prune candidate performs a fresh Authority
 * decision immediately before exactly one generation-bound mutation. A retained entry never asks
 * for Authority and never touches the mutation port. Multi-entry orchestration is deferred until a
 * durable recovery journal exists, avoiding partially applied retention batches.
 */
class MemoryRetentionExecutor internal constructor(
    private val authorizer: MemoryRetentionAuthorizer,
    private val mutationPort: MemoryRetentionMutationPort
) {
    fun execute(
        entry: MemoryRetentionLedgerEntry,
        principal: AuthorityPrincipal
    ): MemoryRetentionExecutionResult {
        if (entry.action == MemoryRetentionShadowAction.KEEP) {
            return MemoryRetentionExecutionResult.Kept
        }

        check(entry.requiresPruneAuthority) {
            "prune candidate must require retention Authority"
        }

        val request = MemoryRetentionPruneRequest(
            recordId = entry.recordId,
            generation = entry.generation,
            retentionClass = entry.retentionClass,
            disposition = entry.disposition
        )
        return when (val authorization = authorizer.authorize(request, principal)) {
            is MemoryRetentionAuthorizationResult.Denied ->
                MemoryRetentionExecutionResult.Denied(authorization.reason)

            is MemoryRetentionAuthorizationResult.Authorized -> when (
                val mutation = mutationPort.removeExact(entry.recordId, entry.generation)
            ) {
                PersistentMemoryMutationResult.Committed -> MemoryRetentionExecutionResult.Committed
                is PersistentMemoryMutationResult.Rejected ->
                    MemoryRetentionExecutionResult.Rejected(mutation.reason)
                is PersistentMemoryMutationResult.Failed ->
                    MemoryRetentionExecutionResult.Failed(mutation.reason, mutation.throwable)
            }
        }
    }
}
