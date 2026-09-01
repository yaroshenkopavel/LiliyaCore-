package pro.liliya.core.cognitive

import pro.liliya.core.identity.SelfGeneration
import pro.liliya.core.identity.SelfIdentityId
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.personality.PersonalityGeneration
import pro.liliya.core.personality.PersonalityProfileId

@JvmInline
value class CognitiveRuntimeScopeId(val value: String) {
    init { require(value.isNotBlank()) { "cognitive runtime scope id must not be blank" } }
    override fun toString(): String = "CognitiveRuntimeScopeId([redacted])"
}

@JvmInline
value class CognitiveTurnId(val value: String) {
    init { require(value.isNotBlank()) { "cognitive turn id must not be blank" } }
    override fun toString(): String = "CognitiveTurnId([redacted])"
}

@JvmInline
value class CognitiveTurnGeneration(val value: Long) {
    init { require(value > 0L) { "cognitive turn generation must be positive" } }
    override fun toString(): String = value.toString()
}

data class CognitiveTurnReference(
    val id: CognitiveTurnId,
    val generation: CognitiveTurnGeneration
)

enum class CognitiveTurnLifecycle {
    CREATED,
    CONTEXT_READY,
    GENERATING,
    COGNITION_READY,
    COMPLETED,
    FAILED
}

enum class CognitiveTurnFailure {
    INPUT_REJECTED,
    CONTEXT_REJECTED,
    INFERENCE_REJECTED,
    PROVIDER_FAILED,
    TURN_FAILED
}

data class CognitiveRuntimeLimits(
    val maxRuntimeScopeIdChars: Int = 256,
    val maxTurnIdChars: Int = 512,
    val maxInputChars: Int = 16_384,
    val maxContextItems: Int = 64,
    val maxContextItemChars: Int = 16_384,
    val maxRetrievalResults: Int = 32,
    val maxInferenceOutputChars: Int = 32_768,
    val maxPlanningGoalChars: Int = 4_096,
    val maxPlanningSteps: Int = 32,
    val maxPlanningStepChars: Int = 4_096,
    val maxReasoningPremises: Int = 32,
    val maxReasoningPremiseChars: Int = 4_096,
    val maxReasoningAnalysisChars: Int = 16_384,
    val maxReasoningConclusionChars: Int = 4_096,
    val maxDecisionOptions: Int = 16,
    val maxDecisionOptionChars: Int = 4_096,
    val maxDecisionRationaleChars: Int = 8_192,
    val maxResultChars: Int = 16_384,
    val maxReflectionChars: Int = 16_384,
    val maxLearningProposalChars: Int = 16_384,
    val maxProvenanceReferenceChars: Int = 1_024
) {
    init {
        require(maxRuntimeScopeIdChars > 0) { "maximum cognitive runtime scope id chars must be positive" }
        require(maxTurnIdChars > 0) { "maximum cognitive turn id chars must be positive" }
        require(maxInputChars > 0) { "maximum cognitive input chars must be positive" }
        require(maxContextItems > 0) { "maximum cognitive context items must be positive" }
        require(maxContextItemChars > 0) { "maximum cognitive context item chars must be positive" }
        require(maxRetrievalResults > 0) { "maximum cognitive retrieval results must be positive" }
        require(maxInferenceOutputChars > 0) { "maximum cognitive inference output chars must be positive" }
        require(maxPlanningGoalChars > 0) { "maximum planning goal chars must be positive" }
        require(maxPlanningSteps > 0) { "maximum planning steps must be positive" }
        require(maxPlanningStepChars > 0) { "maximum planning step chars must be positive" }
        require(maxReasoningPremises > 0) { "maximum reasoning premises must be positive" }
        require(maxReasoningPremiseChars > 0) { "maximum reasoning premise chars must be positive" }
        require(maxReasoningAnalysisChars > 0) { "maximum reasoning analysis chars must be positive" }
        require(maxReasoningConclusionChars > 0) { "maximum reasoning conclusion chars must be positive" }
        require(maxDecisionOptions > 0) { "maximum decision options must be positive" }
        require(maxDecisionOptionChars > 0) { "maximum decision option chars must be positive" }
        require(maxDecisionRationaleChars > 0) { "maximum decision rationale chars must be positive" }
        require(maxResultChars > 0) { "maximum cognitive result chars must be positive" }
        require(maxReflectionChars > 0) { "maximum cognitive reflection chars must be positive" }
        require(maxLearningProposalChars > 0) { "maximum cognitive learning proposal chars must be positive" }
        require(maxProvenanceReferenceChars > 0) { "maximum cognitive provenance reference chars must be positive" }
    }
}

class CognitiveInput(
    val text: String
) {
    init { require(text.isNotBlank()) { "cognitive input text must not be blank" } }

    override fun equals(other: Any?): Boolean = other is CognitiveInput && text == other.text
    override fun hashCode(): Int = text.hashCode()
    override fun toString(): String = "CognitiveInput(text=<redacted:${text.length}>)"
}

sealed interface CognitiveContextSourceReference {
    data class Memory(
        val recordId: MemoryRecordId,
        val generation: MemoryGeneration
    ) : CognitiveContextSourceReference

    data class Knowledge(
        val itemId: KnowledgeItemId,
        val generation: KnowledgeGeneration
    ) : CognitiveContextSourceReference

    data class Self(
        val identityId: SelfIdentityId,
        val generation: SelfGeneration
    ) : CognitiveContextSourceReference

    data class Personality(
        val profileId: PersonalityProfileId,
        val generation: PersonalityGeneration
    ) : CognitiveContextSourceReference
}

class CognitiveContextItem(
    val source: CognitiveContextSourceReference,
    val content: String
) {
    init { require(content.isNotBlank()) { "cognitive context item content must not be blank" } }

    override fun equals(other: Any?): Boolean =
        other is CognitiveContextItem && source == other.source && content == other.content

    override fun hashCode(): Int = 31 * source.hashCode() + content.hashCode()
    override fun toString(): String = "CognitiveContextItem(source=$source, content=<redacted:${content.length}>)"
}

class CognitiveContextSnapshot(
    val turn: CognitiveTurnReference,
    items: List<CognitiveContextItem>
) {
    val items: List<CognitiveContextItem> = items.toList()

    override fun equals(other: Any?): Boolean =
        other is CognitiveContextSnapshot && turn == other.turn && items == other.items

    override fun hashCode(): Int = 31 * turn.hashCode() + items.hashCode()
    override fun toString(): String = "CognitiveContextSnapshot(turn=$turn, items=<redacted:${items.size}>)"
}
