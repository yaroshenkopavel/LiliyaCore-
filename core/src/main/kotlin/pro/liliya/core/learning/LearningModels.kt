package pro.liliya.core.learning

import java.time.Instant
import pro.liliya.core.reflection.ReflectionGeneration
import pro.liliya.core.reflection.ReflectionRecordId

@JvmInline
value class LearningCandidateId(val value: String) {
    init { require(value.isNotBlank()) { "learning candidate id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class LearningSourceId(val value: String) {
    init { require(value.isNotBlank()) { "learning source id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class LearningSourceReference(val value: String) {
    init { require(value.isNotBlank()) { "learning source reference must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class LearningGeneration(val value: Long) {
    init { require(value > 0L) { "learning generation must be positive" } }
    override fun toString(): String = value.toString()
}

sealed interface LearningOrigin {
    data class Reflection(
        val recordId: ReflectionRecordId,
        val generation: ReflectionGeneration
    ) : LearningOrigin

    /** Exact consolidation provenance. Construction is controlled by the learning module. */
    data class Consolidation internal constructor(
        val consolidationId: LearningConsolidationId,
        val generation: LearningConsolidationGeneration
    ) : LearningOrigin

    data class Declared(
        val sourceId: LearningSourceId,
        val sourceReference: LearningSourceReference? = null
    ) : LearningOrigin
}

class LearningCandidate(
    val id: LearningCandidateId,
    val origin: LearningOrigin,
    val proposal: String,
    val createdAt: Instant
) {
    init { require(proposal.isNotBlank()) { "learning proposal must not be blank" } }

    override fun equals(other: Any?): Boolean =
        other is LearningCandidate &&
            id == other.id && origin == other.origin && proposal == other.proposal && createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + origin.hashCode()
        result = 31 * result + proposal.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "LearningCandidate(id=$id, origin=$origin, proposal=<redacted>, createdAt=$createdAt)"
}

data class LearningCandidateSnapshot(
    val candidate: LearningCandidate,
    val generation: LearningGeneration
)
