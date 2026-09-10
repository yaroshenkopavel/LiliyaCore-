package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationFailure
import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationResult
import pro.liliya.core.cognitive.CognitiveLearningGovernanceResult
import pro.liliya.core.cognitive.CognitiveRuntimeLimits

class AndroidProductRuntimeDisabledLearningOwnersFactoryContractTest {
    @Test
    fun explicit_disabled_policy_is_fail_closed_for_governance_and_materialization() {
        val result = AndroidProductRuntimeDisabledLearningOwnersFactory.create(
            CognitiveRuntimeLimits()
        )

        assertIs<AndroidProductRuntimeDisabledLearningOwnersResult.Ready>(result)

        val governance = assertIs<CognitiveLearningGovernanceResult.Rejected>(
            AndroidProductRuntimeDisabledLearningOwnersFactory.disabledGovernanceDecision()
        )
        assertEquals(
            "learning disabled by explicit first-run product policy",
            governance.rationale
        )

        val materialization =
            assertIs<CognitiveLearningApplicationMaterializationResult.Rejected>(
                AndroidProductRuntimeDisabledLearningOwnersFactory
                    .disabledApplicationMaterializationDecision()
            )
        assertEquals(
            CognitiveLearningApplicationMaterializationFailure.MATERIALIZER_REJECTED,
            materialization.reason
        )
    }

    @Test
    fun rationale_budget_too_small_is_rejected_instead_of_truncating_policy_reason() {
        val result = AndroidProductRuntimeDisabledLearningOwnersFactory.create(
            CognitiveRuntimeLimits(
                maxLearningGovernanceRationaleChars = 8
            )
        )

        assertIs<AndroidProductRuntimeDisabledLearningOwnersResult.Rejected>(result)
    }
}
