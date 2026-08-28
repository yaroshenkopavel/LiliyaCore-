package pro.liliya.core.knowledge

import java.time.Instant
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId

@JvmInline
value class KnowledgeItemId(val value: String) {
    init {
        require(value.isNotBlank()) { "knowledge item id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class KnowledgeSourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "knowledge source id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class KnowledgeSourceReference(val value: String) {
    init {
        require(value.isNotBlank()) { "knowledge source reference must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class KnowledgeGeneration(val value: Long) {
    init {
        require(value > 0L) { "knowledge generation must be positive" }
    }

    override fun toString(): String = value.toString()
}

sealed interface KnowledgeOrigin {
    data class Memory(
        val recordId: MemoryRecordId,
        val generation: MemoryGeneration
    ) : KnowledgeOrigin

    data class Declared(
        val sourceId: KnowledgeSourceId,
        val sourceReference: KnowledgeSourceReference? = null
    ) : KnowledgeOrigin
}

data class KnowledgeItem(
    val id: KnowledgeItemId,
    val origin: KnowledgeOrigin,
    val content: String,
    val createdAt: Instant
) {
    init {
        require(content.isNotBlank()) { "knowledge content must not be blank" }
    }
}

data class KnowledgeItemSnapshot(
    val item: KnowledgeItem,
    val generation: KnowledgeGeneration
)
