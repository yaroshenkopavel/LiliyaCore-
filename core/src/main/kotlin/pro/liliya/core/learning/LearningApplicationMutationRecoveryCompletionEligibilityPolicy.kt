package pro.liliya.core.learning

/**
 * Fail-closed read-only decision for whether one exact restored Prepared mutation has enough
 * evidence for a later recovery-completion operation.
 *
 * Eligibility != completion authority.
 * Eligibility != mutation claim ownership.
 * Eligibility != replay or downstream-write authority.
 */
enum class LearningApplicationMutationRecoveryCompletionRejection {
    MUTATION_MISSING,
    MUTATION_ID_MISMATCH,
    MUTATION_GENERATION_MISMATCH,
    ALREADY_COMPLETED,
    COMPLETED_STATE_MISMATCH,
    TARGET_MISMATCH,
    DOWNSTREAM_ID_MISMATCH
}

sealed interface LearningApplicationMutationRecoveryCompletionEligibilityResult {
    data class Eligible(
        val evidence: LearningApplicationMutationExactRecoveryEvidence
    ) : LearningApplicationMutationRecoveryCompletionEligibilityResult

    data class Rejected(
        val reason: LearningApplicationMutationRecoveryCompletionRejection
    ) : LearningApplicationMutationRecoveryCompletionEligibilityResult
}

/**
 * Pure P2.1e policy. It performs no claim, completion, removal, replay, compensation, downstream
 * mutation, Authority action, signing action, or learning enablement.
 */
object LearningApplicationMutationRecoveryCompletionEligibilityPolicy {
    fun evaluate(
        evidence: LearningApplicationMutationExactRecoveryEvidence,
        preparedMutation: LearningApplicationMutationSnapshot?,
        completedReceipt: LearningApplicationMutationApplicationReceipt?
    ): LearningApplicationMutationRecoveryCompletionEligibilityResult {
        if (completedReceipt != null) {
            val reason = if (completedReceipt.mutation == evidence.mutation) {
                LearningApplicationMutationRecoveryCompletionRejection.ALREADY_COMPLETED
            } else {
                LearningApplicationMutationRecoveryCompletionRejection.COMPLETED_STATE_MISMATCH
            }
            return LearningApplicationMutationRecoveryCompletionEligibilityResult.Rejected(reason)
        }

        val prepared = preparedMutation
            ?: return LearningApplicationMutationRecoveryCompletionEligibilityResult.Rejected(
                LearningApplicationMutationRecoveryCompletionRejection.MUTATION_MISSING
            )

        if (prepared.plan.id != evidence.mutation.mutationId) {
            return LearningApplicationMutationRecoveryCompletionEligibilityResult.Rejected(
                LearningApplicationMutationRecoveryCompletionRejection.MUTATION_ID_MISMATCH
            )
        }
        if (prepared.generation != evidence.mutation.generation) {
            return LearningApplicationMutationRecoveryCompletionEligibilityResult.Rejected(
                LearningApplicationMutationRecoveryCompletionRejection.MUTATION_GENERATION_MISMATCH
            )
        }
        if (prepared.plan.target != evidence.target) {
            return LearningApplicationMutationRecoveryCompletionEligibilityResult.Rejected(
                LearningApplicationMutationRecoveryCompletionRejection.TARGET_MISMATCH
            )
        }

        val exactDownstreamIdentity = when (val payload = prepared.plan.payload) {
            is LearningApplicationMutationPayload.Memory ->
                (evidence.downstream as? LearningApplicationDownstreamReference.Memory)
                    ?.recordId == payload.record.id
            is LearningApplicationMutationPayload.Knowledge ->
                (evidence.downstream as? LearningApplicationDownstreamReference.Knowledge)
                    ?.itemId == payload.item.id
        }
        if (!exactDownstreamIdentity) {
            return LearningApplicationMutationRecoveryCompletionEligibilityResult.Rejected(
                LearningApplicationMutationRecoveryCompletionRejection.DOWNSTREAM_ID_MISMATCH
            )
        }

        return LearningApplicationMutationRecoveryCompletionEligibilityResult.Eligible(evidence)
    }
}
