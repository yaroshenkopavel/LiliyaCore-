package pro.liliya.core.memory

import pro.liliya.core.authority.AuthorityPrincipal

internal enum class MemoryRetentionExactTargetObservation {
    MISSING,
    EXACT,
    GENERATION_CHANGED
}

internal fun interface MemoryRetentionExactTargetObservationPort {
    fun observe(
        recordId: MemoryRecordId,
        generation: MemoryGeneration
    ): MemoryRetentionExactTargetObservation
}

internal fun PersistentMemoryComposition.retentionObservationPort(): MemoryRetentionExactTargetObservationPort =
    MemoryRetentionExactTargetObservationPort { recordId, generation ->
        when (val current = inspect(recordId)) {
            null -> MemoryRetentionExactTargetObservation.MISSING
            else -> if (current.generation == generation) {
                MemoryRetentionExactTargetObservation.EXACT
            } else {
                MemoryRetentionExactTargetObservation.GENERATION_CHANGED
            }
        }
    }

internal fun EncryptedPersistentMemoryComposition.retentionObservationPort():
    MemoryRetentionExactTargetObservationPort =
    MemoryRetentionExactTargetObservationPort { recordId, generation ->
        when (val current = inspect(recordId)) {
            null -> MemoryRetentionExactTargetObservation.MISSING
            else -> if (current.generation == generation) {
                MemoryRetentionExactTargetObservation.EXACT
            } else {
                MemoryRetentionExactTargetObservation.GENERATION_CHANGED
            }
        }
    }

sealed interface MemoryRetentionTransactionExecutionResult {
    data class Committed(val snapshot: MemoryRetentionTransactionSnapshot) :
        MemoryRetentionTransactionExecutionResult

    /**
     * The exact target was already absent during recovery. The transaction objective is closed
     * without claiming that this recovery invocation performed the deletion.
     */
    data class ResolvedAbsent(val snapshot: MemoryRetentionTransactionSnapshot) :
        MemoryRetentionTransactionExecutionResult

