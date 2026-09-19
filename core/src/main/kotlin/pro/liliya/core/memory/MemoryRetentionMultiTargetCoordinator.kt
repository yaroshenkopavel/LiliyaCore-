package pro.liliya.core.memory

import pro.liliya.core.authority.AuthorityPrincipal

internal interface MemoryRetentionSingleTargetExecutionPort {
    fun execute(
        reference: MemoryRetentionTransactionReference,
        principal: AuthorityPrincipal
    ): MemoryRetentionTransactionExecutionResult

    fun recover(
        reference: MemoryRetentionTransactionReference,
        principal: AuthorityPrincipal
    ): MemoryRetentionTransactionExecutionResult
}

internal fun MemoryRetentionTransactionExecutor.executionPort(): MemoryRetentionSingleTargetExecutionPort =
    object : MemoryRetentionSingleTargetExecutionPort {
        override fun execute(
            reference: MemoryRetentionTransactionReference,
            principal: AuthorityPrincipal
        ): MemoryRetentionTransactionExecutionResult = this@executionPort.execute(reference, principal)

        override fun recover(
            reference: MemoryRetentionTransactionReference,
            principal: AuthorityPrincipal
        ): MemoryRetentionTransactionExecutionResult = this@executionPort.recover(reference, principal)
    }

sealed interface MemoryRetentionMultiTargetExecutionResult {
    data class Advanced(
        val snapshot: MemoryRetentionMultiTargetSnapshot,
        val childReference: MemoryRetentionTransactionReference
    ) : MemoryRetentionMultiTargetExecutionResult

    data class Completed(
        val snapshot: MemoryRetentionMultiTargetSnapshot,
        val childReference: MemoryRetentionTransactionReference?
    ) : MemoryRetentionMultiTargetExecutionResult

    data class Denied(val reason: String) : MemoryRetentionMultiTargetExecutionResult
    data class Stale(val reason: String) : MemoryRetentionMultiTargetExecutionResult
    data class Rejected(
        val reason: String,
        val snapshot: MemoryRetentionMultiTargetSnapshot? = null
    ) : MemoryRetentionMultiTargetExecutionResult
    data class RecoveryRequired(val reason: String, val throwable: Throwable? = null) :
        MemoryRetentionMultiTargetExecutionResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        MemoryRetentionMultiTargetExecutionResult
}

/**
 * Durable ordered multi-target coordinator.
 *
 * Exactly one target is processed per invocation. The coordinator never mutates Memory directly;
 * each target is represented by a deterministic child transaction and delegated to the proven
 * single-target execution/recovery primitive. Parent progress advances only after durable child
 * COMMITTED evidence. A crash after child commit but before parent advance is therefore replay-safe:
 * the next invocation observes the committed child and advances the parent without another delete.
 */
