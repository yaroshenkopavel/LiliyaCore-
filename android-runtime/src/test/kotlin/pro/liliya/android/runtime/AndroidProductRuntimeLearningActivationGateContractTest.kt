package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class AndroidProductRuntimeLearningActivationGateContractTest {
    @Test
    fun incomplete_evidence_never_invokes_activation_and_preserves_exact_rejection() {
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

        cases.forEach { (candidate, expectedReason) ->
            var calls = 0
            val result = AndroidProductRuntimeLearningActivationGate.activate(candidate) {
                calls += 1
                Any()
            }

            val rejected = assertIs<
                AndroidProductRuntimeLearningActivationResult.EvidenceRejected
            >(result)
            assertEquals(expectedReason, rejected.reason)
            assertEquals(0, calls)
        }
    }

    @Test
    fun complete_evidence_invokes_activation_exactly_once() {
        var calls = 0
        val expected = Any()

        val result = AndroidProductRuntimeLearningActivationGate.activate(evidence()) {
            calls += 1
            expected
        }

        val activated = assertIs<AndroidProductRuntimeLearningActivationResult.Activated<Any>>(result)
        assertEquals(expected, activated.value)
        assertEquals(1, calls)
    }

    @Test
    fun activation_exception_fails_closed() {
        val result = AndroidProductRuntimeLearningActivationGate.activate(evidence()) {
            error("synthetic activation failure")
        }

        assertIs<AndroidProductRuntimeLearningActivationResult.ActivationFailed>(result)
    }

    @Test
    fun accepted_enablement_evidence_does_not_change_first_working_disabled_policy() {
        assertIs<AndroidProductRuntimeLearningActivationResult.Activated<Any>>(
            AndroidProductRuntimeLearningActivationGate.activate(evidence()) { Any() }
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
