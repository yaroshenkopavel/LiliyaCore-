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
    MEMORY_CANDIDATE_BOUND_INSUFFICIENT,
    KNOWLEDGE_CANDIDATE_BOUND_INSUFFICIENT,
    MEMORY_DISCOVERY_PROVIDER_FAILED,
    KNOWLEDGE_DISCOVERY_PROVIDER_FAILED,
    MEMORY_CANDIDATE_LIMIT_EXCEEDED,
    KNOWLEDGE_CANDIDATE_LIMIT_EXCEEDED,
    MEMORY_DUPLICATE_ENTITY_CANDIDATE,
    KNOWLEDGE_DUPLICATE_ENTITY_CANDIDATE,
    MEMORY_RESOLVER_PROVIDER_FAILED,
    KNOWLEDGE_RESOLVER_PROVIDER_FAILED,
    MEMORY_RESOLVER_CONTRACT_VIOLATION,
    KNOWLEDGE_RESOLVER_CONTRACT_VIOLATION
}

class CognitiveRelevanceRetrievalException(
    val failure: CognitiveRelevanceRetrievalFailure,
    private val providerExceptionType: String? = null
) : IllegalStateException(failure.name) {
    override fun toString(): String =
        "CognitiveRelevanceRetrievalException(failure=$failure, providerExceptionType=${providerExceptionType ?: "null"})"

    companion object {
        internal fun providerFailure(
            failure: CognitiveRelevanceRetrievalFailure,
            throwable: Exception
        ): CognitiveRelevanceRetrievalException = CognitiveRelevanceRetrievalException(
            failure = failure,
            providerExceptionType = throwable.javaClass.name
        )
    }
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
        if (limits.maxCandidatesPerSource < request.maxResults) {
            throw CognitiveRelevanceRetrievalException(
                CognitiveRelevanceRetrievalFailure.MEMORY_CANDIDATE_BOUND_INSUFFICIENT
            )
        }

        val candidates = try {
            discovery.discover(
                MemoryRelevanceDiscoveryRequest(
                    turn = request.turn,
                    input = request.input,
                    maxCandidates = limits.maxCandidatesPerSource
                )
            ).candidates
        } catch (throwable: Exception) {
            throw CognitiveRelevanceRetrievalException.providerFailure(
                CognitiveRelevanceRetrievalFailure.MEMORY_DISCOVERY_PROVIDER_FAILED,
                throwable
            )
        }

        validateMemoryCandidates(candidates)

        val resolved = ArrayList<MemoryRecordSnapshot>(minOf(request.maxResults, candidates.size))
        for (candidate in candidates) {
            val result = try {
                resolver.resolveExact(candidate)
            } catch (throwable: Exception) {
                throw CognitiveRelevanceRetrievalException.providerFailure(
                    CognitiveRelevanceRetrievalFailure.MEMORY_RESOLVER_PROVIDER_FAILED,
                    throwable
                )
            }
            when (result) {
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
        if (limits.maxCandidatesPerSource < request.maxResults) {
            throw CognitiveRelevanceRetrievalException(
                CognitiveRelevanceRetrievalFailure.KNOWLEDGE_CANDIDATE_BOUND_INSUFFICIENT
            )
        }

        val candidates = try {
            discovery.discover(
                KnowledgeRelevanceDiscoveryRequest(
                    turn = request.turn,
                    input = request.input,
                    maxCandidates = limits.maxCandidatesPerSource
                )
            ).candidates
        } catch (throwable: Exception) {
            throw CognitiveRelevanceRetrievalException.providerFailure(
                CognitiveRelevanceRetrievalFailure.KNOWLEDGE_DISCOVERY_PROVIDER_FAILED,
                throwable
            )
        }

        validateKnowledgeCandidates(candidates)

        val resolved = ArrayList<KnowledgeItemSnapshot>(minOf(request.maxResults, candidates.size))
        for (candidate in candidates) {
            val result = try {
                resolver.resolveExact(candidate)
            } catch (throwable: Exception) {
                throw CognitiveRelevanceRetrievalException.providerFailure(
                    CognitiveRelevanceRetrievalFailure.KNOWLEDGE_RESOLVER_PROVIDER_FAILED,
                    throwable
                )
            }
            when (result) {
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
