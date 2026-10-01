package pro.liliya.core.cognitive

import pro.liliya.core.knowledge.KnowledgeComposition
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.knowledge.PersistentKnowledgeComposition
import pro.liliya.core.knowledge.PersistentKnowledgeInspectResult
import pro.liliya.core.memory.MemoryComposition
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemoryRecordSnapshot
import pro.liliya.core.memory.PersistentMemoryComposition
import pro.liliya.core.memory.PersistentMemoryInspectResult

/**
 * Advisory semantic/relevance candidate for one exact observed Memory generation.
 *
 * A candidate is selection evidence only. It is not an authoritative Memory snapshot,
 * mutation ownership, License, Authority or execution permission.
 */
data class MemoryRelevanceCandidate(
    val recordId: MemoryRecordId,
    val generation: MemoryGeneration
) {
    override fun toString(): String =
        "MemoryRelevanceCandidate(recordId=<redacted>, generation=$generation)"
}

/** Advisory semantic/relevance candidate for one exact observed Knowledge generation. */
data class KnowledgeRelevanceCandidate(
    val itemId: KnowledgeItemId,
    val generation: KnowledgeGeneration
) {
    override fun toString(): String =
        "KnowledgeRelevanceCandidate(itemId=<redacted>, generation=$generation)"
}

data class MemoryRelevanceDiscoveryRequest(
    val turn: CognitiveTurnReference,
    val input: CognitiveInput,
    val maxCandidates: Int
) {
    init {
        require(maxCandidates > 0) { "maximum Memory relevance candidates must be positive" }
    }

    override fun toString(): String =
        "MemoryRelevanceDiscoveryRequest(turn=$turn, input=<redacted>, maxCandidates=$maxCandidates)"
}

data class KnowledgeRelevanceDiscoveryRequest(
    val turn: CognitiveTurnReference,
    val input: CognitiveInput,
    val maxCandidates: Int
) {
    init {
        require(maxCandidates > 0) { "maximum Knowledge relevance candidates must be positive" }
    }

    override fun toString(): String =
        "KnowledgeRelevanceDiscoveryRequest(turn=$turn, input=<redacted>, maxCandidates=$maxCandidates)"
}

class MemoryRelevanceDiscoveryResult(
    candidates: List<MemoryRelevanceCandidate>
) {
    val candidates: List<MemoryRelevanceCandidate> = candidates.toList()

    override fun equals(other: Any?): Boolean =
        other is MemoryRelevanceDiscoveryResult && candidates == other.candidates

    override fun hashCode(): Int = candidates.hashCode()

    override fun toString(): String =
        "MemoryRelevanceDiscoveryResult(candidates=<redacted:${candidates.size}>)"
}

class KnowledgeRelevanceDiscoveryResult(
    candidates: List<KnowledgeRelevanceCandidate>
) {
    val candidates: List<KnowledgeRelevanceCandidate> = candidates.toList()

    override fun equals(other: Any?): Boolean =
        other is KnowledgeRelevanceDiscoveryResult && candidates == other.candidates

    override fun hashCode(): Int = candidates.hashCode()

    override fun toString(): String =
        "KnowledgeRelevanceDiscoveryResult(candidates=<redacted:${candidates.size}>)"
}

fun interface MemoryRelevanceDiscoveryPort {
    fun discover(request: MemoryRelevanceDiscoveryRequest): MemoryRelevanceDiscoveryResult
}

fun interface KnowledgeRelevanceDiscoveryPort {
    fun discover(request: KnowledgeRelevanceDiscoveryRequest): KnowledgeRelevanceDiscoveryResult
}

data class CognitiveRelevanceRetrievalLimits(
    val maxCandidatesPerSource: Int
) {
    init {
        require(maxCandidatesPerSource > 0) {
            "maximum relevance candidates per source must be positive"
        }
    }
}

sealed interface MemoryAuthoritativeResolutionResult {
    data class Resolved(
        val snapshot: MemoryRecordSnapshot
    ) : MemoryAuthoritativeResolutionResult

    data object Stale : MemoryAuthoritativeResolutionResult
}

sealed interface KnowledgeAuthoritativeResolutionResult {
    data class Resolved(
        val snapshot: KnowledgeItemSnapshot
    ) : KnowledgeAuthoritativeResolutionResult

