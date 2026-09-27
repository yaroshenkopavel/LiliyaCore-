package pro.liliya.android.semanticprovider

import pro.liliya.core.semantic.SemanticClaimVectorDiscoveryPort
import pro.liliya.core.semantic.SemanticClaimVectorProviderCandidate
import pro.liliya.core.semantic.SemanticClaimVectorProviderDiagnostics
import pro.liliya.core.semantic.SemanticClaimVectorProviderIdentity
import pro.liliya.core.semantic.SemanticClaimVectorProviderResult
import pro.liliya.core.semantic.SemanticClaimSourceCheckpoint
import pro.liliya.core.semantic.SemanticClaimVersionReference

internal data class OfflineSemanticClaimVectorIdentity(
    val profileId: String,
    val profileGeneration: Long,
    val indexGeneration: Long,
    val source: SemanticClaimSourceCheckpoint
) {
    init {
        require(profileId.isNotBlank())
        require(profileGeneration > 0L)
        require(indexGeneration > 0L)
    }
}

internal data class OfflineSemanticClaimVectorCandidate(
    val reference: SemanticClaimVersionReference,
    val similarity: Double
) {
    init { require(similarity.isFinite()) }
}

internal sealed interface OfflineSemanticClaimVectorDiscoveryResult {
    data class Ranked(
        val identity: OfflineSemanticClaimVectorIdentity,
        val candidates: List<OfflineSemanticClaimVectorCandidate>,
        val truncated: Boolean = false,
        val routingNodeReads: Int = 0,
        val shardReads: Int = 0
    ) : OfflineSemanticClaimVectorDiscoveryResult {
        init {
            require(routingNodeReads >= 0)
            require(shardReads >= 0)
        }
    }

    data class Unavailable(
        val kind: SemanticProviderFailureKind,
        val reason: String
    ) : OfflineSemanticClaimVectorDiscoveryResult {
        init { require(reason.isNotBlank()) }
    }

    data class Failed(
        val reason: String,
        val exceptionClass: String? = null
    ) : OfflineSemanticClaimVectorDiscoveryResult {
        init {
            require(reason.isNotBlank())
            require(exceptionClass == null || exceptionClass.isNotBlank())
        }
    }
}

internal fun interface OfflineSemanticClaimVectorDiscoveryPort {
    fun discover(
        text: String,
        maxCandidates: Int
    ): OfflineSemanticClaimVectorDiscoveryResult
}

internal class OfflineSemanticClaimVectorDiscoveryAdapter(
    private val provider: OfflineSemanticClaimVectorDiscoveryPort
) : SemanticClaimVectorDiscoveryPort {
    override fun discover(
        text: String,
        maxCandidates: Int
    ): SemanticClaimVectorProviderResult {
        val result = try {
            provider.discover(text, maxCandidates)
        } catch (failure: Exception) {
            return SemanticClaimVectorProviderResult.Failed(
                reason = "offline semantic claim vector provider failed",
                throwable = failure
            )
        }

        return when (result) {
            is OfflineSemanticClaimVectorDiscoveryResult.Unavailable ->
                SemanticClaimVectorProviderResult.Unavailable(
                    result.kind.name + ":" + result.reason
                )
            is OfflineSemanticClaimVectorDiscoveryResult.Failed ->
                SemanticClaimVectorProviderResult.Failed(
                    reason = if (result.exceptionClass == null) {
                        result.reason
                    } else {
                        result.reason + ":" + result.exceptionClass
                    }
                )
            is OfflineSemanticClaimVectorDiscoveryResult.Ranked ->
                mapRanked(result, maxCandidates)
        }
    }

    private fun mapRanked(
        ranked: OfflineSemanticClaimVectorDiscoveryResult.Ranked,
        maxCandidates: Int
    ): SemanticClaimVectorProviderResult {
        if (ranked.candidates.size > maxCandidates) {
            return SemanticClaimVectorProviderResult.Failed(
                "offline semantic claim vector candidate budget exceeded"
            )
        }
        if (ranked.candidates.map { it.reference }.distinct().size != ranked.candidates.size) {
            return SemanticClaimVectorProviderResult.Failed(
                "offline semantic claim vector candidates contain duplicate references"
            )
        }

        return SemanticClaimVectorProviderResult.Ranked(
            identity = SemanticClaimVectorProviderIdentity(
                profileId = ranked.identity.profileId,
                profileGeneration = ranked.identity.profileGeneration,
                indexGeneration = ranked.identity.indexGeneration,
                source = ranked.identity.source
            ),
            candidates = ranked.candidates.map {
                SemanticClaimVectorProviderCandidate(
                    reference = it.reference,
                    similarity = it.similarity
                )
            },
            truncated = ranked.truncated,
            diagnostics = SemanticClaimVectorProviderDiagnostics(
                routingNodeReads = ranked.routingNodeReads,
                shardReads = ranked.shardReads
            )
        )
    }
}