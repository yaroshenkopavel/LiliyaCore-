package pro.liliya.core.reasoning

import java.time.Instant

@JvmInline
value class ReasoningArtifactId(val value: String) {
    init { require(value.isNotBlank()) { "reasoning artifact id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class ReasoningPremiseId(val value: String) {
    init { require(value.isNotBlank()) { "reasoning premise id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class ReasoningSourceId(val value: String) {
    init { require(value.isNotBlank()) { "reasoning source id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class ReasoningSourceReference(val value: String) {
    init { require(value.isNotBlank()) { "reasoning source reference must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class ReasoningGeneration(val value: Long) {
    init { require(value > 0L) { "reasoning generation must be positive" } }
    override fun toString(): String = value.toString()
}

data class ReasoningOrigin(
    val sourceId: ReasoningSourceId,
    val sourceReference: ReasoningSourceReference? = null
)

class ReasoningPremise(
    val id: ReasoningPremiseId,
    val statement: String
) {
    init { require(statement.isNotBlank()) { "reasoning premise statement must not be blank" } }

    override fun equals(other: Any?): Boolean =
        other is ReasoningPremise && id == other.id && statement == other.statement

    override fun hashCode(): Int = 31 * id.hashCode() + statement.hashCode()

    override fun toString(): String = "ReasoningPremise(id=$id, statement=<redacted>)"
}

class ReasoningArtifact(
    val id: ReasoningArtifactId,
    val origin: ReasoningOrigin,
    premises: List<ReasoningPremise>,
    val analysis: String,
    val conclusion: String,
    val createdAt: Instant
) {
    val premises: List<ReasoningPremise> = premises.toList()

    init {
        require(this.premises.isNotEmpty()) { "reasoning artifact must contain at least one premise" }
        require(this.premises.map { it.id }.toSet().size == this.premises.size) {
            "reasoning premise ids must be unique"
        }
        require(analysis.isNotBlank()) { "reasoning analysis must not be blank" }
        require(conclusion.isNotBlank()) { "reasoning conclusion must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is ReasoningArtifact &&
            id == other.id && origin == other.origin && premises == other.premises &&
            analysis == other.analysis && conclusion == other.conclusion && createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + origin.hashCode()
        result = 31 * result + premises.hashCode()
        result = 31 * result + analysis.hashCode()
        result = 31 * result + conclusion.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "ReasoningArtifact(id=$id, origin=$origin, premises=<redacted:${premises.size}>, analysis=<redacted>, conclusion=<redacted>, createdAt=$createdAt)"
}

data class ReasoningArtifactSnapshot(
    val artifact: ReasoningArtifact,
    val generation: ReasoningGeneration
)
