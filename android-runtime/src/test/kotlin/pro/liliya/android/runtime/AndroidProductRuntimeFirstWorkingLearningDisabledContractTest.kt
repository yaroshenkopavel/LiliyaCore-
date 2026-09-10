package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationFailure
import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationResult
import pro.liliya.core.cognitive.CognitiveLearningGovernanceResult

class AndroidProductRuntimeFirstWorkingLearningDisabledContractTest {
    @Test
    fun governance_is_always_fail_closed_with_fixed_bounded_rationale() {
        val result = AndroidProductRuntimeFirstWorkingLearningDisabled.governanceDecision()

        val rejected = assertIs<CognitiveLearningGovernanceResult.Rejected>(result)
        assertEquals(
            "learning disabled by first-working-liliya product policy",
            rejected.rationale
        )
        assertEquals(
            AndroidProductRuntimeFirstWorkingLearningDisabled.REJECTION_RATIONALE,
            rejected.rationale
        )
    }

    @Test
    fun learning_materialization_is_disabled_fail_closed() {
        val result = AndroidProductRuntimeFirstWorkingLearningDisabled.materializationDecision()

        val rejected =
            assertIs<CognitiveLearningApplicationMaterializationResult.Rejected>(result)
        assertEquals(
            CognitiveLearningApplicationMaterializationFailure.MATERIALIZER_REJECTED,
            rejected.reason
        )
    }

    @Test
    fun product_owners_are_explicit_and_separate_ports() {
        val owners = AndroidProductRuntimeFirstWorkingLearningDisabled.create()

        assertIs<pro.liliya.core.cognitive.CognitiveLearningGovernancePort>(
            owners.governance
        )
        assertIs<pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationPort>(
            owners.learningMaterialization
        )
    }
}
