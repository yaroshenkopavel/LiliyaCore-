package pro.liliya.core.cognitive

import pro.liliya.core.identity.SelfIdentitySnapshot
import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.memory.MemoryRecordSnapshot
import pro.liliya.core.personality.PersonalityProfileSnapshot

class MemoryRetrievalRequest(
    val turn: CognitiveTurnReference,
    val input: CognitiveInput,
    val maxResults: Int
) {
    init { require(maxResults > 0) { "memory retrieval max results must be positive" } }
    override fun toString(): String =
        "MemoryRetrievalRequest(turn=$turn, input=<redacted>, maxResults=$maxResults)"
}

class MemoryRetrievalResult(
    items: List<MemoryRecordSnapshot>
) {
    val items: List<MemoryRecordSnapshot> = items.toList()
    override fun toString(): String = "MemoryRetrievalResult(items=<redacted:${items.size}>)"
}

fun interface MemoryRetrievalPort {
    fun retrieve(request: MemoryRetrievalRequest): MemoryRetrievalResult
}

class KnowledgeRetrievalRequest(
    val turn: CognitiveTurnReference,
    val input: CognitiveInput,
    val maxResults: Int
) {
    init { require(maxResults > 0) { "knowledge retrieval max results must be positive" } }
    override fun toString(): String =
        "KnowledgeRetrievalRequest(turn=$turn, input=<redacted>, maxResults=$maxResults)"
}

class KnowledgeRetrievalResult(
    items: List<KnowledgeItemSnapshot>
) {
    val items: List<KnowledgeItemSnapshot> = items.toList()
    override fun toString(): String = "KnowledgeRetrievalResult(items=<redacted:${items.size}>)"
}

fun interface KnowledgeRetrievalPort {
    fun retrieve(request: KnowledgeRetrievalRequest): KnowledgeRetrievalResult
}

/** Read-only view of the authoritative Self owner for one assembly attempt. */
fun interface SelfSnapshotPort {
    fun current(): SelfIdentitySnapshot?
}

/** Read-only view of authoritative Personality snapshots in their provider-defined deterministic order. */
fun interface PersonalitySnapshotPort {
    fun snapshot(): List<PersonalityProfileSnapshot>
}

class CognitiveInferenceRequest(
    val turn: CognitiveTurnReference,
    val input: CognitiveInput,
    val context: CognitiveContextSnapshot,
    val maxOutputChars: Int = CognitiveRuntimeLimits().maxInferenceOutputChars
) {
    init {
        require(context.turn == turn) { "cognitive inference context must belong to the same turn" }
        require(maxOutputChars > 0) { "cognitive inference output budget must be positive" }
    }

    override fun toString(): String =
        "CognitiveInferenceRequest(turn=$turn, input=<redacted>, context=$context, maxOutputChars=$maxOutputChars)"
}

enum class CognitiveInferenceFailure {
    PROVIDER_REJECTED,
    PROVIDER_FAILED,
    RESOURCE_LIMIT_REJECTED
}

sealed interface CognitiveInferenceResult {
    val turn: CognitiveTurnReference

    class Succeeded(
        override val turn: CognitiveTurnReference,
        val output: String
    ) : CognitiveInferenceResult {
        init { require(output.isNotBlank()) { "cognitive inference output must not be blank" } }
        override fun toString(): String =
            "Succeeded(turn=$turn, output=<redacted:${output.length}>)"
    }

    data class Rejected(
        override val turn: CognitiveTurnReference,
        val reason: CognitiveInferenceFailure
    ) : CognitiveInferenceResult
}

fun interface CognitiveInferencePort {
    fun infer(request: CognitiveInferenceRequest): CognitiveInferenceResult
}
