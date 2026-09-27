package pro.liliya.core.semantic

import java.time.Instant
import pro.liliya.core.retrieval.HybridFusedCandidate

data class SemanticClaimTemporalFilterPolicy(
    val maxInputCandidates: Int = 128
) {
    init { require(maxInputCandidates in 1..128) }
}

enum class SemanticClaimTemporalFilterStatus {
    ELIGIBLE,
    REJECTED,
    FALLBACK_REQUIRED,
    FAILED
}

data class SemanticClaimTemporalFilterAudit(
    val worldTime: Instant,
    val knowledgeTime: Instant,
    val inputCount: Int,
    val examinedCount: Int,
    val knowledgeInvisibleCount: Int,
    val worldInvalidCount: Int,
    val eligibleCount: Int,
    val status: SemanticClaimTemporalFilterStatus
) {
    init {
        require(inputCount >= 0)
        require(examinedCount in 0..inputCount)
        require(knowledgeInvisibleCount >= 0)
        require(worldInvalidCount >= 0)
        require(eligibleCount >= 0)
        require(knowledgeInvisibleCount + worldInvalidCount + eligibleCount <= examinedCount)
        if (status == SemanticClaimTemporalFilterStatus.ELIGIBLE) {
            require(knowledgeInvisibleCount + worldInvalidCount + eligibleCount == examinedCount)
        }
    }
}

sealed interface SemanticClaimTemporalFilterResult {
    data class Eligible(
        val candidates: List<HybridFusedCandidate>,
        val audit: SemanticClaimTemporalFilterAudit
    ) : SemanticClaimTemporalFilterResult

    data class Rejected(
        val reason: String,
        val audit: SemanticClaimTemporalFilterAudit
    ) : SemanticClaimTemporalFilterResult {
        init { require(reason.isNotBlank()) }
    }

    data class FallbackRequired(
        val reason: String,
        val audit: SemanticClaimTemporalFilterAudit
    ) : SemanticClaimTemporalFilterResult {
        init { require(reason.isNotBlank()) }
    }

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null,
        val audit: SemanticClaimTemporalFilterAudit
    ) : SemanticClaimTemporalFilterResult {
        init { require(reason.isNotBlank()) }
    }
}

internal interface SemanticClaimTemporalCanonicalReader {
    fun sourceCheckpoint(): SemanticClaimSourceCheckpoint
    fun readExact(reference: SemanticClaimVersionReference): SemanticClaimReadResult
}

private class RepositorySemanticClaimTemporalCanonicalReader(
    private val repository: EncryptedPersistentSemanticClaimRepository
) : SemanticClaimTemporalCanonicalReader {
    override fun sourceCheckpoint(): SemanticClaimSourceCheckpoint =
        repository.sourceCheckpoint()

    override fun readExact(reference: SemanticClaimVersionReference): SemanticClaimReadResult =
        repository.readExact(reference)
}

