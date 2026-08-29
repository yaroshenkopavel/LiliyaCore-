package pro.liliya.core.learning

import java.time.Instant

@JvmInline
value class LearningConsolidationId(val value: String) {
    init { require(value.isNotBlank()) { "learning consolidation id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class LearningConsolidationGeneration(val value: Long) {
    init { require(value > 0L) { "learning consolidation generation must be positive" } }
    override fun toString(): String = value.toString()
}

class LearningConsolidationProposal(
    val id: LearningConsolidationId,
    sources: List<LearningApplicationMutationApplicationReceipt>,
    val proposal: String,
    val createdAt: Instant
) {
    val sources: List<LearningApplicationMutationApplicationReceipt> = sources.toList()

    init {
        require(this.sources.isNotEmpty()) { "learning consolidation sources must not be empty" }
        require(proposal.isNotBlank()) { "learning consolidation proposal must not be blank" }
        require(this.sources.map { it.mutation }.distinct().size == this.sources.size) {
            "learning consolidation sources must reference unique completed mutations"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is LearningConsolidationProposal &&
            id == other.id &&
            sources == other.sources &&
            proposal == other.proposal &&
            createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sources.hashCode()
        result = 31 * result + proposal.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "LearningConsolidationProposal(id=$id, sources=${sources.size}, proposal=<redacted>, createdAt=$createdAt)"
}

data class LearningConsolidationSnapshot(
    val proposal: LearningConsolidationProposal,
    val generation: LearningConsolidationGeneration
)
