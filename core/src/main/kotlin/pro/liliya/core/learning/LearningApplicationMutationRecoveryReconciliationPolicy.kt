package pro.liliya.core.learning

/**
 * Pure reconciliation policy for durable Prepared governed-learning mutations.
 *
 * Policy decision != Authority.
 * Policy decision != recovery execution.
 * Policy decision != mutation completion.
 *
 * This owner deliberately exposes no claim/complete/remove/downstream-write/Authority ports. It
 * only converts read-only recovery classification evidence into the next required safety gate.
 */
enum class LearningApplicationMutationRecoveryReconciliationDecision {
    NO_RECOVERY_REQUIRED,
    REPLAY_REQUIRES_FRESH_AUTHORITY,
    EXACT_DOWNSTREAM_REQUIRES_EXPLICIT_COMPLETION_POLICY,
    MISMATCH_BLOCKED,
    CLASSIFICATION_FAILED
}

object LearningApplicationMutationRecoveryReconciliationPolicy {
    fun decide(
        classification: LearningApplicationMutationRecoveryClassificationResult
    ): LearningApplicationMutationRecoveryReconciliationDecision = when (classification) {
        LearningApplicationMutationRecoveryClassificationResult.Failed ->
            LearningApplicationMutationRecoveryReconciliationDecision.CLASSIFICATION_FAILED

        is LearningApplicationMutationRecoveryClassificationResult.Classified ->
            decide(classification.summary)
    }

    internal fun decide(
        summary: LearningApplicationMutationRecoverySummary
    ): LearningApplicationMutationRecoveryReconciliationDecision = when {
        summary.mismatchedDownstreamPresent > 0 ->
            LearningApplicationMutationRecoveryReconciliationDecision.MISMATCH_BLOCKED

        summary.exactDownstreamPresent > 0 ->
            LearningApplicationMutationRecoveryReconciliationDecision
                .EXACT_DOWNSTREAM_REQUIRES_EXPLICIT_COMPLETION_POLICY

        summary.noDownstream > 0 ->
            LearningApplicationMutationRecoveryReconciliationDecision
                .REPLAY_REQUIRES_FRESH_AUTHORITY

        else -> LearningApplicationMutationRecoveryReconciliationDecision.NO_RECOVERY_REQUIRED
    }
}
