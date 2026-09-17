package pro.liliya.core.memory

/** Exact observed Memory identity used by advisory near-semantic evidence. */
data class MemoryRetentionSemanticReference(
    val recordId: MemoryRecordId,
    val generation: MemoryGeneration
)

/**
 * Advisory pairwise similarity evidence only. It is not Authority, mutation ownership,
 * a Memory snapshot, or execution permission. Raw content and embeddings are deliberately absent.
 */
data class MemoryRetentionNearSemanticEvidence(
    val left: MemoryRetentionSemanticReference,
    val right: MemoryRetentionSemanticReference,
    val similarity: Double
)

data class MemoryRetentionNearSemanticPolicy(
    val minimumSimilarity: Double,
    val maxEvidence: Int
) {
    init {
        require(minimumSimilarity.isFinite() && minimumSimilarity in 0.0..1.0) {
            "near-semantic minimum similarity must be finite and within [0, 1]"
        }
        require(maxEvidence > 0) { "near-semantic evidence bound must be positive" }
    }
}

enum class MemoryRetentionNearSemanticDisposition {
    ADMITTED,
    NO_MATCH
}

data class MemoryRetentionNearSemanticDecision(
    val candidate: MemoryRetentionSemanticReference,
    val retentionClass: MemoryRetentionClass,
    val disposition: MemoryRetentionNearSemanticDisposition,
    val anchor: MemoryRetentionSemanticReference? = null,
    val similarity: Double? = null
) {
    init {
        require((disposition == MemoryRetentionNearSemanticDisposition.ADMITTED) == (anchor != null)) {
            "admitted near-semantic decision requires exactly one anchor"
        }
        require((anchor != null) == (similarity != null)) {
            "near-semantic anchor and similarity must be present together"
        }
    }
}

enum class MemoryRetentionNearSemanticFailure {
    EVIDENCE_LIMIT_EXCEEDED,
    NON_FINITE_SIMILARITY,
    SIMILARITY_OUT_OF_RANGE,
    SELF_PAIR,
    UNKNOWN_REFERENCE,
    STALE_GENERATION,
    CROSS_CLASS_PAIR,
    INELIGIBLE_PAIR,
    DUPLICATE_PAIR
}

sealed interface MemoryRetentionNearSemanticAdmissionResult {
    data class Evaluated(
        val decisions: List<MemoryRetentionNearSemanticDecision>
    ) : MemoryRetentionNearSemanticAdmissionResult

    data class Rejected(
        val failure: MemoryRetentionNearSemanticFailure
    ) : MemoryRetentionNearSemanticAdmissionResult
}

/**
 * Read-only, bounded experiment layered on top of the accepted retention ledger.
 *
 * Only baseline budget-rejected entries may be candidates and only baseline RETAINED entries
 * may be anchors. Canonical DUPLICATE_SUPPRESSED decisions are never reinterpreted. Admitted
 * candidates never become anchors, preventing transitive chaining. This class has no Authority,
 * Memory, model, persistence, or mutation dependency.
 */
