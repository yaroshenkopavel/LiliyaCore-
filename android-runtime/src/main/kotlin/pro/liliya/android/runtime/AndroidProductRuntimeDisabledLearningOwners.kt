package pro.liliya.android.runtime

import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationFailure
import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationPort
import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationResult
import pro.liliya.core.cognitive.CognitiveLearningGovernancePort
import pro.liliya.core.cognitive.CognitiveLearningGovernanceResult
import pro.liliya.core.cognitive.CognitiveRuntimeLimits

data class AndroidProductRuntimeDisabledLearningOwners(
    val governance: CognitiveLearningGovernancePort,
    val applicationMaterialization: CognitiveLearningApplicationMaterializationPort
)

sealed interface AndroidProductRuntimeDisabledLearningOwnersResult {
    data class Ready(
        val owners: AndroidProductRuntimeDisabledLearningOwners
    ) : AndroidProductRuntimeDisabledLearningOwnersResult

    data object Rejected : AndroidProductRuntimeDisabledLearningOwnersResult
}

/**
 * Explicit fail-closed product policy for first working startup with learning disabled.
 *
 * Disabled Learning != Auto Approval.
 * Disabled Learning != Learning Materialization.
 * Disabled Learning != Permanent Product Policy.
 *
 * The caller must explicitly choose this owner set. Startup composition does not install it
 * implicitly. A later product-learning gate may replace it with a different trusted policy owner.
 */
object AndroidProductRuntimeDisabledLearningOwnersFactory {
    private const val DISABLED_RATIONALE =
        "learning disabled by explicit first-run product policy"

    fun create(
        limits: CognitiveRuntimeLimits
    ): AndroidProductRuntimeDisabledLearningOwnersResult {
        if (
            DISABLED_RATIONALE.length >
            limits.maxLearningGovernanceRationaleChars
        ) {
            return AndroidProductRuntimeDisabledLearningOwnersResult.Rejected
        }

        return AndroidProductRuntimeDisabledLearningOwnersResult.Ready(
            AndroidProductRuntimeDisabledLearningOwners(
                governance = CognitiveLearningGovernancePort {
                    disabledGovernanceDecision()
                },
                applicationMaterialization =
                    CognitiveLearningApplicationMaterializationPort {
                        disabledApplicationMaterializationDecision()
                    }
            )
        )
    }

    internal fun disabledGovernanceDecision(): CognitiveLearningGovernanceResult =
        CognitiveLearningGovernanceResult.Rejected(DISABLED_RATIONALE)

    internal fun disabledApplicationMaterializationDecision():
        CognitiveLearningApplicationMaterializationResult =
        CognitiveLearningApplicationMaterializationResult.Rejected(
            CognitiveLearningApplicationMaterializationFailure.MATERIALIZER_REJECTED
        )
}
