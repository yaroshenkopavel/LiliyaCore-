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

data class LearningConsolidationReference(
    val consolidationId: LearningConsolidationId,
    val generation: LearningConsolidationGeneration
)

class LearningConsolidationProposal(
    val id: LearningConsolidationId,
    sources: List<LearningApplicationMutationApplicationReceipt>,
    val proposal: String,
    val createdAt: Instant
) {
    private val sourceSnapshot: List<LearningApplicationMutationApplicationReceipt> = sources
        .toList()
        .sortedWith(
            compareBy<LearningApplicationMutationApplicationReceipt> { it.mutation.mutationId.value }
                .thenBy { it.mutation.generation.value }
        )

    val sources: List<LearningApplicationMutationApplicationReceipt>
        get() = sourceSnapshot.toList()

    init {
        require(sourceSnapshot.isNotEmpty()) { "learning consolidation sources must not be empty" }
        require(proposal.isNotBlank()) { "learning consolidation proposal must not be blank" }
        require(sourceSnapshot.map { it.mutation }.distinct().size == sourceSnapshot.size) {
            "learning consolidation sources must reference unique completed mutations"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is LearningConsolidationProposal &&
            id == other.id &&
            sourceSnapshot == other.sourceSnapshot &&
            proposal == other.proposal &&
            createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sourceSnapshot.hashCode()
        result = 31 * result + proposal.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "LearningConsolidationProposal(id=$id, sources=${sourceSnapshot.size}, proposal=<redacted>, createdAt=$createdAt)"
}

data class LearningConsolidationSnapshot(
    val proposal: LearningConsolidationProposal,
    val generation: LearningConsolidationGeneration
)