class MemoryRetentionMultiTargetCoordinator internal constructor(
    private val journal: PersistentMemoryRetentionMultiTargetJournal,
    private val childJournal: PersistentMemoryRetentionTransactionJournal,
    private val executionPort: MemoryRetentionSingleTargetExecutionPort
) {
    fun executeNext(
        reference: MemoryRetentionMultiTargetReference,
        principal: AuthorityPrincipal
    ): MemoryRetentionMultiTargetExecutionResult {
        val parent = journal.inspect(reference.id)
            ?: return MemoryRetentionMultiTargetExecutionResult.Rejected(
                "memory retention multi-target is not live"
            )
        if (parent.reference != reference) {
            return MemoryRetentionMultiTargetExecutionResult.Stale(
                "memory retention multi-target generation is stale"
            )
        }
        when (parent.state) {
            MemoryRetentionMultiTargetState.COMPLETED ->
                return MemoryRetentionMultiTargetExecutionResult.Completed(parent, null)
            MemoryRetentionMultiTargetState.REJECTED ->
                return MemoryRetentionMultiTargetExecutionResult.Rejected(
                    "memory retention multi-target is rejected",
                    parent
                )
            MemoryRetentionMultiTargetState.ACTIVE -> Unit
        }

        val target = parent.currentTarget
            ?: return rejectParent(parent, "memory retention multi-target has no current target")
        val child = when (val ensured = ensureChild(parent, target)) {
            is ChildResolution.Resolved -> ensured.snapshot
            is ChildResolution.Rejected -> return rejectParent(parent, ensured.reason)
            is ChildResolution.Failed -> return MemoryRetentionMultiTargetExecutionResult.Failed(
                ensured.reason,
                ensured.throwable
            )
        }

        if (child.plan.targets.singleOrNull() != target) {
            return rejectParent(parent, "retention child transaction target does not match parent progress")
        }

        return when (child.state) {
            MemoryRetentionTransactionState.PLANNED ->
                handleChildResult(parent, child.reference, executionPort.execute(child.reference, principal))
            MemoryRetentionTransactionState.RECOVERY_REQUIRED ->
                handleChildResult(parent, child.reference, executionPort.recover(child.reference, principal))
            MemoryRetentionTransactionState.COMMITTED -> advanceParent(parent, child.reference)
            MemoryRetentionTransactionState.REJECTED -> rejectParent(
                parent,
                "retention child transaction is rejected"
            )
            MemoryRetentionTransactionState.AUTHORIZED ->
                MemoryRetentionMultiTargetExecutionResult.RecoveryRequired(
                    "retention child transaction has uncertain authorized state; reopen child journal before recovery"
                )
        }
    }

    private fun handleChildResult(
        parent: MemoryRetentionMultiTargetSnapshot,
        childReference: MemoryRetentionTransactionReference,
        result: MemoryRetentionTransactionExecutionResult
    ): MemoryRetentionMultiTargetExecutionResult = when (result) {
        is MemoryRetentionTransactionExecutionResult.Committed,
        is MemoryRetentionTransactionExecutionResult.ResolvedAbsent ->
            advanceParent(parent, childReference)
        is MemoryRetentionTransactionExecutionResult.Denied ->
            MemoryRetentionMultiTargetExecutionResult.Denied(result.reason)
        is MemoryRetentionTransactionExecutionResult.Stale ->
            rejectParent(parent, result.reason)
        is MemoryRetentionTransactionExecutionResult.Rejected ->
            rejectParent(parent, result.reason)
        is MemoryRetentionTransactionExecutionResult.RecoveryRequired ->
            MemoryRetentionMultiTargetExecutionResult.RecoveryRequired(
                result.reason,
                result.throwable
            )
        is MemoryRetentionTransactionExecutionResult.Failed ->
            MemoryRetentionMultiTargetExecutionResult.Failed(result.reason, result.throwable)
    }

    private fun advanceParent(
        parent: MemoryRetentionMultiTargetSnapshot,
        childReference: MemoryRetentionTransactionReference
    ): MemoryRetentionMultiTargetExecutionResult = when (
        val progress = journal.advance(parent.reference, parent.nextIndex)
    ) {
        is PersistentMemoryRetentionMultiTargetProgressResult.Committed ->
            if (progress.snapshot.state == MemoryRetentionMultiTargetState.COMPLETED) {
                MemoryRetentionMultiTargetExecutionResult.Completed(
                    progress.snapshot,
                    childReference
                )
            } else {
                MemoryRetentionMultiTargetExecutionResult.Advanced(
                    progress.snapshot,
                    childReference
                )
            }
        is PersistentMemoryRetentionMultiTargetProgressResult.Rejected ->
            MemoryRetentionMultiTargetExecutionResult.RecoveryRequired(
                "retention child is committed but parent progress could not advance: ${progress.reason}"
            )
        is PersistentMemoryRetentionMultiTargetProgressResult.Failed ->
            MemoryRetentionMultiTargetExecutionResult.RecoveryRequired(
                "retention child is committed but parent progress failed: ${progress.reason}",
                progress.throwable
            )
    }

    private fun rejectParent(
        parent: MemoryRetentionMultiTargetSnapshot,
        reason: String
    ): MemoryRetentionMultiTargetExecutionResult = when (
        val rejected = journal.reject(parent.reference, parent.nextIndex)
    ) {
        is PersistentMemoryRetentionMultiTargetProgressResult.Committed ->
            MemoryRetentionMultiTargetExecutionResult.Rejected(reason, rejected.snapshot)
        is PersistentMemoryRetentionMultiTargetProgressResult.Rejected ->
            MemoryRetentionMultiTargetExecutionResult.RecoveryRequired(
                "$reason; parent rejection could not be committed: ${rejected.reason}"
            )
        is PersistentMemoryRetentionMultiTargetProgressResult.Failed ->
            MemoryRetentionMultiTargetExecutionResult.RecoveryRequired(
                "$reason; parent rejection failed: ${rejected.reason}",
                rejected.throwable
            )
    }

    private fun ensureChild(
        parent: MemoryRetentionMultiTargetSnapshot,
        target: MemoryRetentionTransactionTarget
    ): ChildResolution {
        val childId = parent.childTransactionId()
        childJournal.inspect(childId)?.let { return ChildResolution.Resolved(it) }

        val plan = MemoryRetentionTransactionPlan(
            id = childId,
            targets = listOf(target),
            createdAt = parent.plan.createdAt
        )
        return when (val prepared = childJournal.prepare(plan)) {
            is PersistentMemoryRetentionTransactionPrepareResult.Prepared ->
                ChildResolution.Resolved(prepared.snapshot)
            is PersistentMemoryRetentionTransactionPrepareResult.Rejected -> {
                val existing = childJournal.inspect(childId)
                if (existing != null) {
                    ChildResolution.Resolved(existing)
                } else {
                    ChildResolution.Rejected(prepared.reason)
                }
            }
            is PersistentMemoryRetentionTransactionPrepareResult.Failed ->
                ChildResolution.Failed(prepared.reason, prepared.throwable)
        }
    }

    private sealed interface ChildResolution {
        data class Resolved(val snapshot: MemoryRetentionTransactionSnapshot) : ChildResolution
        data class Rejected(val reason: String) : ChildResolution
        data class Failed(val reason: String, val throwable: Throwable? = null) : ChildResolution
    }

    companion object {
        fun create(
            journal: PersistentMemoryRetentionMultiTargetJournal,
            childJournal: PersistentMemoryRetentionTransactionJournal,
            executor: MemoryRetentionTransactionExecutor
        ): MemoryRetentionMultiTargetCoordinator = MemoryRetentionMultiTargetCoordinator(
            journal = journal,
            childJournal = childJournal,
            executionPort = executor.executionPort()
        )
    }
}
