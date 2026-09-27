package pro.liliya.core.semantic

import pro.liliya.core.retrieval.RankedRetrievalCandidate
import pro.liliya.core.retrieval.RetrievalCandidateId
import pro.liliya.core.retrieval.RetrievalChannelId
import pro.liliya.core.retrieval.RetrievalChannelRequirement
import pro.liliya.core.retrieval.RetrievalChannelResult

data class SemanticClaimVectorPolicy(
    val maxCandidates: Int = 128
) {
    init { require(maxCandidates in 1..128) }
}

data class SemanticClaimVectorProviderIdentity(
    val profileId: String,
    val profileGeneration: Long,
    val indexGeneration: Long
) {
    init {
        require(profileId.isNotBlank() && profileId.length <= 128)
        require(profileGeneration > 0L)
        require(indexGeneration > 0L)
    }
}

data class SemanticClaimVectorProviderCandidate(
    val reference: SemanticClaimVersionReference,
    val similarity: Double
) {
    init { require(similarity.isFinite()) }
}

sealed interface SemanticClaimVectorProviderResult {
    data class Ranked(
        val identity: SemanticClaimVectorProviderIdentity,
        val candidates: List<SemanticClaimVectorProviderCandidate>,
        val truncated: Boolean = false
    ) : SemanticClaimVectorProviderResult

    data class Unavailable(val reason: String) : SemanticClaimVectorProviderResult {
        init { require(reason.isNotBlank()) }
    }

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : SemanticClaimVectorProviderResult {
        init { require(reason.isNotBlank()) }
    }
}

fun interface SemanticClaimVectorDiscoveryPort {
    fun discover(text: String, maxCandidates: Int): SemanticClaimVectorProviderResult
}

data class SemanticClaimVectorCandidate(
    val reference: SemanticClaimVersionReference,
    val candidateId: RetrievalCandidateId,
    val similarity: Double
) {
    init { require(similarity.isFinite()) }
}

data class SemanticClaimVectorAudit(
    val providerIdentity: SemanticClaimVectorProviderIdentity,
    val source: SemanticClaimSourceCheckpoint,
    val requestedCandidates: Int,
    val providerCandidates: Int,
    val providerTruncated: Boolean,
    val staleCandidates: Int,
    val returnedCandidates: Int,
    val advisoryOnly: Boolean = true
) {
    init {
        require(requestedCandidates in 1..128)
        require(providerCandidates in 0..requestedCandidates)
        require(staleCandidates >= 0)
        require(returnedCandidates in 0..providerCandidates)
        require(advisoryOnly)
    }
}

sealed interface SemanticClaimVectorQueryResult {
    data class Ranked(
        val candidates: List<SemanticClaimVectorCandidate>,
        val audit: SemanticClaimVectorAudit
    ) : SemanticClaimVectorQueryResult

    data class FallbackRequired(val reason: String) : SemanticClaimVectorQueryResult {
        init { require(reason.isNotBlank()) }
    }

    data class Rejected(val reason: String) : SemanticClaimVectorQueryResult {
        init { require(reason.isNotBlank()) }
    }

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : SemanticClaimVectorQueryResult {
        init { require(reason.isNotBlank()) }
    }
}

fun SemanticClaimVectorQueryResult.toRetrievalChannelResult(
    requirement: RetrievalChannelRequirement,
    channelId: RetrievalChannelId = RetrievalChannelId("semantic-vector-v1")
): RetrievalChannelResult =
    when (this) {
        is SemanticClaimVectorQueryResult.Ranked ->
            RetrievalChannelResult.Ranked(
                channelId,
                requirement,
                candidates.map { RankedRetrievalCandidate(it.candidateId) }
            )
        is SemanticClaimVectorQueryResult.FallbackRequired ->
            RetrievalChannelResult.Unavailable(channelId, requirement, reason)
        is SemanticClaimVectorQueryResult.Rejected ->
            RetrievalChannelResult.Failed(channelId, requirement, reason)
        is SemanticClaimVectorQueryResult.Failed ->
            RetrievalChannelResult.Failed(channelId, requirement, reason)
    }