package pro.liliya.core.learning

import java.time.Instant
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.memory.MemoryRecordId

@JvmInline
value class LearningApplicationMutationPlanId(val value: String) {
    init { require(value.isNotBlank()) { "learning application mutation plan id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class LearningApplicationMutationPlanGeneration(val value: Long) {
    init { require(value > 0L) { "learning application mutation plan generation must be positive" } }
    override fun toString(): String = value.toString()
}

sealed interface LearningApplicationMutationDestination {
    data class Memory(val recordId: MemoryRecordId) : LearningApplicationMutationDestination
    data class Knowledge(val itemId: KnowledgeItemId) : LearningApplicationMutationDestination
}

/**
 * Caller-supplied structural idempotency plan for a future controlled mutation.
 * Presence of this plan does not validate, authorize, execute, apply, consolidate, or mutate downstream state.
 * Authorization must be checked again immediately before any future mutation.
 */
data class LearningApplicationMutationPlan(
    val id: LearningApplicationMutationPlanId,
    val application: LearningApplicationIntentReference,
    val destination: LearningApplicationMutationDestination,
    val createdAt: Instant
)

data class LearningApplicationMutationPlanSnapshot(
    val plan: LearningApplicationMutationPlan,
    val generation: LearningApplicationMutationPlanGeneration
)
