package pro.liliya.core.cognitive

import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

class CognitiveRuntimeComposition(
    private val foundation: FoundationComposition,
    private val memoryRetrieval: MemoryRetrievalPort,
    private val knowledgeRetrieval: KnowledgeRetrievalPort,
    private val inference: CognitiveInferencePort,
    val limits: CognitiveRuntimeLimits = CognitiveRuntimeLimits(),
    registry: CognitiveTurnRegistry? = null
) {
    private val turns = registry ?: CognitiveTurnRegistry(limits)

    fun beginTurn(
        id: CognitiveTurnId,
        input: CognitiveInput
    ): CognitiveTurnRegistrationResult {
        val context = foundation.rootContext(
            operation = "beginCognitiveTurn",
            component = "CognitiveRuntime",
            metadata = mapOf("cognitiveTurnId" to id.value)
        )
        val result = turns.register(id, input)
        when (result) {
            is CognitiveTurnRegistrationResult.Registered -> foundation.observability.record(
                DiagnosticSeverity.INFO,
                "COGNITIVE_TURN_REGISTERED",
                "cognitive turn registered",
                context,
                mapOf(
                    "cognitiveTurnId" to id.value,
                    "cognitiveTurnGeneration" to result.ownership.reference.generation.value.toString()
                )
            )

            is CognitiveTurnRegistrationResult.Rejected -> foundation.observability.record(
                DiagnosticSeverity.WARNING,
                "COGNITIVE_TURN_REGISTRATION_REJECTED",
                "cognitive turn registration rejected",
                context,
                mapOf(
                    "cognitiveTurnId" to id.value,
                    "rejectionReason" to result.reason.name
                )
            )
        }
        return result
    }

    fun currentReference(): CognitiveTurnReference? = turns.currentReference()
    fun currentLifecycle(): CognitiveTurnLifecycle? = turns.currentLifecycle()
}
