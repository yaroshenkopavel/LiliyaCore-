package pro.liliya.core.learning

import java.time.Instant

@JvmInline
value class LearningDecisionId(val value: String) {
    init { require(value.isNotBlank()) { "learning decision id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class LearningDecisionGeneration(val value: Long) {
    init { require(value > 0L) { "learning decision generation must be positive" } }
    override fun toString(): String = value.toString()
}

data class LearningCandidateReference(
    val candidateId: LearningCandidateId,
    val generation: LearningGeneration
)

enum class LearningDecisionDisposition {
    APPROVE,
    REJECT
}

class LearningDecision(
    val id: LearningDecisionId,
    val candidate: LearningCandidateReference,
    val disposition: LearningDecisionDisposition,
    val rationale: String,
    val createdAt: Instant
) {
    init { require(rationale.isNotBlank()) { "learning decision rationale must not be blank" } }

    override fun equals(other: Any?): Boolean =
        other is LearningDecision &&
            id == other.id &&
            candidate == other.candidate &&
            disposition == other.disposition &&
            rationale == other.rationale &&
            createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + candidate.hashCode()
        result = 31 * result + disposition.hashCode()
        result = 31 * result + rationale.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "LearningDecision(id=$id, candidate=$candidate, disposition=$disposition, rationale=<redacted>, createdAt=$createdAt)"
}

data class LearningDecisionSnapshot(
    val decision: LearningDecision,
    val generation: LearningDecisionGeneration
)
