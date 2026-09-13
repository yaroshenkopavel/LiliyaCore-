package pro.liliya.core.learning

import kotlin.test.Test
import kotlin.test.assertEquals

class LearningApplicationMutationRecoveryReconciliationPolicyContractTest {

    @Test
    fun clean_evidence_requires_no_recovery() {
        assertDecision(
            summary = LearningApplicationMutationRecoverySummary.Clean,
            expected = LearningApplicationMutationRecoveryReconciliationDecision.NO_RECOVERY_REQUIRED
        )
    }

    @Test
    fun only_absent_downstream_requires_fresh_authority_before_replay() {
        assertDecision(
            summary = summary(noDownstream = 2),
            expected = LearningApplicationMutationRecoveryReconciliationDecision
                .REPLAY_REQUIRES_FRESH_AUTHORITY
        )
    }

    @Test
    fun exact_downstream_requires_separate_completion_policy() {
        assertDecision(
            summary = summary(exactDownstreamPresent = 1),
            expected = LearningApplicationMutationRecoveryReconciliationDecision
                .EXACT_DOWNSTREAM_REQUIRES_EXPLICIT_COMPLETION_POLICY
        )
    }

    @Test
    fun mismatch_is_blocked() {
        assertDecision(
            summary = summary(mismatchedDownstreamPresent = 1),
            expected = LearningApplicationMutationRecoveryReconciliationDecision.MISMATCH_BLOCKED
        )
    }

    @Test
    fun exact_downstream_dominates_replay_candidate() {
        assertDecision(
            summary = summary(noDownstream = 1, exactDownstreamPresent = 1),
            expected = LearningApplicationMutationRecoveryReconciliationDecision
                .EXACT_DOWNSTREAM_REQUIRES_EXPLICIT_COMPLETION_POLICY
        )
    }

    @Test
    fun mismatch_dominates_exact_and_absent_downstream() {
        assertDecision(
            summary = summary(
                noDownstream = 1,
                exactDownstreamPresent = 1,
                mismatchedDownstreamPresent = 1
            ),
            expected = LearningApplicationMutationRecoveryReconciliationDecision.MISMATCH_BLOCKED
        )
    }

    @Test
    fun mismatch_dominates_exact_downstream() {
        assertDecision(
            summary = summary(exactDownstreamPresent = 2, mismatchedDownstreamPresent = 1),
            expected = LearningApplicationMutationRecoveryReconciliationDecision.MISMATCH_BLOCKED
        )
    }

    @Test
    fun classification_failure_fails_closed() {
        assertEquals(
            LearningApplicationMutationRecoveryReconciliationDecision.CLASSIFICATION_FAILED,
            LearningApplicationMutationRecoveryReconciliationPolicy.decide(
                LearningApplicationMutationRecoveryClassificationResult.Failed
            )
        )
    }

    private fun assertDecision(
        summary: LearningApplicationMutationRecoverySummary,
        expected: LearningApplicationMutationRecoveryReconciliationDecision
    ) {
        assertEquals(
            expected,
            LearningApplicationMutationRecoveryReconciliationPolicy.decide(
                LearningApplicationMutationRecoveryClassificationResult.Classified(summary)
            )
        )
    }

    private fun summary(
        noDownstream: Int = 0,
        exactDownstreamPresent: Int = 0,
        mismatchedDownstreamPresent: Int = 0
    ): LearningApplicationMutationRecoverySummary =
        LearningApplicationMutationRecoverySummary(
            noDownstream = noDownstream,
            exactDownstreamPresent = exactDownstreamPresent,
            mismatchedDownstreamPresent = mismatchedDownstreamPresent
        )
}
