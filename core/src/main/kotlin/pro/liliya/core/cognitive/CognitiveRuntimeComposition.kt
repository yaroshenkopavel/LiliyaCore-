package pro.liliya.core.cognitive

import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.reasoning.ReasoningComposition

class CognitiveRuntimeComposition(
    private val foundation: FoundationComposition,
    memoryRetrieval: MemoryRetrievalPort,
    knowledgeRetrieval: KnowledgeRetrievalPort,
    selfSnapshots: SelfSnapshotPort,
    personalitySnapshots: PersonalitySnapshotPort,
    private val inference: CognitiveInferencePort,
    val limits: CognitiveRuntimeLimits = CognitiveRuntimeLimits(),
    registry: CognitiveTurnRegistry? = null,
    materialization: CognitiveMaterializationPort? = null,
    planning: PlanningComposition? = null,
    reasoning: ReasoningComposition? = null,
    decision: DecisionComposition? = null,
    artifactIds: CognitiveArtifactIdSource? = null,
    timestamps: CognitiveTimestampSource? = null
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
    private val generationCoordinator = if (
        materialization != null &&
        planning != null &&
        reasoning != null &&
        decision != null &&
        artifactIds != null &&
        timestamps != null
    ) {
        CognitiveGenerationCoordinator(
            turns = turns,
            inference = inference,
            materialization = materialization,
            planning = planning,
            reasoning = reasoning,
            decision = decision,
            artifactIds = artifactIds,
            timestamps = timestamps,
            limits = limits
        )
    } else {
        null
    }

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
            metadata = turnMetadata(reference)
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

    fun generateCognition(reference: CognitiveTurnReference): CognitiveGenerationResult {
        val context = foundation.rootContext(
            operation = "generateCognition",
            component = "CognitiveRuntime",
            metadata = turnMetadata(reference)
        )
        val coordinator = generationCoordinator
        if (coordinator == null) {
            foundation.observability.record(
                DiagnosticSeverity.WARNING,
                "COGNITIVE_GENERATION_REJECTED",
                "cognitive generation rejected",
                context,
                mapOf("rejectionReason" to CognitiveGenerationFailure.DEPENDENCIES_UNAVAILABLE.name)
            )
            return CognitiveGenerationResult.Rejected(
                CognitiveGenerationFailure.DEPENDENCIES_UNAVAILABLE
            )
        }

        val result = coordinator.generate(reference)
        when (result) {
            is CognitiveGenerationResult.Succeeded -> foundation.observability.record(
                DiagnosticSeverity.INFO,
                "COGNITIVE_GENERATION_PUBLISHED",
                "cognitive generation published",
                context,
                mapOf(
                    "planningProposalId" to result.planning.id.value,
                    "planningGeneration" to result.planning.generation.value.toString(),
                    "reasoningArtifactId" to result.reasoning.id.value,
                    "reasoningGeneration" to result.reasoning.generation.value.toString(),
                    "decisionId" to result.decision.id.value,
                    "decisionGeneration" to result.decision.generation.value.toString()
                )
            )

            CognitiveGenerationResult.Stale -> foundation.observability.record(
                DiagnosticSeverity.WARNING,
                "COGNITIVE_GENERATION_STALE",
                "cognitive generation is no longer current",
                context
            )

            is CognitiveGenerationResult.Rejected -> foundation.observability.record(
                DiagnosticSeverity.WARNING,
                "COGNITIVE_GENERATION_REJECTED",
                "cognitive generation rejected",
                context,
                mapOf("rejectionReason" to result.reason.name)
            )
        }
        return result
    }

    fun currentReference(): CognitiveTurnReference? = turns.currentReference()
    fun currentLifecycle(): CognitiveTurnLifecycle? = turns.currentLifecycle()

    private fun turnMetadata(reference: CognitiveTurnReference): Map<String, String> = mapOf(
        "cognitiveTurnId" to reference.id.value,
        "cognitiveTurnGeneration" to reference.generation.value.toString()
    )
}
