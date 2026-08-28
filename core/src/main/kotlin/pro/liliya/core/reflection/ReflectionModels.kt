package pro.liliya.core.reflection

import java.time.Instant
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId

@JvmInline
value class ReflectionRecordId(val value: String) {
    init { require(value.isNotBlank()) { "reflection record id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class ReflectionSourceId(val value: String) {
    init { require(value.isNotBlank()) { "reflection source id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class ReflectionSourceReference(val value: String) {
    init { require(value.isNotBlank()) { "reflection source reference must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class ReflectionGeneration(val value: Long) {
    init { require(value > 0L) { "reflection generation must be positive" } }
    override fun toString(): String = value.toString()
}

sealed interface ReflectionOrigin {
    data class Memory(
        val recordId: MemoryRecordId,
        val generation: MemoryGeneration
    ) : ReflectionOrigin

    data class Knowledge(
        val itemId: KnowledgeItemId,
        val generation: KnowledgeGeneration
    ) : ReflectionOrigin

    data class Declared(
        val sourceId: ReflectionSourceId,
        val sourceReference: ReflectionSourceReference? = null
    ) : ReflectionOrigin
}

class ReflectionRecord(
    val id: ReflectionRecordId,
    val origin: ReflectionOrigin,
    val content: String,
    val createdAt: Instant
) {
    init { require(content.isNotBlank()) { "reflection content must not be blank" } }

    override fun equals(other: Any?): Boolean =
        other is ReflectionRecord &&
            id == other.id && origin == other.origin && content == other.content && createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + origin.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "ReflectionRecord(id=$id, origin=$origin, content=<redacted>, createdAt=$createdAt)"
}

data class ReflectionRecordSnapshot(
    val record: ReflectionRecord,
    val generation: ReflectionGeneration
)
