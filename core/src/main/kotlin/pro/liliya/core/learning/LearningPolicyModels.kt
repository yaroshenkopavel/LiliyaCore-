package pro.liliya.core.learning

import java.time.Instant

@JvmInline
value class LearningPolicyId(val value: String) {
    init { require(value.isNotBlank()) { "learning policy id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class LearningPolicyGeneration(val value: Long) {
    init { require(value > 0L) { "learning policy generation must be positive" } }
    override fun toString(): String = value.toString()
}

class LearningPolicy(
    val id: LearningPolicyId,
    val rule: String,
    val createdAt: Instant
) {
    init { require(rule.isNotBlank()) { "learning policy rule must not be blank" } }

    override fun equals(other: Any?): Boolean =
        other is LearningPolicy &&
            id == other.id &&
            rule == other.rule &&
            createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + rule.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "LearningPolicy(id=$id, rule=<redacted>, createdAt=$createdAt)"
}

data class LearningPolicySnapshot(
    val policy: LearningPolicy,
    val generation: LearningPolicyGeneration
)
