package pro.liliya.core.semantic

import pro.liliya.core.retrieval.RetrievalCandidateId

internal interface SemanticClaimVectorCanonicalReader {
    fun sourceCheckpoint(): SemanticClaimSourceCheckpoint
    fun readExact(reference: SemanticClaimVersionReference): SemanticClaimReadResult
}

private class RepositorySemanticClaimVectorCanonicalReader(
    private val repository: EncryptedPersistentSemanticClaimRepository
) : SemanticClaimVectorCanonicalReader {
    override fun sourceCheckpoint(): SemanticClaimSourceCheckpoint =
        repository.sourceCheckpoint()

    override fun readExact(reference: SemanticClaimVersionReference): SemanticClaimReadResult =
        repository.readExact(reference)
}

class SemanticClaimVectorQuerySource private constructor(
    private val canonical: SemanticClaimVectorCanonicalReader,
    private val provider: SemanticClaimVectorDiscoveryPort,
    private val policy: SemanticClaimVectorPolicy
) {
    constructor(
        repository: EncryptedPersistentSemanticClaimRepository,
        provider: SemanticClaimVectorDiscoveryPort,
        policy: SemanticClaimVectorPolicy = SemanticClaimVectorPolicy()
    ) : this(
        RepositorySemanticClaimVectorCanonicalReader(repository),
        provider,
        policy
    )

    internal constructor(
        canonical: SemanticClaimVectorCanonicalReader,
        provider: SemanticClaimVectorDiscoveryPort
    ) : this(canonical, provider, SemanticClaimVectorPolicy())

    fun query(text: String): SemanticClaimVectorQueryResult {
        if (text.isBlank()) {
            return SemanticClaimVectorQueryResult.Rejected(
                "semantic vector query must not be blank"
            )
        }

        val sourceBefore = canonical.sourceCheckpoint()
        val discovered = try {
            provider.discover(text, policy.maxCandidates)
        } catch (throwable: Exception) {
            return SemanticClaimVectorQueryResult.Failed(
                "semantic vector provider threw",
                throwable
            )
        }

        val ranked = when (discovered) {
            is SemanticClaimVectorProviderResult.Unavailable ->
                return SemanticClaimVectorQueryResult.FallbackRequired(discovered.reason)
            is SemanticClaimVectorProviderResult.Failed ->
                return SemanticClaimVectorQueryResult.Failed(
                    discovered.reason,
                    discovered.throwable
                )
            is SemanticClaimVectorProviderResult.Ranked -> discovered
        }

        if (ranked.identity.source != sourceBefore) {
            return fallback("semantic vector provider source checkpoint is stale")
        }
        if (ranked.candidates.size > policy.maxCandidates) {
            return fallback("semantic vector provider candidate budget exceeded")
        }
        if (ranked.candidates.map { it.reference }.distinct().size != ranked.candidates.size) {
            return fallback("semantic vector provider returned duplicate claim references")
        }

        val validated = ArrayList<SemanticClaimVectorCandidate>(ranked.candidates.size)
        for (candidate in ranked.candidates) {
            val canonicalRecord = when (val loaded = canonical.readExact(candidate.reference)) {
                SemanticClaimReadResult.Missing ->
                    return fallback("semantic vector candidate canonical claim is missing")
                is SemanticClaimReadResult.Found -> loaded.record
                SemanticClaimReadResult.Corrupt ->
                    return fallback("semantic vector candidate canonical claim is corrupt")
                is SemanticClaimReadResult.Incompatible ->
                    return fallback(loaded.reason)
                is SemanticClaimReadResult.EncryptionUnavailable ->
                    return fallback(
                        "semantic vector canonical claim encryption unavailable: " +
                            loaded.category
                    )
                is SemanticClaimReadResult.Failed ->
                    return SemanticClaimVectorQueryResult.Failed(
                        loaded.reason,
                        loaded.throwable
                    )
            }

            if (
                canonicalRecord.id != candidate.reference.claimId ||
                canonicalRecord.version != candidate.reference.version
            ) {
                return fallback(
                    "semantic vector candidate does not match canonical claim id/version"
                )
            }

            validated += SemanticClaimVectorCandidate(
                reference = candidate.reference,
                candidateId = candidateId(candidate.reference),
                similarity = candidate.similarity
            )
        }

        val sourceAfter = canonical.sourceCheckpoint()
        if (sourceAfter != sourceBefore) {
            return fallback("canonical semantic claim source changed during vector query")
        }

        return SemanticClaimVectorQueryResult.Ranked(
            candidates = validated,
            audit = SemanticClaimVectorAudit(
                providerIdentity = ranked.identity,
                source = sourceBefore,
                requestedCandidates = policy.maxCandidates,
                providerCandidates = ranked.candidates.size,
                providerTruncated = ranked.truncated,
                routingNodeReads = ranked.diagnostics.routingNodeReads,
                shardReads = ranked.diagnostics.shardReads,
                staleCandidates = 0,
                returnedCandidates = validated.size
            )
        )
    }

    private fun candidateId(reference: SemanticClaimVersionReference): RetrievalCandidateId =
        SemanticClaimRetrievalCandidateIdentity.encode(reference)

    private fun fallback(reason: String): SemanticClaimVectorQueryResult =
        SemanticClaimVectorQueryResult.FallbackRequired(reason)
}