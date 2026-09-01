package pro.liliya.core.cognitive

import java.time.Instant
import pro.liliya.core.decision.DecisionSnapshot
import pro.liliya.core.learning.LearningGeneration
import pro.liliya.core.learning.LearningCandidateId
import pro.liliya.core.planning.PlanningProposalSnapshot
import pro.liliya.core.reasoning.ReasoningArtifactSnapshot
import pro.liliya.core.reflection.ReflectionGeneration
import pro.liliya.core.reflection.ReflectionRecordId

data class CognitiveOutcomeBudgets(
    val maxResultChars: Int,
    val maxReflectionChars: Int,
    val maxLearningProposalChars: Int
) {
    init {
        require(maxResultChars > 0)
        require(maxReflectionChars > 0)
        require(maxLearningProposalChars > 0)
    }

    companion object {
        fun from(limits: CognitiveRuntimeLimits): CognitiveOutcomeBudgets = CognitiveOutcomeBudgets(
            maxResultChars = limits.maxResultChars,
            maxReflectionChars = limits.maxReflectionChars,
            maxLearningProposalChars = limits.maxLearningProposalChars
        )
    }
}

class CognitiveOutcomeMaterializationRequest(
    val turn: CognitiveTurnReference,
    val planning: PlanningProposalSnapshot,
    val reasoning: ReasoningArtifactSnapshot,
    val decision: DecisionSnapshot,
    val inferenceOutput: String,
    val budgets: CognitiveOutcomeBudgets
) {
    init { require(inferenceOutput.isNotBlank()) { "outcome inference output must not be blank" } }

    override fun toString(): String =
        "CognitiveOutcomeMaterializationRequest(" +
            "turn=$turn, planning=<structural:${planning.proposal.id}:${planning.generation}>, " +
            "reasoning=<structural:${reasoning.artifact.id}:${reasoning.generation}>, " +
            "decision=<structural:${decision.decision.id}:${decision.generation}>, " +
            "inferenceOutput=<redacted:${inferenceOutput.length}>, budgets=$budgets)"
}

class CognitiveOutcomeCandidate(
    val resultContent: String,
    val reflectionContent: String,
    val learningProposal: String
) {
    init {
        require(resultContent.isNotBlank()) { "cognitive result content must not be blank" }
        require(reflectionContent.isNotBlank()) { "cognitive reflection content must not be blank" }
        require(learningProposal.isNotBlank()) { "cognitive learning proposal must not be blank" }
    }

    override fun toString(): String =
        "CognitiveOutcomeCandidate(resultContent=<redacted:${resultContent.length}>, " +
            "reflectionContent=<redacted:${reflectionContent.length}>, " +
            "learningProposal=<redacted:${learningProposal.length}>)"
}

enum class CognitiveOutcomeMaterializationFailure {
    MATERIALIZER_REJECTED,
    MATERIALIZER_FAILED,
    RESOURCE_LIMIT_REJECTED,
    STRUCTURE_REJECTED
}

sealed interface CognitiveOutcomeMaterializationResult {
    data class Succeeded(val candidate: CognitiveOutcomeCandidate) : CognitiveOutcomeMaterializationResult
    data class Rejected(val reason: CognitiveOutcomeMaterializationFailure) : CognitiveOutcomeMaterializationResult
}

fun interface CognitiveOutcomeMaterializationPort {
    fun materialize(request: CognitiveOutcomeMaterializationRequest): CognitiveOutcomeMaterializationResult
}

class CognitiveResult(
    val turn: CognitiveTurnReference,
    val planning: PlanningReference,
    val reasoning: ReasoningReference,
    val decision: DecisionReference,
    val content: String,
    val createdAt: Instant
) {
    init { require(content.isNotBlank()) { "cognitive result content must not be blank" } }

    override fun equals(other: Any?): Boolean =
        other is CognitiveResult &&
            turn == other.turn && planning == other.planning && reasoning == other.reasoning &&
            decision == other.decision && content == other.content && createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = turn.hashCode()
        result = 31 * result + planning.hashCode()
        result = 31 * result + reasoning.hashCode()
        result = 31 * result + decision.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "CognitiveResult(turn=$turn, planning=$planning, reasoning=$reasoning, decision=$decision, " +
            "content=<redacted:${content.length}>, createdAt=$createdAt)"
}

data class CognitiveReflectionReference(
    val id: ReflectionRecordId,
    val generation: ReflectionGeneration
)

data class CognitiveLearningReference(
    val id: LearningCandidateId,
    val generation: LearningGeneration
)

enum class CognitiveFinalizationFailure {
    DEPENDENCIES_UNAVAILABLE,
    FINALIZATION_IN_PROGRESS,
    ACCEPTED_COGNITION_MISSING,
    ACCEPTED_COGNITION_MISMATCH,
    OUTCOME_MATERIALIZER_FAILED,
    OUTCOME_MATERIALIZER_REJECTED,
    OUTCOME_LIMIT_REJECTED,
    ARTIFACT_ID_OR_TIME_FAILED,
    ARTIFACT_ID_COLLISION,
    REFLECTION_INSTALL_FAILED,
    REFLECTION_MISMATCH,
    LEARNING_INSTALL_FAILED,
    LEARNING_MISMATCH,
    TERMINAL_COMPLETION_FAILED,
    COMPENSATION_FAILED
}

sealed interface CognitiveFinalizationResult {
    data class Completed(
        val result: CognitiveResult,
        val reflection: CognitiveReflectionReference,
        val learning: CognitiveLearningReference
    ) : CognitiveFinalizationResult

    data object Stale : CognitiveFinalizationResult
    data class Rejected(val reason: CognitiveFinalizationFailure) : CognitiveFinalizationResult
}
