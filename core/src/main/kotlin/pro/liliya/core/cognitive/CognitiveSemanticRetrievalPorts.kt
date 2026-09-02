package pro.liliya.core.cognitive

import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.memory.MemoryRecordSnapshot

/**
 * Structural failures in the advisory relevance layer.
 *
 * These failures do not grant or revoke Authority. They only prevent an invalid
 * advisory candidate set from being materialized into authoritative Cognitive context.
 */
enum class CognitiveRelevanceRetrievalFailure {
    MEMORY_CANDIDATE_LIMIT_EXCEEDED,
    KNOWLEDGE_CANDIDATE_LIMIT_EXCEEDED,
    MEMORY_DUPLICATE_ENTITY_CANDIDATE,
    KNOWLEDGE_DUPLICATE_ENTITY_CANDIDATE,
    MEMORY_RESOLVER_CONTRACT_VIOLATION,
    KNOWLEDGE_RESOLVER_CONTRACT_VIOLATION
}

class CognitiveRelevanceRetrievalException(
    val failure: CognitiveRelevanceRetrievalFailure
) : IllegalStateException(failure.name) {
    override fun toString(): String =
        "CognitiveRelevanceRetrievalException(failure=$failure)"
}

/**
 * MemoryRetrievalPort backed by advisory relevance discovery plus exact authoritative resolution.
 *
 * Provider order is preserved. Stale candidates are omitted. There is no retry, recency fallback,
 * candidate rebinding, or similarity-score interpretation in Core.
 */
class RelevanceMemoryRetrievalPort(
    private val discovery: MemoryRelevanceDiscoveryPort,
    private val resolver: MemoryAuthoritativeResolverPort,
    private val limits: CognitiveRelevanceRetrievalLimits
) : MemoryRetrievalPort {
    override fun retrieve(request: MemoryRetrievalRequest): MemoryRetrievalResult {
        val candidates = discovery.discover(
            MemoryRelevanceDiscoveryRequest(
                turn = request.turn,
                input = request.input,
                maxCandidates = limits.maxCandidatesPerSource
            )
        ).candidates

        validateMemoryCandidates(candidates)

        val resolved = ArrayList<MemoryRecordSnapshot>(minOf(request.maxResults, candidates.size))
        for (candidate in candidates) {
            when (val result = resolver.resolveExact(candidate)) {
                is MemoryAuthoritativeResolutionResult.Resolved -> {
                    val snapshot = result.snapshot
                    if (
                        snapshot.record.id != candidate.recordId ||
                        snapshot.generation != candidate.generation
                    ) {
                        throw CognitiveRelevanceRetrievalException(
                            CognitiveRelevanceRetrievalFailure.MEMORY_RESOLVER_CONTRACT_VIOLATION
                        )
                    }
                    resolved += snapshot
                    if (resolved.size == request.maxResults) break
                }

                MemoryAuthoritativeResolutionResult.Stale -> Unit
            }
        }

        return MemoryRetrievalResult(resolved)
    }

    private fun validateMemoryCandidates(candidates: List<MemoryRelevanceCandidate>) {
        if (candidates.size > limits.maxCandidatesPerSource) {
            throw CognitiveRelevanceRetrievalException(
                CognitiveRelevanceRetrievalFailure.MEMORY_CANDIDATE_LIMIT_EXCEEDED
            )
        }

        val seen = HashSet<Any>(candidates.size)
        for (candidate in candidates) {
            if (!seen.add(candidate.recordId)) {
                throw CognitiveRelevanceRetrievalException(
                    CognitiveRelevanceRetrievalFailure.MEMORY_DUPLICATE_ENTITY_CANDIDATE
                )
            }
        }
    }
}

/**
 * KnowledgeRetrievalPort backed by advisory relevance discovery plus exact authoritative resolution.
 *
 * Provider order is preserved. Stale candidates are omitted. There is no retry, recency fallback,
 * candidate rebinding, or similarity-score interpretation in Core.
 */
class RelevanceKnowledgeRetrievalPort(
    private val discovery: KnowledgeRelevanceDiscoveryPort,
    private val resolver: KnowledgeAuthoritativeResolverPort,
    private val limits: CognitiveRelevanceRetrievalLimits
) : KnowledgeRetrievalPort {
    override fun retrieve(request: KnowledgeRetrievalRequest): KnowledgeRetrievalResult {
        val candidates = discovery.discover(
            KnowledgeRelevanceDiscoveryRequest(
                turn = request.turn,
                input = request.input,
                maxCandidates = limits.maxCandidatesPerSource
            )
        ).candidates

        validateKnowledgeCandidates(candidates)

        val resolved = ArrayList<KnowledgeItemSnapshot>(minOf(request.maxResults, candidates.size))
        for (candidate in candidates) {
            when (val result = resolver.resolveExact(candidate)) {
                is KnowledgeAuthoritativeResolutionResult.Resolved -> {
                    val snapshot = result.snapshot
                    if (
                        snapshot.item.id != candidate.itemId ||
                        snapshot.generation != candidate.generation
                    ) {
                        throw CognitiveRelevanceRetrievalException(
                            CognitiveRelevanceRetrievalFailure.KNOWLEDGE_RESOLVER_CONTRACT_VIOLATION
                        )
                    }
                    resolved += snapshot
                    if (resolved.size == request.maxResults) break
                }

                KnowledgeAuthoritativeResolutionResult.Stale -> Unit
            }
        }

        return KnowledgeRetrievalResult(resolved)
    }

    private fun validateKnowledgeCandidates(candidates: List<KnowledgeRelevanceCandidate>) {
        if (candidates.size > limits.maxCandidatesPerSource) {
            throw CognitiveRelevanceRetrievalException(
                CognitiveRelevanceRetrievalFailure.KNOWLEDGE_CANDIDATE_LIMIT_EXCEEDED
            )
        }

        val seen = HashSet<Any>(candidates.size)
        for (candidate in candidates) {
            if (!seen.add(candidate.itemId)) {
                throw CognitiveRelevanceRetrievalException(
                    CognitiveRelevanceRetrievalFailure.KNOWLEDGE_DUPLICATE_ENTITY_CANDIDATE
                )
            }
        }
    }
}
