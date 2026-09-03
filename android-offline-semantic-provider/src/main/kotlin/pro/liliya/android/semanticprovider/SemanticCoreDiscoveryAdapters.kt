package pro.liliya.android.semanticprovider

import pro.liliya.core.cognitive.KnowledgeRelevanceCandidate
import pro.liliya.core.cognitive.KnowledgeRelevanceDiscoveryPort
import pro.liliya.core.cognitive.KnowledgeRelevanceDiscoveryRequest
import pro.liliya.core.cognitive.KnowledgeRelevanceDiscoveryResult
import pro.liliya.core.cognitive.MemoryRelevanceCandidate
import pro.liliya.core.cognitive.MemoryRelevanceDiscoveryPort
import pro.liliya.core.cognitive.MemoryRelevanceDiscoveryRequest
import pro.liliya.core.cognitive.MemoryRelevanceDiscoveryResult

internal sealed interface SemanticCandidateDiscoveryResult {
    data class Candidates(
        val candidates: List<SemanticIndexSourceReference>
    ) : SemanticCandidateDiscoveryResult {
        override fun toString(): String = "Candidates(candidates=<redacted:${candidates.size}>)"
    }

    data class ProviderFailure(val className: String) : SemanticCandidateDiscoveryResult {
        init {
            require(className.isNotBlank()) { "semantic provider failure class name must not be blank" }
        }
    }
}

/**
 * Provider-private semantic boundary.
 *
 * Implementations may use embeddings, similarity scores and an advisory index internally, but this
 * boundary exposes only ranked exact source identity + generation. It deliberately carries no
 * authoritative snapshot, score, vector, License, Authority or execution permission.
 */
internal fun interface SemanticCandidateDiscoveryPort {
    fun discover(
        domain: SemanticIndexDomain,
        input: String,
        maxCandidates: Int
    ): SemanticCandidateDiscoveryResult
}

internal class OfflineSemanticMemoryRelevanceDiscoveryAdapter(
    private val provider: SemanticCandidateDiscoveryPort
) : MemoryRelevanceDiscoveryPort {

    override fun discover(request: MemoryRelevanceDiscoveryRequest): MemoryRelevanceDiscoveryResult {
        val discovered = try {
            provider.discover(
                domain = SemanticIndexDomain.MEMORY,
                input = request.input.text,
                maxCandidates = request.maxCandidates
            )
        } catch (failure: Throwable) {
            return MemoryRelevanceDiscoveryResult.ProviderFailure(failure.structuralClassName())
        }

        return when (discovered) {
            is SemanticCandidateDiscoveryResult.ProviderFailure ->
                MemoryRelevanceDiscoveryResult.ProviderFailure(discovered.className)

            is SemanticCandidateDiscoveryResult.Candidates -> {
                if (discovered.candidates.size > request.maxCandidates) {
                    return MemoryRelevanceDiscoveryResult.ProviderFailure("CandidateCountExceeded")
                }

                val mapped = ArrayList<MemoryRelevanceCandidate>(discovered.candidates.size)
                for (candidate in discovered.candidates) {
                    val source = candidate as? SemanticIndexSourceReference.Memory
                        ?: return MemoryRelevanceDiscoveryResult.ProviderFailure("CandidateDomainMismatch")
                    mapped += MemoryRelevanceCandidate(
                        recordId = source.id,
                        generation = source.generation
                    )
                }
                MemoryRelevanceDiscoveryResult.Candidates(mapped)
            }
        }
    }
}

internal class OfflineSemanticKnowledgeRelevanceDiscoveryAdapter(
    private val provider: SemanticCandidateDiscoveryPort
) : KnowledgeRelevanceDiscoveryPort {

    override fun discover(request: KnowledgeRelevanceDiscoveryRequest): KnowledgeRelevanceDiscoveryResult {
        val discovered = try {
            provider.discover(
                domain = SemanticIndexDomain.KNOWLEDGE,
                input = request.input.text,
                maxCandidates = request.maxCandidates
            )
        } catch (failure: Throwable) {
            return KnowledgeRelevanceDiscoveryResult.ProviderFailure(failure.structuralClassName())
        }

        return when (discovered) {
            is SemanticCandidateDiscoveryResult.ProviderFailure ->
                KnowledgeRelevanceDiscoveryResult.ProviderFailure(discovered.className)

            is SemanticCandidateDiscoveryResult.Candidates -> {
                if (discovered.candidates.size > request.maxCandidates) {
                    return KnowledgeRelevanceDiscoveryResult.ProviderFailure("CandidateCountExceeded")
                }

                val mapped = ArrayList<KnowledgeRelevanceCandidate>(discovered.candidates.size)
                for (candidate in discovered.candidates) {
                    val source = candidate as? SemanticIndexSourceReference.Knowledge
                        ?: return KnowledgeRelevanceDiscoveryResult.ProviderFailure("CandidateDomainMismatch")
                    mapped += KnowledgeRelevanceCandidate(
                        itemId = source.id,
                        generation = source.generation
                    )
                }
                KnowledgeRelevanceDiscoveryResult.Candidates(mapped)
            }
        }
    }
}

private fun Throwable.structuralClassName(): String =
    this::class.simpleName?.takeIf { it.isNotBlank() } ?: "Unknown"