class SemanticClaimTemporalFilter internal constructor(
    private val canonical: SemanticClaimTemporalCanonicalReader,
    private val policy: SemanticClaimTemporalFilterPolicy
) {
    constructor(
        repository: EncryptedPersistentSemanticClaimRepository,
        policy: SemanticClaimTemporalFilterPolicy = SemanticClaimTemporalFilterPolicy()
    ) : this(RepositorySemanticClaimTemporalCanonicalReader(repository), policy)

    fun filter(
        candidates: List<HybridFusedCandidate>,
        worldTime: Instant,
        knowledgeTime: Instant
    ): SemanticClaimTemporalFilterResult {
        var examined = 0
        var knowledgeInvisible = 0
        var worldInvalid = 0
        val eligible = ArrayList<HybridFusedCandidate>(minOf(candidates.size, policy.maxInputCandidates))

        fun audit(status: SemanticClaimTemporalFilterStatus) =
            SemanticClaimTemporalFilterAudit(
                worldTime = worldTime,
                knowledgeTime = knowledgeTime,
                inputCount = candidates.size,
                examinedCount = examined,
                knowledgeInvisibleCount = knowledgeInvisible,
                worldInvalidCount = worldInvalid,
                eligibleCount = eligible.size,
                status = status
            )

        if (candidates.size > policy.maxInputCandidates) {
            return SemanticClaimTemporalFilterResult.Rejected(
                "semantic temporal filter input candidate budget exceeded",
                audit(SemanticClaimTemporalFilterStatus.REJECTED)
            )
        }
        if (candidates.map { it.id }.distinct().size != candidates.size) {
            return SemanticClaimTemporalFilterResult.Rejected(
                "semantic temporal filter input contains duplicate candidate ids",
                audit(SemanticClaimTemporalFilterStatus.REJECTED)
            )
        }

        val sourceBefore = canonical.sourceCheckpoint()

        for (candidate in candidates) {
            val reference = SemanticClaimRetrievalCandidateIdentity.decode(candidate.id)
                ?: return SemanticClaimTemporalFilterResult.Rejected(
                    "semantic temporal filter candidate id is not a valid Semantic Claim identity",
                    audit(SemanticClaimTemporalFilterStatus.REJECTED)
                )

            examined += 1
            val record = when (val loaded = canonical.readExact(reference)) {
                SemanticClaimReadResult.Missing ->
                    return fallback(
                        "semantic temporal filter canonical claim is missing",
                        audit(SemanticClaimTemporalFilterStatus.FALLBACK_REQUIRED)
                    )
                is SemanticClaimReadResult.Found -> loaded.record
                SemanticClaimReadResult.Corrupt ->
                    return fallback(
                        "semantic temporal filter canonical claim is corrupt",
                        audit(SemanticClaimTemporalFilterStatus.FALLBACK_REQUIRED)
                    )
                is SemanticClaimReadResult.Incompatible ->
                    return fallback(
                        loaded.reason,
                        audit(SemanticClaimTemporalFilterStatus.FALLBACK_REQUIRED)
                    )
                is SemanticClaimReadResult.EncryptionUnavailable ->
                    return fallback(
                        "semantic temporal filter canonical claim encryption unavailable: " +
                            loaded.category,
                        audit(SemanticClaimTemporalFilterStatus.FALLBACK_REQUIRED)
                    )
                is SemanticClaimReadResult.Failed ->
                    return SemanticClaimTemporalFilterResult.Failed(
                        loaded.reason,
                        loaded.throwable,
                        audit(SemanticClaimTemporalFilterStatus.FAILED)
                    )
            }

            if (record.id != reference.claimId || record.version != reference.version) {
                return fallback(
                    "semantic temporal filter candidate does not match canonical claim id/version",
                    audit(SemanticClaimTemporalFilterStatus.FALLBACK_REQUIRED)
                )
            }

            val temporal = record.temporal
            val visible =
                temporal.observedAt <= knowledgeTime &&
                    (temporal.supersededAt == null || temporal.supersededAt > knowledgeTime)
            if (!visible) {
                knowledgeInvisible += 1
                continue
            }

            val valid =
                (temporal.validFrom == null || temporal.validFrom <= worldTime) &&
                    (temporal.validUntil == null || worldTime < temporal.validUntil)
            if (!valid) {
                worldInvalid += 1
                continue
            }

            eligible += candidate
        }

        val sourceAfter = canonical.sourceCheckpoint()
        if (sourceAfter != sourceBefore) {
            return fallback(
                "canonical semantic claim source changed during temporal filtering",
                audit(SemanticClaimTemporalFilterStatus.FALLBACK_REQUIRED)
            )
        }

        return SemanticClaimTemporalFilterResult.Eligible(
            candidates = eligible,
            audit = audit(SemanticClaimTemporalFilterStatus.ELIGIBLE)
        )
    }

    private fun fallback(
        reason: String,
        audit: SemanticClaimTemporalFilterAudit
    ) = SemanticClaimTemporalFilterResult.FallbackRequired(reason, audit)
}