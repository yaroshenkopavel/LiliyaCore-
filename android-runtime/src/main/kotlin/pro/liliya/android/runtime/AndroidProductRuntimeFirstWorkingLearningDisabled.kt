package pro.liliya.android.runtime

import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationFailure
import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationPort
import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationResult
import pro.liliya.core.cognitive.CognitiveLearningGovernancePort
import pro.liliya.core.cognitive.CognitiveLearningGovernanceResult

data class AndroidProductRuntimeFirstWorkingLearningOwners(
    val governance: CognitiveLearningGovernancePort,
    val learningMaterialization: CognitiveLearningApplicationMaterializationPort
)

/**
 * Temporary explicit product policy for First Working Liliya.
 *
 * Learning is deliberately disabled fail-closed. This is not a Core default and is not the final
 * self-learning policy. Replacing it requires a separate accepted product-policy gate.
 */
object AndroidProductRuntimeFirstWorkingLearningDisabled {
    internal const val REJECTION_RATIONALE =
        "learning disabled by first-working-liliya product policy"

    fun create(): AndroidProductRuntimeFirstWorkingLearningOwners =
        AndroidProductRuntimeFirstWorkingLearningOwners(
            governance = CognitiveLearningGovernancePort {
                governanceDecision()
            },
            learningMaterialization = CognitiveLearningApplicationMaterializationPort {
                materializationDecision()
            }
        )

    internal fun governanceDecision(): CognitiveLearningGovernanceResult =
        CognitiveLearningGovernanceResult.Rejected(REJECTION_RATIONALE)

    internal fun materializationDecision():
        CognitiveLearningApplicationMaterializationResult =
        CognitiveLearningApplicationMaterializationResult.Rejected(
            CognitiveLearningApplicationMaterializationFailure.MATERIALIZER_REJECTED
        )
}
