package pro.liliya.android.llamacppengine

import android.content.Context
import pro.liliya.android.protectedmodel.enginesource.AndroidAppPrivateStagedModelEngineLoader
import pro.liliya.android.protectedmodel.staging.AndroidAppPrivateProtectedModelStagingBackend
import pro.liliya.android.protectedmodel.staging.AndroidProtectedModelStagingPolicy
import pro.liliya.core.cognitive.CognitiveInferencePort
import pro.liliya.core.cognitive.CognitiveModelRequestCompilerPort
import pro.liliya.core.cognitive.CognitiveModelRuntimeComposition
import pro.liliya.core.cognitive.CognitiveModelRuntimeSessionIdSource
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.CognitiveStagedModelActivationPort
import pro.liliya.core.cognitive.stagedModelActivationPort
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.modelengine.ModelEngineLoaderPort
import pro.liliya.core.modelengine.StagedModelEngineLoadCoordinator
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCoordinator
import pro.liliya.core.protectedmodel.ProtectedModelAccessCoordinator

/**
 * Android production composition for the staged protected-model -> llama.cpp -> Cognitive Runtime path.
 *
 * One exact app-private staging backend instance is shared by staging and engine-source validation.
 * Physical paths remain inside the Android staging/physical-loader boundary. Core receives only its
 * structural staging ownership and neutral model-engine contracts.
 */
class AndroidLlamaCppCognitiveModelAssembly private constructor(
    val stagingCoordinator: LargeProtectedModelStagingCoordinator,
    val cognitiveRuntime: CognitiveModelRuntimeComposition
) {
    val stagedActivation: CognitiveStagedModelActivationPort =
        cognitiveRuntime.stagedModelActivationPort()

    val inferencePort: CognitiveInferencePort = cognitiveRuntime.inferencePort

    companion object {
        fun create(
            context: Context,
            stagingPolicy: AndroidProtectedModelStagingPolicy,
            stagingBudgets: LargeProtectedModelStagingBudgets,
            llamaPolicy: LlamaCppEnginePolicy,
            foundation: FoundationComposition,
            protectedAccess: ProtectedModelAccessCoordinator,
            legacyEngineLoader: ModelEngineLoaderPort,
            compiler: CognitiveModelRequestCompilerPort,
            sessionIds: CognitiveModelRuntimeSessionIdSource,
            limits: CognitiveRuntimeLimits = CognitiveRuntimeLimits()
        ): AndroidLlamaCppCognitiveModelAssembly {
            val stagingBackend = AndroidAppPrivateProtectedModelStagingBackend(
                context = context.applicationContext,
                policy = stagingPolicy
            )
            val stagingCoordinator = LargeProtectedModelStagingCoordinator(
                backend = stagingBackend,
                budgets = stagingBudgets
            )
            val physicalLoader = AndroidLlamaCppPhysicalEngineLoader(llamaPolicy)
            val platformStagedLoader = AndroidAppPrivateStagedModelEngineLoader(
                stagingBackend = stagingBackend,
                physicalLoader = physicalLoader
            )
            val stagedEngineLoader = StagedModelEngineLoadCoordinator(
                stagingCoordinator = stagingCoordinator,
                loader = platformStagedLoader
            )
            val cognitiveRuntime = CognitiveModelRuntimeComposition(
                foundation = foundation,
                protectedAccess = protectedAccess,
                engineLoader = legacyEngineLoader,
                compiler = compiler,
                sessionIds = sessionIds,
                limits = limits,
                stagedEngineLoader = stagedEngineLoader
            )
            return AndroidLlamaCppCognitiveModelAssembly(
                stagingCoordinator = stagingCoordinator,
                cognitiveRuntime = cognitiveRuntime
            )
        }
    }
}
