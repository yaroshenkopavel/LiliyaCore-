package pro.liliya.core.learning

import java.time.Instant

@JvmInline
value class LearningApplicationId(val value: String) {
    init { require(value.isNotBlank()) { "learning application id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class LearningApplicationGeneration(val value: Long) {
    init { require(value > 0L) { "learning application generation must be positive" } }
    override fun toString(): String = value.toString()
}

data class LearningDecisionReference(
    val decisionId: LearningDecisionId,
    val generation: LearningDecisionGeneration
)

data class LearningPolicyReference(
    val policyId: LearningPolicyId,
    val generation: LearningPolicyGeneration
)

enum class LearningApplicationTarget {
    MEMORY,
    KNOWLEDGE
}

/**
 * Caller-supplied structural intent to apply an exact learning decision under an exact policy.
 * Presence of this record does not validate, authorize, execute, consolidate, or mutate downstream state.
 */
data class LearningApplicationIntent(
    val id: LearningApplicationId,
    val decision: LearningDecisionReference,
    val policy: LearningPolicyReference,
    val target: LearningApplicationTarget,
    val createdAt: Instant
)

data class LearningApplicationSnapshot(
    val intent: LearningApplicationIntent,
    val generation: LearningApplicationGeneration
)
