package pro.liliya.core.learning

import java.time.Instant
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.memory.MemoryRecord

@JvmInline
value class LearningApplicationMutationId(val value: String) {
    init { require(value.isNotBlank()) { "learning application mutation id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class LearningApplicationMutationGeneration(val value: Long) {
    init { require(value > 0L) { "learning application mutation generation must be positive" } }
    override fun toString(): String = value.toString()
}

@JvmInline
value class LearningApplicationIdempotencyKey(val value: String) {
    init { require(value.isNotBlank()) { "learning application idempotency key must not be blank" } }
    override fun toString(): String = value
}

sealed interface LearningApplicationMutationPayload {
    class Memory(val record: MemoryRecord) : LearningApplicationMutationPayload {
        override fun equals(other: Any?): Boolean = other is Memory && record == other.record
        override fun hashCode(): Int = record.hashCode()
        override fun toString(): String = "Memory(recordId=${record.id})"
    }

    class Knowledge(val item: KnowledgeItem) : LearningApplicationMutationPayload {
        override fun equals(other: Any?): Boolean = other is Knowledge && item == other.item
        override fun hashCode(): Int = item.hashCode()
        override fun toString(): String = "Knowledge(itemId=${item.id})"
    }
}

class LearningApplicationMutationPlan(
    val id: LearningApplicationMutationId,
    val application: LearningApplicationIntentReference,
    val principal: AuthorityPrincipal,
    val target: LearningApplicationTarget,
    val idempotencyKey: LearningApplicationIdempotencyKey,
    val payload: LearningApplicationMutationPayload,
    val createdAt: Instant
) {
    init {
        require(
            when (target) {
                LearningApplicationTarget.MEMORY -> payload is LearningApplicationMutationPayload.Memory
                LearningApplicationTarget.KNOWLEDGE -> payload is LearningApplicationMutationPayload.Knowledge
            }
        ) { "learning application mutation payload must match target" }
    }

    override fun equals(other: Any?): Boolean =
        other is LearningApplicationMutationPlan &&
            id == other.id &&
            application == other.application &&
            principal == other.principal &&
            target == other.target &&
            idempotencyKey == other.idempotencyKey &&
            payload == other.payload &&
            createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + application.hashCode()
        result = 31 * result + principal.hashCode()
        result = 31 * result + target.hashCode()
        result = 31 * result + idempotencyKey.hashCode()
        result = 31 * result + payload.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "LearningApplicationMutationPlan(id=$id, application=$application, principal=$principal, " +
            "target=$target, idempotencyKey=$idempotencyKey, payload=$payload, createdAt=$createdAt)"
}

data class LearningApplicationMutationSnapshot(
    val plan: LearningApplicationMutationPlan,
    val generation: LearningApplicationMutationGeneration
)