class MemoryRetentionNearSemanticAdmission(
    private val policy: MemoryRetentionNearSemanticPolicy
) {
    fun evaluate(
        ledger: MemoryRetentionLedger,
        evidence: List<MemoryRetentionNearSemanticEvidence>
    ): MemoryRetentionNearSemanticAdmissionResult {
        if (evidence.size > policy.maxEvidence) {
            return rejected(MemoryRetentionNearSemanticFailure.EVIDENCE_LIMIT_EXCEEDED)
        }

        val entries = ledger.entries
        val byId = entries.associateBy { it.recordId }
        if (byId.size != entries.size) {
            return rejected(MemoryRetentionNearSemanticFailure.UNKNOWN_REFERENCE)
        }

        val candidates = entries.filter { it.isNearSemanticCandidate() }
        val candidateIds = candidates.mapTo(HashSet()) { it.recordId }
        val anchorIds = entries
            .filter { it.disposition == MemoryRetentionDisposition.RETAINED }
            .mapTo(HashSet()) { it.recordId }

        val indexed = HashMap<PairKey, MemoryRetentionNearSemanticEvidence>(evidence.size)
        for (item in evidence) {
            if (!item.similarity.isFinite()) {
                return rejected(MemoryRetentionNearSemanticFailure.NON_FINITE_SIMILARITY)
            }
            if (item.similarity !in 0.0..1.0) {
                return rejected(MemoryRetentionNearSemanticFailure.SIMILARITY_OUT_OF_RANGE)
            }
            if (item.left.recordId == item.right.recordId) {
                return rejected(MemoryRetentionNearSemanticFailure.SELF_PAIR)
            }

            val left = byId[item.left.recordId]
                ?: return rejected(MemoryRetentionNearSemanticFailure.UNKNOWN_REFERENCE)
            val right = byId[item.right.recordId]
                ?: return rejected(MemoryRetentionNearSemanticFailure.UNKNOWN_REFERENCE)
            if (left.generation != item.left.generation || right.generation != item.right.generation) {
                return rejected(MemoryRetentionNearSemanticFailure.STALE_GENERATION)
            }
            if (left.retentionClass != right.retentionClass) {
                return rejected(MemoryRetentionNearSemanticFailure.CROSS_CLASS_PAIR)
            }

            val leftCandidate = item.left.recordId in candidateIds
            val rightCandidate = item.right.recordId in candidateIds
            val leftAnchor = item.left.recordId in anchorIds
            val rightAnchor = item.right.recordId in anchorIds
            if (!((leftCandidate && rightAnchor) || (rightCandidate && leftAnchor))) {
                return rejected(MemoryRetentionNearSemanticFailure.INELIGIBLE_PAIR)
            }

            val key = PairKey.of(item.left.recordId, item.right.recordId)
            if (indexed.put(key, item) != null) {
                return rejected(MemoryRetentionNearSemanticFailure.DUPLICATE_PAIR)
            }
        }

        val decisions = candidates
            .sortedWith(compareBy<MemoryRetentionLedgerEntry>({ it.retentionClass.ordinal }, { it.recordId.value }))
            .map { candidate -> evaluateCandidate(candidate, entries, indexed) }
        return MemoryRetentionNearSemanticAdmissionResult.Evaluated(decisions)
    }

    private fun evaluateCandidate(
        candidate: MemoryRetentionLedgerEntry,
        entries: List<MemoryRetentionLedgerEntry>,
        evidence: Map<PairKey, MemoryRetentionNearSemanticEvidence>
    ): MemoryRetentionNearSemanticDecision {
        val match = entries.asSequence()
            .filter {
                it.retentionClass == candidate.retentionClass &&
                    it.disposition == MemoryRetentionDisposition.RETAINED
            }
            .mapNotNull { anchor ->
                val item = evidence[PairKey.of(candidate.recordId, anchor.recordId)] ?: return@mapNotNull null
                if (item.similarity < policy.minimumSimilarity) return@mapNotNull null
                AnchorMatch(anchor.reference(), item.similarity)
            }
            .sortedWith(
                compareByDescending<AnchorMatch> { it.similarity }
                    .thenBy { it.anchor.recordId.value }
            )
            .firstOrNull()

        return if (match == null) {
            MemoryRetentionNearSemanticDecision(
                candidate = candidate.reference(),
                retentionClass = candidate.retentionClass,
                disposition = MemoryRetentionNearSemanticDisposition.NO_MATCH
            )
        } else {
            MemoryRetentionNearSemanticDecision(
                candidate = candidate.reference(),
                retentionClass = candidate.retentionClass,
                disposition = MemoryRetentionNearSemanticDisposition.ADMITTED,
                anchor = match.anchor,
                similarity = match.similarity
            )
        }
    }

    private data class AnchorMatch(
        val anchor: MemoryRetentionSemanticReference,
        val similarity: Double
    )

    private data class PairKey(val first: String, val second: String) {
        companion object {
            fun of(left: MemoryRecordId, right: MemoryRecordId): PairKey =
                if (left.value <= right.value) PairKey(left.value, right.value)
                else PairKey(right.value, left.value)
        }
    }

    private fun MemoryRetentionLedgerEntry.isNearSemanticCandidate(): Boolean =
        disposition == MemoryRetentionDisposition.RECORD_BUDGET_REJECTED ||
            disposition == MemoryRetentionDisposition.CONTENT_BUDGET_REJECTED

    private fun MemoryRetentionLedgerEntry.reference(): MemoryRetentionSemanticReference =
        MemoryRetentionSemanticReference(recordId, generation)

    private fun rejected(
        failure: MemoryRetentionNearSemanticFailure
    ): MemoryRetentionNearSemanticAdmissionResult.Rejected =
        MemoryRetentionNearSemanticAdmissionResult.Rejected(failure)
}
