package pro.liliya.android.runtime

import kotlin.test.assertEquals
import org.junit.Test
import pro.liliya.core.learning.LearningApplicationMutationApplicationPort
import pro.liliya.core.learning.LearningApplicationMutationApplicationResult
import pro.liliya.core.learning.LearningApplicationMutationExactCompletionRecoveryPort
import pro.liliya.core.learning.LearningApplicationMutationExactCompletionRecoveryResult
import pro.liliya.core.learning.LearningApplicationMutationRecoveryClassificationPort
import pro.liliya.core.learning.LearningApplicationMutationRecoveryClassificationResult
import pro.liliya.core.learning.LearningApplicationMutationRecoveryCompletionEligibilityDecision
import pro.liliya.core.learning.LearningApplicationMutationRecoverySummary
import pro.liliya.core.learning.LearningApplicationMutationReference

class AndroidProductRuntimeLearningMutationRecoverySafetyContractTest {

    @Test
    fun clean_real_mutation_recovery_evidence_allows_rearm() {
        val safety = AndroidProductRuntimeLearningMutationRecoverySafety(
            classificationOnly(clean())
        )

        assertEquals(
            AndroidProductRuntimeLearningActivationRecoverySafetyResult.SafeToRearm,
            safety.evaluate(AndroidProductRuntimeLearningActivationJournalState.FAILED)
        )
    }

    @Test
    fun missing_recovery_classification_capability_blocks_rearm() {
        val safety = AndroidProductRuntimeLearningMutationRecoverySafety(
            LearningApplicationMutationApplicationPort {
                error("mutation must never run during activation recovery safety evaluation")
            }
        )

        assertEquals(
            AndroidProductRuntimeLearningActivationRecoverySafetyResult.Blocked,
            safety.evaluate(AndroidProductRuntimeLearningActivationJournalState.ACTIVATING)
        )
    }

    @Test
    fun recovery_requiring_fresh_authority_blocks_rearm() {
        val safety = AndroidProductRuntimeLearningMutationRecoverySafety(
            application(
                before = requiresRecovery(noDownstream = 1),
                recover = {
                    LearningApplicationMutationExactCompletionRecoveryResult.Blocked(
                        LearningApplicationMutationRecoveryCompletionEligibilityDecision
                            .REPLAY_REQUIRES_FRESH_AUTHORITY
                    )
                },
                after = clean()
            )
        )

        assertEquals(
            AndroidProductRuntimeLearningActivationRecoverySafetyResult.Blocked,
            safety.evaluate(AndroidProductRuntimeLearningActivationJournalState.RESTORING)
        )
    }

    @Test
    fun exact_completion_followed_by_clean_reclassification_allows_rearm() {
        var recoveryCalls = 0
        val safety = AndroidProductRuntimeLearningMutationRecoverySafety(
            application(
                before = requiresRecovery(exact = 1),
                recover = {
                    recoveryCalls += 1
                    LearningApplicationMutationExactCompletionRecoveryResult.Completed(emptyList())
                },
                after = clean()
            )
        )

        assertEquals(
            AndroidProductRuntimeLearningActivationRecoverySafetyResult.SafeToRearm,
            safety.evaluate(AndroidProductRuntimeLearningActivationJournalState.FAILED)
        )
        assertEquals(1, recoveryCalls)
    }

    @Test
    fun non_ambiguous_activation_states_are_never_rearmed_through_safety_adapter() {
        var classificationCalls = 0
        val safety = AndroidProductRuntimeLearningMutationRecoverySafety(
            object : LearningApplicationMutationApplicationPort,
                LearningApplicationMutationRecoveryClassificationPort {
                override fun apply(
                    reference: LearningApplicationMutationReference
                ): LearningApplicationMutationApplicationResult =
                    error("mutation must never run during safety evaluation")

                override fun classifyPreparedMutations():
                    LearningApplicationMutationRecoveryClassificationResult {
                    classificationCalls += 1
                    return clean()
                }
            }
        )

        assertEquals(
            AndroidProductRuntimeLearningActivationRecoverySafetyResult.Blocked,
            safety.evaluate(AndroidProductRuntimeLearningActivationJournalState.CLEAN)
        )
        assertEquals(
            AndroidProductRuntimeLearningActivationRecoverySafetyResult.Blocked,
            safety.evaluate(AndroidProductRuntimeLearningActivationJournalState.ACTIVATED)
        )
        assertEquals(0, classificationCalls)
    }

    private fun clean(): LearningApplicationMutationRecoveryClassificationResult =
        LearningApplicationMutationRecoveryClassificationResult.Classified(
            LearningApplicationMutationRecoverySummary.Clean
        )

    private fun requiresRecovery(
        noDownstream: Int = 0,
        exact: Int = 0,
        mismatched: Int = 0
    ): LearningApplicationMutationRecoveryClassificationResult =
        LearningApplicationMutationRecoveryClassificationResult.Classified(
            LearningApplicationMutationRecoverySummary(
                noDownstream = noDownstream,
                exactDownstreamPresent = exact,
                mismatchedDownstreamPresent = mismatched
            )
        )

    private fun classificationOnly(
        recovery: LearningApplicationMutationRecoveryClassificationResult
    ): LearningApplicationMutationApplicationPort =
        object : LearningApplicationMutationApplicationPort,
            LearningApplicationMutationRecoveryClassificationPort {
            override fun apply(
                reference: LearningApplicationMutationReference
            ): LearningApplicationMutationApplicationResult =
                error("mutation must never run during safety evaluation")

            override fun classifyPreparedMutations():
                LearningApplicationMutationRecoveryClassificationResult = recovery
        }

    private fun application(
        before: LearningApplicationMutationRecoveryClassificationResult,
        recover: () -> LearningApplicationMutationExactCompletionRecoveryResult,
        after: LearningApplicationMutationRecoveryClassificationResult
    ): LearningApplicationMutationApplicationPort {
        var classificationCalls = 0
        return object : LearningApplicationMutationApplicationPort,
            LearningApplicationMutationRecoveryClassificationPort,
            LearningApplicationMutationExactCompletionRecoveryPort {
            override fun apply(
                reference: LearningApplicationMutationReference
            ): LearningApplicationMutationApplicationResult =
                error("mutation must never run during safety evaluation")

            override fun classifyPreparedMutations():
                LearningApplicationMutationRecoveryClassificationResult =
                if (classificationCalls++ == 0) before else after

            override fun recoverExactCompletion():
                LearningApplicationMutationExactCompletionRecoveryResult = recover()
        }
    }
}
