package pro.liliya.android.runtime

/**
 * Explicit product-policy evidence required before First Working Liliya may replace the
 * fail-closed learning-disabled owners with governed learning.
 *
 * Evidence != Authority. This gate never creates Authority, grants permissions, installs a
 * learning composition, or enables mutation. It only validates that the trusted product owner
 * has accepted every prerequisite independently.
 */
data class AndroidProductRuntimeLearningEnablementEvidence(
    val productPolicyApproved: Boolean,
    val poisoningResistanceAccepted: Boolean,
    val freshAuthorityPerMutationAccepted: Boolean,
    val rollbackCompensationAccepted: Boolean,
    val durableCrashSemanticsAccepted: Boolean
)

enum class AndroidProductRuntimeLearningEnablementRejection {
    PRODUCT_POLICY_NOT_APPROVED,
    POISONING_RESISTANCE_NOT_ACCEPTED,
    FRESH_AUTHORITY_NOT_ACCEPTED,
    ROLLBACK_COMPENSATION_NOT_ACCEPTED,
    DURABLE_CRASH_SEMANTICS_NOT_ACCEPTED
}

sealed interface AndroidProductRuntimeLearningEnablementAcceptanceResult {
    data object Ready : AndroidProductRuntimeLearningEnablementAcceptanceResult

    data class Rejected(
        val reason: AndroidProductRuntimeLearningEnablementRejection
    ) : AndroidProductRuntimeLearningEnablementAcceptanceResult
}

object AndroidProductRuntimeLearningEnablementAcceptanceGate {
    fun evaluate(
        evidence: AndroidProductRuntimeLearningEnablementEvidence
    ): AndroidProductRuntimeLearningEnablementAcceptanceResult {
        val rejection = when {
            !evidence.productPolicyApproved ->
                AndroidProductRuntimeLearningEnablementRejection.PRODUCT_POLICY_NOT_APPROVED
            !evidence.poisoningResistanceAccepted ->
                AndroidProductRuntimeLearningEnablementRejection.POISONING_RESISTANCE_NOT_ACCEPTED
            !evidence.freshAuthorityPerMutationAccepted ->
                AndroidProductRuntimeLearningEnablementRejection.FRESH_AUTHORITY_NOT_ACCEPTED
            !evidence.rollbackCompensationAccepted ->
                AndroidProductRuntimeLearningEnablementRejection.ROLLBACK_COMPENSATION_NOT_ACCEPTED
            !evidence.durableCrashSemanticsAccepted ->
                AndroidProductRuntimeLearningEnablementRejection.DURABLE_CRASH_SEMANTICS_NOT_ACCEPTED
            else -> null
        }
        return rejection?.let(AndroidProductRuntimeLearningEnablementAcceptanceResult::Rejected)
            ?: AndroidProductRuntimeLearningEnablementAcceptanceResult.Ready
    }
}
