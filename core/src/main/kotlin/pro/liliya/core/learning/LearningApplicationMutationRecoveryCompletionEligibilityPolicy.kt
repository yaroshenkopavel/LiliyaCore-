package pro.liliya.core.learning

/**
 * Read-only P2.1e batch decision over the complete current Prepared mutation set and its exact
 * recovery-evidence classification.
 *
 * Eligibility != Authority.
 * Eligibility != recovery execution.
 * Eligibility != mutation completion.
 * Eligibility != replay or downstream-write authority.
 *
 * This policy deliberately exposes no claim/complete/remove/replay/compensation/downstream-write
 * operation. It only decides which separately gated recovery path, if any, may be considered next.
 */
enum class LearningApplicationMutationRecoveryCompletionEligibilityDecision {
    NO_RECOVERY_REQUIRED,
    EXACT_COMPLETION_EVIDENCE_READY,
    REPLAY_REQUIRES_FRESH_AUTHORITY,
    MISMATCH_BLOCKED,
    MIXED_RECOVERY_BLOCKED,
    EVIDENCE_INCONSISTENT,
    EVIDENCE_FAILED
}

object LearningApplicationMutationRecoveryCompletionEligibilityPolicy {
    fun decide(
        preparedMutations: List<LearningApplicationMutationSnapshot>,
        evidence: LearningApplicationMutationRecoveryEvidenceResult
    ): LearningApplicationMutationRecoveryCompletionEligibilityDecision = when (evidence) {
        LearningApplicationMutationRecoveryEvidenceResult.Failed ->
            LearningApplicationMutationRecoveryCompletionEligibilityDecision.EVIDENCE_FAILED

        is LearningApplicationMutationRecoveryEvidenceResult.Classified ->
            decide(preparedMutations, evidence.summary)
    }

    internal fun decide(
        preparedMutations: List<LearningApplicationMutationSnapshot>,
        summary: LearningApplicationMutationRecoveryEvidenceSummary
    ): LearningApplicationMutationRecoveryCompletionEligibilityDecision {
        // A mismatch is the strongest classified fail-closed state and deliberately dominates
        // lower-risk exact/absent combinations.
        if (summary.mismatchedDownstreamPresent > 0) {
            return LearningApplicationMutationRecoveryCompletionEligibilityDecision.MISMATCH_BLOCKED
        }

        val preparedReferences = preparedMutations.map(::referenceOf)
        val exact = summary.exactDownstream

        if (
            preparedReferences.toSet().size != preparedReferences.size ||
            summary.totalPrepared != preparedMutations.size ||
            exact.map { it.mutation }.toSet().size != exact.size ||
            exact.map { it.downstream }.toSet().size != exact.size
        ) {
            return LearningApplicationMutationRecoveryCompletionEligibilityDecision
                .EVIDENCE_INCONSISTENT
        }

        val preparedByReference = preparedMutations.associateBy(::referenceOf)
        if (exact.any { evidence -> !matchesPrepared(evidence, preparedByReference[evidence.mutation]) }) {
            return LearningApplicationMutationRecoveryCompletionEligibilityDecision
                .EVIDENCE_INCONSISTENT
        }

        if (exact.isNotEmpty() && summary.noDownstream > 0) {
            return LearningApplicationMutationRecoveryCompletionEligibilityDecision
                .MIXED_RECOVERY_BLOCKED
        }

        if (exact.isNotEmpty()) {
            return LearningApplicationMutationRecoveryCompletionEligibilityDecision
                .EXACT_COMPLETION_EVIDENCE_READY
        }

        if (summary.noDownstream > 0) {
            return LearningApplicationMutationRecoveryCompletionEligibilityDecision
                .REPLAY_REQUIRES_FRESH_AUTHORITY
        }

        return LearningApplicationMutationRecoveryCompletionEligibilityDecision.NO_RECOVERY_REQUIRED
    }

    private fun referenceOf(
        snapshot: LearningApplicationMutationSnapshot
    ): LearningApplicationMutationReference =
        LearningApplicationMutationReference(snapshot.plan.id, snapshot.generation)

    private fun matchesPrepared(
        evidence: LearningApplicationMutationExactRecoveryEvidence,
        prepared: LearningApplicationMutationSnapshot?
    ): Boolean {
        prepared ?: return false
        if (prepared.plan.target != evidence.target) return false

        return when (val payload = prepared.plan.payload) {
            is LearningApplicationMutationPayload.Memory ->
                (evidence.downstream as? LearningApplicationDownstreamReference.Memory)
                    ?.recordId == payload.record.id

            is LearningApplicationMutationPayload.Knowledge ->
                (evidence.downstream as? LearningApplicationDownstreamReference.Knowledge)
                    ?.itemId == payload.item.id
        }
    }
}
