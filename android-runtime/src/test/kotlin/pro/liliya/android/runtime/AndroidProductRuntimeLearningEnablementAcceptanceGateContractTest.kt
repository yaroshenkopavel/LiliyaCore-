package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class AndroidProductRuntimeLearningEnablementAcceptanceGateContractTest {
    @Test
    fun every_missing_evidence_domain_fails_closed_with_exact_reason() {
        val accepted = evidence()
        val cases = listOf(
            accepted.copy(productPolicyApproved = false) to
                AndroidProductRuntimeLearningEnablementRejection.PRODUCT_POLICY_NOT_APPROVED,
            accepted.copy(poisoningResistanceAccepted = false) to
                AndroidProductRuntimeLearningEnablementRejection.POISONING_RESISTANCE_NOT_ACCEPTED,
            accepted.copy(freshAuthorityPerMutationAccepted = false) to
                AndroidProductRuntimeLearningEnablementRejection.FRESH_AUTHORITY_NOT_ACCEPTED,
            accepted.copy(rollbackCompensationAccepted = false) to
                AndroidProductRuntimeLearningEnablementRejection.ROLLBACK_COMPENSATION_NOT_ACCEPTED,
            accepted.copy(durableCrashSemanticsAccepted = false) to
                AndroidProductRuntimeLearningEnablementRejection.DURABLE_CRASH_SEMANTICS_NOT_ACCEPTED
        )

        cases.forEach { (candidate, expected) ->
            val rejected = assertIs<AndroidProductRuntimeLearningEnablementAcceptanceResult.Rejected>(
                AndroidProductRuntimeLearningEnablementAcceptanceGate.evaluate(candidate)
            )
            assertEquals(expected, rejected.reason)
        }
    }

    @Test
    fun complete_explicit_evidence_is_ready_but_does_not_replace_disabled_policy() {
        assertIs<AndroidProductRuntimeLearningEnablementAcceptanceResult.Ready>(
            AndroidProductRuntimeLearningEnablementAcceptanceGate.evaluate(evidence())
        )
        assertIs<pro.liliya.core.cognitive.CognitiveLearningGovernanceResult.Rejected>(
            AndroidProductRuntimeFirstWorkingLearningDisabled.governanceDecision()
        )
    }

    private fun evidence() = AndroidProductRuntimeLearningEnablementEvidence(
        productPolicyApproved = true,
        poisoningResistanceAccepted = true,
        freshAuthorityPerMutationAccepted = true,
        rollbackCompensationAccepted = true,
        durableCrashSemanticsAccepted = true
    )
}