    data class Denied(val reason: String) : MemoryRetentionTransactionExecutionResult
    data class Stale(val reason: String) : MemoryRetentionTransactionExecutionResult
    data class Rejected(val reason: String) : MemoryRetentionTransactionExecutionResult
    data class RecoveryRequired(val reason: String, val throwable: Throwable? = null) :
        MemoryRetentionTransactionExecutionResult {
        override fun toString(): String =
            "RecoveryRequired(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }

    data class Failed(val reason: String, val throwable: Throwable? = null) :
        MemoryRetentionTransactionExecutionResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

/**
 * Journal-aware single-target retention execution and recovery.
 *
 * This class intentionally has no multi-target loop. A transaction must contain exactly one
 * target. Fresh execution persists AUTHORIZED before mutation. Recovery never treats that durable
 * state as Authority: after reopen it must start from RECOVERY_REQUIRED, inspect only the exact
 * identity/generation and obtain fresh scoped Authority before any retry mutation.
 */
class MemoryRetentionTransactionExecutor internal constructor(
    private val journal: PersistentMemoryRetentionTransactionJournal,
    private val authorizer: MemoryRetentionAuthorizer,
    private val mutationPort: MemoryRetentionMutationPort,
    private val observationPort: MemoryRetentionExactTargetObservationPort
) {
    fun execute(
        reference: MemoryRetentionTransactionReference,
        principal: AuthorityPrincipal
    ): MemoryRetentionTransactionExecutionResult {
        val snapshot = journal.inspect(reference.transactionId)
            ?: return MemoryRetentionTransactionExecutionResult.Rejected(
                "memory retention transaction is not live"
            )
        if (snapshot.reference != reference) {
            return MemoryRetentionTransactionExecutionResult.Stale(
                "memory retention transaction generation is stale"
            )
        }
        if (snapshot.state != MemoryRetentionTransactionState.PLANNED) {
            return MemoryRetentionTransactionExecutionResult.Rejected(
                "memory retention transaction is not planned"
            )
        }
        val target = singleTarget(snapshot) ?: return rejectMalformed(snapshot)

        return authorizeAndMutate(
            snapshot = snapshot,
            target = target,
            expectedState = MemoryRetentionTransactionState.PLANNED,
            principal = principal
        )
    }

    fun recover(
        reference: MemoryRetentionTransactionReference,
        principal: AuthorityPrincipal
    ): MemoryRetentionTransactionExecutionResult {
        val snapshot = journal.inspect(reference.transactionId)
            ?: return MemoryRetentionTransactionExecutionResult.Rejected(
                "memory retention transaction is not live"
            )
        if (snapshot.reference != reference) {
            return MemoryRetentionTransactionExecutionResult.Stale(
                "memory retention transaction generation is stale"
            )
        }
        if (snapshot.state != MemoryRetentionTransactionState.RECOVERY_REQUIRED) {
            return MemoryRetentionTransactionExecutionResult.Rejected(
                "memory retention transaction does not require recovery"
            )
        }
        val target = singleTarget(snapshot) ?: return rejectMalformed(snapshot)

        return when (observationPort.observe(target.recordId, target.generation)) {
            MemoryRetentionExactTargetObservation.MISSING -> completeResolvedAbsent(snapshot)
            MemoryRetentionExactTargetObservation.GENERATION_CHANGED -> rejectStaleTarget(snapshot)
            MemoryRetentionExactTargetObservation.EXACT -> authorizeAndMutate(
                snapshot = snapshot,
                target = target,
                expectedState = MemoryRetentionTransactionState.RECOVERY_REQUIRED,
                principal = principal
            )
        }
    }

    private fun authorizeAndMutate(
        snapshot: MemoryRetentionTransactionSnapshot,
        target: MemoryRetentionTransactionTarget,
        expectedState: MemoryRetentionTransactionState,
        principal: AuthorityPrincipal
    ): MemoryRetentionTransactionExecutionResult {
        val request = MemoryRetentionPruneRequest(
            recordId = target.recordId,
            generation = target.generation,
            retentionClass = target.retentionClass,
            disposition = target.disposition
        )
        when (val authorization = authorizer.authorize(request, principal)) {
            is MemoryRetentionAuthorizationResult.Denied ->
                return MemoryRetentionTransactionExecutionResult.Denied(authorization.reason)
            is MemoryRetentionAuthorizationResult.Authorized -> Unit
        }

        val authorized = when (
            val transition = journal.transition(
                reference = snapshot.reference,
                expectedState = expectedState,
                nextState = MemoryRetentionTransactionState.AUTHORIZED
            )
        ) {
            is PersistentMemoryRetentionTransactionTransitionResult.Committed -> transition.snapshot
            is PersistentMemoryRetentionTransactionTransitionResult.Rejected ->
                return MemoryRetentionTransactionExecutionResult.Rejected(transition.reason)
            is PersistentMemoryRetentionTransactionTransitionResult.Failed ->
                return MemoryRetentionTransactionExecutionResult.Failed(
                    transition.reason,
                    transition.throwable
                )
        }

        return when (val mutation = mutationPort.removeExact(target.recordId, target.generation)) {
            PersistentMemoryMutationResult.Committed -> completeAfterMutation(authorized)
            is PersistentMemoryMutationResult.Rejected -> rejectAfterDefiniteMutationRejection(
                authorized,
                mutation.reason
            )
            is PersistentMemoryMutationResult.Failed -> invalidateAfterUnknownMutationFailure(
                authorized,
                mutation.reason,
                mutation.throwable
            )
        }
    }

    private fun completeAfterMutation(
        authorized: MemoryRetentionTransactionSnapshot
    ): MemoryRetentionTransactionExecutionResult = when (
        val completion = journal.transition(
            reference = authorized.reference,
            expectedState = MemoryRetentionTransactionState.AUTHORIZED,
            nextState = MemoryRetentionTransactionState.COMMITTED
        )
    ) {
        is PersistentMemoryRetentionTransactionTransitionResult.Committed ->
            MemoryRetentionTransactionExecutionResult.Committed(completion.snapshot)
        is PersistentMemoryRetentionTransactionTransitionResult.Rejected ->
            MemoryRetentionTransactionExecutionResult.RecoveryRequired(
                "retention mutation committed but journal completion was rejected: ${completion.reason}"
            )
        is PersistentMemoryRetentionTransactionTransitionResult.Failed ->
            MemoryRetentionTransactionExecutionResult.RecoveryRequired(
                "retention mutation committed but journal completion failed: ${completion.reason}",
                completion.throwable
            )
    }

    private fun rejectAfterDefiniteMutationRejection(
        authorized: MemoryRetentionTransactionSnapshot,
        reason: String
    ): MemoryRetentionTransactionExecutionResult = when (
        val rejected = journal.transition(
            reference = authorized.reference,
            expectedState = MemoryRetentionTransactionState.AUTHORIZED,
            nextState = MemoryRetentionTransactionState.REJECTED
        )
    ) {
        is PersistentMemoryRetentionTransactionTransitionResult.Committed ->
            MemoryRetentionTransactionExecutionResult.Rejected(reason)
        is PersistentMemoryRetentionTransactionTransitionResult.Rejected ->
            MemoryRetentionTransactionExecutionResult.RecoveryRequired(
                "retention mutation rejected but journal rejection could not be committed: ${rejected.reason}"
            )
        is PersistentMemoryRetentionTransactionTransitionResult.Failed ->
            MemoryRetentionTransactionExecutionResult.RecoveryRequired(
                "retention mutation rejected but journal rejection failed: ${rejected.reason}",
                rejected.throwable
            )
    }

    private fun invalidateAfterUnknownMutationFailure(
        authorized: MemoryRetentionTransactionSnapshot,
        reason: String,
        throwable: Throwable?
    ): MemoryRetentionTransactionExecutionResult {
        return when (
            val recovery = journal.transition(
                reference = authorized.reference,
                expectedState = MemoryRetentionTransactionState.AUTHORIZED,
                nextState = MemoryRetentionTransactionState.RECOVERY_REQUIRED
            )
        ) {
            is PersistentMemoryRetentionTransactionTransitionResult.Committed ->
                MemoryRetentionTransactionExecutionResult.RecoveryRequired(reason, throwable)
            is PersistentMemoryRetentionTransactionTransitionResult.Rejected ->
                MemoryRetentionTransactionExecutionResult.RecoveryRequired(
                    "$reason; journal recovery transition was rejected: ${recovery.reason}",
                    throwable
                )
            is PersistentMemoryRetentionTransactionTransitionResult.Failed ->
                MemoryRetentionTransactionExecutionResult.RecoveryRequired(
                    "$reason; journal recovery transition failed: ${recovery.reason}",
                    recovery.throwable ?: throwable
                )
        }
    }

    private fun completeResolvedAbsent(
        snapshot: MemoryRetentionTransactionSnapshot
    ): MemoryRetentionTransactionExecutionResult = when (
        val completion = journal.transition(
            reference = snapshot.reference,
            expectedState = MemoryRetentionTransactionState.RECOVERY_REQUIRED,
            nextState = MemoryRetentionTransactionState.COMMITTED
        )
    ) {
        is PersistentMemoryRetentionTransactionTransitionResult.Committed ->
            MemoryRetentionTransactionExecutionResult.ResolvedAbsent(completion.snapshot)
        is PersistentMemoryRetentionTransactionTransitionResult.Rejected ->
            MemoryRetentionTransactionExecutionResult.RecoveryRequired(completion.reason)
        is PersistentMemoryRetentionTransactionTransitionResult.Failed ->
            MemoryRetentionTransactionExecutionResult.RecoveryRequired(
                completion.reason,
                completion.throwable
            )
    }

    private fun rejectStaleTarget(
        snapshot: MemoryRetentionTransactionSnapshot
    ): MemoryRetentionTransactionExecutionResult = when (
        val rejected = journal.transition(
            reference = snapshot.reference,
            expectedState = MemoryRetentionTransactionState.RECOVERY_REQUIRED,
            nextState = MemoryRetentionTransactionState.REJECTED
        )
    ) {
        is PersistentMemoryRetentionTransactionTransitionResult.Committed ->
            MemoryRetentionTransactionExecutionResult.Stale(
                "retention target generation changed during recovery"
            )
        is PersistentMemoryRetentionTransactionTransitionResult.Rejected ->
            MemoryRetentionTransactionExecutionResult.RecoveryRequired(rejected.reason)
        is PersistentMemoryRetentionTransactionTransitionResult.Failed ->
            MemoryRetentionTransactionExecutionResult.RecoveryRequired(
                rejected.reason,
                rejected.throwable
            )
    }

    private fun singleTarget(
        snapshot: MemoryRetentionTransactionSnapshot
    ): MemoryRetentionTransactionTarget? = snapshot.plan.targets.singleOrNull()

    private fun rejectMalformed(
        snapshot: MemoryRetentionTransactionSnapshot
    ): MemoryRetentionTransactionExecutionResult {
        val terminal = when (snapshot.state) {
            MemoryRetentionTransactionState.PLANNED,
            MemoryRetentionTransactionState.RECOVERY_REQUIRED -> journal.transition(
                reference = snapshot.reference,
                expectedState = snapshot.state,
                nextState = MemoryRetentionTransactionState.REJECTED
            )
            else -> null
        }
        return if (terminal is PersistentMemoryRetentionTransactionTransitionResult.Failed) {
            MemoryRetentionTransactionExecutionResult.Failed(terminal.reason, terminal.throwable)
        } else {
            MemoryRetentionTransactionExecutionResult.Rejected(
                "single-target retention executor requires exactly one target"
            )
        }
    }

    companion object {
        fun persistent(
            journal: PersistentMemoryRetentionTransactionJournal,
            authorizer: MemoryRetentionAuthorizer,
            memory: PersistentMemoryComposition
        ): MemoryRetentionTransactionExecutor = MemoryRetentionTransactionExecutor(
            journal = journal,
            authorizer = authorizer,
            mutationPort = memory.retentionMutationPort(),
            observationPort = memory.retentionObservationPort()
        )

        fun encrypted(
            journal: PersistentMemoryRetentionTransactionJournal,
            authorizer: MemoryRetentionAuthorizer,
            memory: EncryptedPersistentMemoryComposition
        ): MemoryRetentionTransactionExecutor = MemoryRetentionTransactionExecutor(
            journal = journal,
            authorizer = authorizer,
            mutationPort = memory.retentionMutationPort(),
            observationPort = memory.retentionObservationPort()
        )
    }
}
