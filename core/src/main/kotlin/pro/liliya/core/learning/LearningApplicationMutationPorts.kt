package pro.liliya.core.learning

import pro.liliya.core.logging.LogContext

/**
 * Narrow preparation ownership used by governed learning independently of the backing mutation
 * store implementation.
 */
interface LearningApplicationMutationPreparedOwnership {
    val plan: LearningApplicationMutationPlan
    val generation: LearningApplicationMutationGeneration
    fun remove(): Boolean
}

sealed interface LearningApplicationMutationPreparationResult {
    data class Prepared(
        val ownership: LearningApplicationMutationPreparedOwnership
    ) : LearningApplicationMutationPreparationResult

    data class AlreadyCompleted(
        val receipt: LearningApplicationMutationApplicationReceipt
    ) : LearningApplicationMutationPreparationResult

    data class Rejected(val reason: String) : LearningApplicationMutationPreparationResult
    data class Failed(val reason: String) : LearningApplicationMutationPreparationResult
}

fun interface LearningApplicationMutationPreparationPort {
    fun prepare(plan: LearningApplicationMutationPlan): LearningApplicationMutationPreparationResult
}

fun interface LearningApplicationMutationInspectionPort {
    fun inspect(id: LearningApplicationMutationId): LearningApplicationMutationSnapshot?
}

fun interface LearningApplicationMutationApplicationPort {
    fun apply(
        reference: LearningApplicationMutationReference
    ): LearningApplicationMutationApplicationResult
}

/**
 * Process-local compatibility adapters. They preserve the existing store and ownership semantics.
 */
fun LearningApplicationMutationComposition.preparationPort(): LearningApplicationMutationPreparationPort =
    LearningApplicationMutationPreparationPort { plan ->
        when (val result = prepare(plan)) {
            is LearningApplicationMutationPrepareResult.Prepared ->
                LearningApplicationMutationPreparationResult.Prepared(
                    object : LearningApplicationMutationPreparedOwnership {
                        override val plan = result.ownership.plan
                        override val generation = result.ownership.generation
                        override fun remove(): Boolean = result.ownership.remove()
                    }
                )
            is LearningApplicationMutationPrepareResult.AlreadyCompleted ->
                LearningApplicationMutationPreparationResult.AlreadyCompleted(result.receipt)
            is LearningApplicationMutationPrepareResult.Rejected ->
                LearningApplicationMutationPreparationResult.Rejected(result.reason)
        }
    }

fun LearningApplicationMutationComposition.inspectionPort(): LearningApplicationMutationInspectionPort =
    LearningApplicationMutationInspectionPort { id -> inspect(id) }

/**
 * Persistent compatibility adapters. Persistent backend failures remain distinguishable from
 * ordinary structural rejection.
 */
fun PersistentLearningApplicationMutationComposition.preparationPort():
    LearningApplicationMutationPreparationPort =
    LearningApplicationMutationPreparationPort { plan ->
        when (val result = prepare(plan)) {
            is PersistentLearningApplicationMutationPrepareResult.Prepared ->
                LearningApplicationMutationPreparationResult.Prepared(
                    object : LearningApplicationMutationPreparedOwnership {
                        override val plan = result.ownership.plan
                        override val generation = result.ownership.generation
                        override fun remove(): Boolean =
                            result.ownership.remove() is PersistentLearningApplicationMutationResult.Committed
                    }
                )
            is PersistentLearningApplicationMutationPrepareResult.AlreadyCompleted ->
                LearningApplicationMutationPreparationResult.AlreadyCompleted(result.receipt)
            is PersistentLearningApplicationMutationPrepareResult.Rejected ->
                LearningApplicationMutationPreparationResult.Rejected(result.reason)
            is PersistentLearningApplicationMutationPrepareResult.Failed ->
                LearningApplicationMutationPreparationResult.Failed(result.reason)
        }
    }

fun PersistentLearningApplicationMutationComposition.inspectionPort():
    LearningApplicationMutationInspectionPort =
    LearningApplicationMutationInspectionPort { id -> inspect(id) }

internal fun LearningApplicationMutationInspectionPort.inspectExact(
    reference: LearningApplicationMutationReference
): LearningApplicationMutationSnapshot? =
    inspect(reference.mutationId)?.takeIf { it.generation == reference.generation }
