package pro.liliya.android.semanticprovider

import pro.liliya.core.cognitive.KnowledgeRelevanceCandidate
import pro.liliya.core.cognitive.KnowledgeRelevanceDiscoveryPort
import pro.liliya.core.cognitive.KnowledgeRelevanceDiscoveryRequest
import pro.liliya.core.cognitive.KnowledgeRelevanceDiscoveryResult
import pro.liliya.core.cognitive.MemoryRelevanceCandidate
import pro.liliya.core.cognitive.MemoryRelevanceDiscoveryPort
import pro.liliya.core.cognitive.MemoryRelevanceDiscoveryRequest
import pro.liliya.core.cognitive.MemoryRelevanceDiscoveryResult

internal sealed interface SemanticCandidateDiscoveryResult

internal data class SemanticCandidates(
    val candidates: List<SemanticIndexSourceReference>
) : SemanticCandidateDiscoveryResult {
    override fun toString(): String = "SemanticCandidates(candidates=<redacted:${candidates.size}>)"
}

internal enum class SemanticProviderFailureKind {
    BUSY,
    INDEX_UNAVAILABLE,
    RESOURCE_REJECTED,
    REQUEST_REJECTED,
    OPERATION_FAILED,
    SESSION_FAILED,
    CLOSED,
    PROVIDER_FAILED
}

internal data class SemanticProviderFailure(
    val kind: SemanticProviderFailureKind,
    val exceptionClass: String? = null
) : SemanticCandidateDiscoveryResult {
    init {
        require(exceptionClass == null || exceptionClass.isNotBlank()) {
            "semantic provider exception class must not be blank"
        }
    }

    override fun toString(): String =
        "SemanticProviderFailure(kind=$kind, exceptionClass=${exceptionClass ?: "null"})"
}

internal enum class SemanticDiscoveryContractFailure {
    PROVIDER_FAILED,
    CANDIDATE_COUNT_EXCEEDED,
    CANDIDATE_DOMAIN_MISMATCH
}

internal class SemanticDiscoveryContractException(
    val failure: SemanticDiscoveryContractFailure,
    val providerFailureKind: SemanticProviderFailureKind? = null,
    val providerExceptionType: String? = null
) : IllegalStateException(failure.name) {
    override fun toString(): String =
        "SemanticDiscoveryContractException(failure=$failure, providerFailureKind=${providerFailureKind ?: "null"}, providerExceptionType=${providerExceptionType ?: "null"})"
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
        val discovered = discoverSafely(
            domain = SemanticIndexDomain.MEMORY,
            input = request.input.text,
            maxCandidates = request.maxCandidates
        )
        val candidates = requireCandidates(discovered, request.maxCandidates)
        val mapped = ArrayList<MemoryRelevanceCandidate>(candidates.size)
        for (candidate in candidates) {
            val source = candidate as? SemanticIndexSourceReference.Memory
                ?: throw SemanticDiscoveryContractException(
                    SemanticDiscoveryContractFailure.CANDIDATE_DOMAIN_MISMATCH
                )
            mapped += MemoryRelevanceCandidate(
                recordId = source.id,
                generation = source.generation
            )
        }
        return MemoryRelevanceDiscoveryResult(mapped)
    }

    private fun discoverSafely(
        domain: SemanticIndexDomain,
        input: String,
        maxCandidates: Int
    ): SemanticCandidateDiscoveryResult = try {
        provider.discover(domain, input, maxCandidates)
    } catch (failure: Exception) {
        throw SemanticDiscoveryContractException(
            failure = SemanticDiscoveryContractFailure.PROVIDER_FAILED,
            providerFailureKind = SemanticProviderFailureKind.PROVIDER_FAILED,
            providerExceptionType = failure.javaClass.name
        )
    }
}

internal class OfflineSemanticKnowledgeRelevanceDiscoveryAdapter(
    private val provider: SemanticCandidateDiscoveryPort
) : KnowledgeRelevanceDiscoveryPort {

    override fun discover(request: KnowledgeRelevanceDiscoveryRequest): KnowledgeRelevanceDiscoveryResult {
        val discovered = discoverSafely(
            domain = SemanticIndexDomain.KNOWLEDGE,
            input = request.input.text,
            maxCandidates = request.maxCandidates
        )
        val candidates = requireCandidates(discovered, request.maxCandidates)
        val mapped = ArrayList<KnowledgeRelevanceCandidate>(candidates.size)
        for (candidate in candidates) {
            val source = candidate as? SemanticIndexSourceReference.Knowledge
                ?: throw SemanticDiscoveryContractException(
                    SemanticDiscoveryContractFailure.CANDIDATE_DOMAIN_MISMATCH
                )
            mapped += KnowledgeRelevanceCandidate(
                itemId = source.id,
                generation = source.generation
            )
        }
        return KnowledgeRelevanceDiscoveryResult(mapped)
    }

    private fun discoverSafely(
        domain: SemanticIndexDomain,
        input: String,
        maxCandidates: Int
    ): SemanticCandidateDiscoveryResult = try {
        provider.discover(domain, input, maxCandidates)
    } catch (failure: Exception) {
        throw SemanticDiscoveryContractException(
            failure = SemanticDiscoveryContractFailure.PROVIDER_FAILED,
            providerFailureKind = SemanticProviderFailureKind.PROVIDER_FAILED,
            providerExceptionType = failure.javaClass.name
        )
    }
}

private fun requireCandidates(
    discovered: SemanticCandidateDiscoveryResult,
    maxCandidates: Int
): List<SemanticIndexSourceReference> = when (discovered) {
    is SemanticProviderFailure ->
        throw SemanticDiscoveryContractException(
            failure = SemanticDiscoveryContractFailure.PROVIDER_FAILED,
            providerFailureKind = discovered.kind,
            providerExceptionType = discovered.exceptionClass
        )

    is SemanticCandidates -> {
        if (discovered.candidates.size > maxCandidates) {
            throw SemanticDiscoveryContractException(
                SemanticDiscoveryContractFailure.CANDIDATE_COUNT_EXCEEDED
            )
        }
        discovered.candidates
    }
}