    data object Stale : KnowledgeAuthoritativeResolutionResult
}

fun interface MemoryAuthoritativeResolverPort {
    fun resolveExact(candidate: MemoryRelevanceCandidate): MemoryAuthoritativeResolutionResult
}

fun interface KnowledgeAuthoritativeResolverPort {
    fun resolveExact(candidate: KnowledgeRelevanceCandidate): KnowledgeAuthoritativeResolutionResult
}

class MemoryCompositionAuthoritativeResolver(
    private val memory: MemoryComposition
) : MemoryAuthoritativeResolverPort {
    override fun resolveExact(
        candidate: MemoryRelevanceCandidate
    ): MemoryAuthoritativeResolutionResult =
        resolveMemory(candidate, memory.inspect(candidate.recordId))
}

class PersistentMemoryCompositionAuthoritativeResolver(
    private val memory: PersistentMemoryComposition
) : MemoryAuthoritativeResolverPort {
    override fun resolveExact(
        candidate: MemoryRelevanceCandidate
    ): MemoryAuthoritativeResolutionResult =
        when (val inspected = memory.inspectResult(candidate.recordId)) {
            PersistentMemoryInspectResult.Missing -> MemoryAuthoritativeResolutionResult.Stale
            is PersistentMemoryInspectResult.Found -> resolveMemory(candidate, inspected.snapshot)
            PersistentMemoryInspectResult.Corrupt ->
                throw IllegalStateException("persistent Memory exact read is corrupt")
            is PersistentMemoryInspectResult.Incompatible ->
                throw IllegalStateException(inspected.reason)
            is PersistentMemoryInspectResult.Failed ->
                throw IllegalStateException(inspected.reason, inspected.throwable)
        }
}

class KnowledgeCompositionAuthoritativeResolver(
    private val knowledge: KnowledgeComposition
) : KnowledgeAuthoritativeResolverPort {
    override fun resolveExact(
        candidate: KnowledgeRelevanceCandidate
    ): KnowledgeAuthoritativeResolutionResult =
        resolveKnowledge(candidate, knowledge.inspect(candidate.itemId))
}

class PersistentKnowledgeCompositionAuthoritativeResolver(
    private val knowledge: PersistentKnowledgeComposition
) : KnowledgeAuthoritativeResolverPort {
    override fun resolveExact(
        candidate: KnowledgeRelevanceCandidate
    ): KnowledgeAuthoritativeResolutionResult =
        when (val inspected = knowledge.inspectResult(candidate.itemId)) {
            PersistentKnowledgeInspectResult.Missing -> KnowledgeAuthoritativeResolutionResult.Stale
            is PersistentKnowledgeInspectResult.Found -> resolveKnowledge(candidate, inspected.snapshot)
            PersistentKnowledgeInspectResult.Corrupt ->
                throw IllegalStateException("persistent Knowledge exact read is corrupt")
            is PersistentKnowledgeInspectResult.Incompatible ->
                throw IllegalStateException(inspected.reason)
            is PersistentKnowledgeInspectResult.Failed ->
                throw IllegalStateException(inspected.reason, inspected.throwable)
        }
}

private fun resolveMemory(
    candidate: MemoryRelevanceCandidate,
    current: MemoryRecordSnapshot?
): MemoryAuthoritativeResolutionResult {
    if (current == null) return MemoryAuthoritativeResolutionResult.Stale
    if (current.record.id != candidate.recordId || current.generation != candidate.generation) {
        return MemoryAuthoritativeResolutionResult.Stale
    }
    return MemoryAuthoritativeResolutionResult.Resolved(current)
}

private fun resolveKnowledge(
    candidate: KnowledgeRelevanceCandidate,
    current: KnowledgeItemSnapshot?
): KnowledgeAuthoritativeResolutionResult {
    if (current == null) return KnowledgeAuthoritativeResolutionResult.Stale
    if (current.item.id != candidate.itemId || current.generation != candidate.generation) {
        return KnowledgeAuthoritativeResolutionResult.Stale
    }
    return KnowledgeAuthoritativeResolutionResult.Resolved(current)
}
