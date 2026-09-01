package pro.liliya.core.cognitive

import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

class CognitiveRuntimeComposition(
    private val foundation: FoundationComposition,
    memoryRetrieval: MemoryRetrievalPort,
    knowledgeRetrieval: KnowledgeRetrievalPort,
    selfSnapshots: SelfSnapshotPort,
    personalitySnapshots: PersonalitySnapshotPort,
    private val inference: CognitiveInferencePort,
    val limits: CognitiveRuntimeLimits = CognitiveRuntimeLimits(),
    registry: CognitiveTurnRegistry? = null
) {
    private val turns = registry ?: CognitiveTurnRegistry(limits)
    private val contextAssembler = CognitiveContextAssembler(
        turns = turns,
        memoryRetrieval = memoryRetrieval,
        knowledgeRetrieval = knowledgeRetrieval,
        selfSnapshots = selfSnapshots,
        personalitySnapshots = personalitySnapshots,
        limits = limits
    )

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
                    "cognitiveTurnGeneration" to result.turn.reference.generation.value.toString()
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

    fun assembleContext(reference: CognitiveTurnReference): CognitiveContextAssemblyResult {
        val context = foundation.rootContext(
            operation = "assembleCognitiveContext",
            component = "CognitiveRuntime",
            metadata = mapOf(
                "cognitiveTurnId" to reference.id.value,
                "cognitiveTurnGeneration" to reference.generation.value.toString()
            )
        )
        val result = contextAssembler.assemble(reference)
        when (result) {
            is CognitiveContextAssemblyResult.Published -> foundation.observability.record(
                DiagnosticSeverity.INFO,
                "COGNITIVE_CONTEXT_PUBLISHED",
                "cognitive context published",
                context,
                mapOf("contextItemCount" to result.itemCount.toString())
            )

            CognitiveContextAssemblyResult.Stale -> foundation.observability.record(
                DiagnosticSeverity.WARNING,
                "COGNITIVE_CONTEXT_ASSEMBLY_STALE",
                "cognitive context assembly is no longer current",
                context
            )

            is CognitiveContextAssemblyResult.Rejected -> foundation.observability.record(
                DiagnosticSeverity.WARNING,
                "COGNITIVE_CONTEXT_ASSEMBLY_REJECTED",
                "cognitive context assembly rejected",
                context,
                mapOf("rejectionReason" to result.reason.name)
            )
        }
        return result
    }

    fun currentReference(): CognitiveTurnReference? = turns.currentReference()
    fun currentLifecycle(): CognitiveTurnLifecycle? = turns.currentLifecycle()
}
