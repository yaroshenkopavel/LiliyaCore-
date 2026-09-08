package pro.liliya.android.runtime

import pro.liliya.core.cognitive.CognitiveArtifactIdSource
import pro.liliya.core.cognitive.CognitiveMaterializationPort
import pro.liliya.core.cognitive.CognitiveOutcomeMaterializationPort
import pro.liliya.core.cognitive.CognitiveRuntimeComposition
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.CognitiveRuntimeScopeId
import pro.liliya.core.cognitive.CognitiveTimestampSource
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.reflection.ReflectionComposition

enum class AndroidHeartProductionPersonaRuntimeFactoryCreateFailure {
    PERSONA_REJECTED
}

sealed interface AndroidHeartProductionPersonaRuntimeFactoryCreateResult {
    data class Ready(
        val persona: AndroidHeartProductionPersonaComposition,
        val factory: AndroidHeartCognitiveRuntimeFactory
    ) : AndroidHeartProductionPersonaRuntimeFactoryCreateResult

    data class Rejected(
        val reason: AndroidHeartProductionPersonaRuntimeFactoryCreateFailure,
        val personaFailure: AndroidHeartProductionPersonaCreateFailure
    ) : AndroidHeartProductionPersonaRuntimeFactoryCreateResult
}

/**
 * Production Cognitive Runtime factory with mandatory exact Self/Personality snapshot wiring.
 *
 * Heart continues to own storage, semantic retrieval and model lifecycle. This composition owns
 * only product persona bootstrap and the already-required Cognitive construction policy.
 */
object AndroidHeartProductionPersonaRuntimeFactory {

    fun create(
        foundation: FoundationComposition,
        personaDefinition: AndroidHeartProductionPersonaDefinition,
        scope: CognitiveRuntimeScopeId,
        materialization: CognitiveMaterializationPort,
        planning: PlanningComposition,
        reasoning: ReasoningComposition,
        decision: DecisionComposition,
        artifactIds: CognitiveArtifactIdSource,
        timestamps: CognitiveTimestampSource,
        outcomeMaterialization: CognitiveOutcomeMaterializationPort,
        reflection: ReflectionComposition,
        learning: LearningComposition,
        limits: CognitiveRuntimeLimits = CognitiveRuntimeLimits(),
        personaLimits: AndroidHeartProductionPersonaLimits =
            AndroidHeartProductionPersonaLimits()
    ): AndroidHeartProductionPersonaRuntimeFactoryCreateResult {
        val persona = when (
            val result = AndroidHeartProductionPersonaBootstrap.create(
                foundation = foundation,
                definition = personaDefinition,
                limits = personaLimits
            )
        ) {
            is AndroidHeartProductionPersonaCreateResult.Ready -> result.composition
            is AndroidHeartProductionPersonaCreateResult.Rejected ->
                return AndroidHeartProductionPersonaRuntimeFactoryCreateResult.Rejected(
                    reason =
                        AndroidHeartProductionPersonaRuntimeFactoryCreateFailure.PERSONA_REJECTED,
                    personaFailure = result.reason
                )
        }

        val factory = AndroidHeartCognitiveRuntimeFactory {
                memoryRetrieval,
                knowledgeRetrieval,
                inference,
                streamingInference ->
            CognitiveRuntimeComposition(
                foundation = foundation,
                scope = scope,
                memoryRetrieval = memoryRetrieval,
                knowledgeRetrieval = knowledgeRetrieval,
                selfSnapshots = persona.selfSnapshots,
                personalitySnapshots = persona.personalitySnapshots,
                inference = inference,
                streamingInference = streamingInference,
                limits = limits,
                materialization = materialization,
                planning = planning,
                reasoning = reasoning,
                decision = decision,
                artifactIds = artifactIds,
                timestamps = timestamps,
                outcomeMaterialization = outcomeMaterialization,
                reflection = reflection,
                learning = learning
            )
        }

        return AndroidHeartProductionPersonaRuntimeFactoryCreateResult.Ready(
            persona = persona,
            factory = factory
        )
    }
}
