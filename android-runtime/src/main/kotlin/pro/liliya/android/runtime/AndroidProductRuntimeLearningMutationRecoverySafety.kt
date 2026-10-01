package pro.liliya.android.runtime

import pro.liliya.core.learning.LearningApplicationMutationApplicationPort

/**
 * Product recovery-safety adapter backed by the real governed-learning mutation recovery contract.
 *
 * Re-arming an ambiguous activation is allowed only when the existing production mutation recovery
 * admission reports a clean state (including any exact-completion recovery it can safely perform).
 * This adapter does not grant Authority, bypass authorization, or apply a new mutation.
 */
class AndroidProductRuntimeLearningMutationRecoverySafety(
    private val mutationApplication: LearningApplicationMutationApplicationPort
) : AndroidProductRuntimeLearningActivationRecoverySafetyPort {

    override fun evaluate(
        interruptedState: AndroidProductRuntimeLearningActivationJournalState
    ): AndroidProductRuntimeLearningActivationRecoverySafetyResult {
        if (
            interruptedState == AndroidProductRuntimeLearningActivationJournalState.CLEAN ||
            interruptedState == AndroidProductRuntimeLearningActivationJournalState.ACTIVATED
        ) {
            return AndroidProductRuntimeLearningActivationRecoverySafetyResult.Blocked
        }

        return if (
            AndroidHeartProductionGovernedLearningAssembly.recoveryAdmissionFailure(
                mutationApplication
            ) == null
        ) {
            AndroidProductRuntimeLearningActivationRecoverySafetyResult.SafeToRearm
        } else {
            AndroidProductRuntimeLearningActivationRecoverySafetyResult.Blocked
        }
    }

    override fun toString(): String =
        "AndroidProductRuntimeLearningMutationRecoverySafety(mutationApplication=<redacted>)"
}
