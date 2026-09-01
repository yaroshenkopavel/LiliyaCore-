package pro.liliya.core.cognitive

import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.reflection.ReflectionComposition

class CognitiveRuntimeComposition(
    private val foundation: FoundationComposition,
    private val scope: CognitiveRuntimeScopeId,
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
    timestamps: CognitiveTimestampSource? = null,
    outcomeMaterialization: CognitiveOutcomeMaterializationPort? = null,
    reflection: ReflectionComposition? = null,
    learning: LearningComposition? = null
) {
    init {
        require(scope.value.length <= limits.maxRuntimeScopeIdChars) {
            "cognitive runtime scope id exceeds configured limit"
        }
    }

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
            scope = scope,
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
    private val finalizationCoordinator = if (
        outcomeMaterialization != null &&
        planning != null &&
        reasoning != null &&
        decision != null &&
        reflection != null &&
        learning != null &&
        artifactIds != null &&
        timestamps != null
    ) {
        CognitiveFinalizationCoordinator(
            turns = turns,
            scope = scope,
            outcomeMaterialization = outcomeMaterialization,
            planning = planning,
            reasoning = reasoning,
            decision = decision,
            reflection = reflection,
            learning = learning,
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
        val requestFingerprint = CognitiveProvenance.requestFingerprint(scope, id)
        val context = foundation.rootContext(
            operation = "beginCognitiveTurn",
            component = "CognitiveRuntime",
            metadata = mapOf("cognitiveTurnRequestFingerprint" to requestFingerprint)
        )
        val result = turns.register(id, input)
        when (result) {
            is CognitiveTurnRegistrationResult.Registered -> {
                val token = CognitiveProvenance.turnToken(scope, result.turn.reference).value
                foundation.observability.record(
                    DiagnosticSeverity.INFO,
                    "COGNITIVE_TURN_REGISTERED",
                    "cognitive turn registered",
                    context,
                    mapOf(
                        "cognitiveTurnProvenance" to token,
                        "cognitiveTurnGeneration" to result.turn.reference.generation.value.toString()
                    )
                )
            }

            is CognitiveTurnRegistrationResult.Rejected -> foundation.observability.record(
                DiagnosticSeverity.WARNING,
                "COGNITIVE_TURN_REGISTRATION_REJECTED",
                "cognitive turn registration rejected",
                context,
                mapOf(
                    "cognitiveTurnRequestFingerprint" to requestFingerprint,
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

    fun finalizeCognition(reference: CognitiveTurnReference): CognitiveFinalizationResult {
        val context = foundation.rootContext(
            operation = "finalizeCognition",
            component = "CognitiveRuntime",
            metadata = turnMetadata(reference)
        )
        val coordinator = finalizationCoordinator
        if (coordinator == null) {
            foundation.observability.record(
                DiagnosticSeverity.WARNING,
                "COGNITIVE_FINALIZATION_REJECTED",
                "cognitive finalization rejected",
                context,
                mapOf("rejectionReason" to CognitiveFinalizationFailure.DEPENDENCIES_UNAVAILABLE.name)
            )
            return CognitiveFinalizationResult.Rejected(
                CognitiveFinalizationFailure.DEPENDENCIES_UNAVAILABLE
            )
        }

        val result = coordinator.finalize(reference)
        when (result) {
            is CognitiveFinalizationResult.Completed -> foundation.observability.record(
                DiagnosticSeverity.INFO,
                "COGNITIVE_FINALIZATION_COMPLETED",
                "cognitive finalization completed",
                context,
                mapOf(
                    "reflectionRecordId" to result.reflection.id.value,
                    "reflectionGeneration" to result.reflection.generation.value.toString(),
                    "learningCandidateId" to result.learning.id.value,
                    "learningGeneration" to result.learning.generation.value.toString()
                )
            )

            CognitiveFinalizationResult.Stale -> foundation.observability.record(
                DiagnosticSeverity.WARNING,
                "COGNITIVE_FINALIZATION_STALE",
                "cognitive finalization is no longer current",
                context
            )

            is CognitiveFinalizationResult.Rejected -> foundation.observability.record(
                DiagnosticSeverity.WARNING,
                "COGNITIVE_FINALIZATION_REJECTED",
                "cognitive finalization rejected",
                context,
                mapOf("rejectionReason" to result.reason.name)
            )
        }
        return result
    }

    fun currentReference(): CognitiveTurnReference? = turns.currentReference()
    fun currentLifecycle(): CognitiveTurnLifecycle? = turns.currentLifecycle()

    private fun turnMetadata(reference: CognitiveTurnReference): Map<String, String> = mapOf(
        "cognitiveTurnProvenance" to CognitiveProvenance.turnToken(scope, reference).value,
        "cognitiveTurnGeneration" to reference.generation.value.toString()
    )
}
