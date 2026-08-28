package pro.liliya.core.memory

import java.time.Instant

@JvmInline
value class MemoryRecordId(val value: String) {
    init {
        require(value.isNotBlank()) { "memory record id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class MemorySourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "memory source id must not be blank" }
    }

    override fun toString(): String = value
}

data class MemoryRecord(
    val id: MemoryRecordId,
    val sourceId: MemorySourceId,
    val content: String,
    val createdAt: Instant
) {
    init {
        require(content.isNotBlank()) { "memory content must not be blank" }
    }
}
