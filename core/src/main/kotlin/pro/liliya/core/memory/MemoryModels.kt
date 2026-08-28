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

@JvmInline
value class MemorySourceReference(val value: String) {
    init {
        require(value.isNotBlank()) { "memory source reference must not be blank" }
    }

    override fun toString(): String = value
}

data class MemoryProvenance(
    val sourceId: MemorySourceId,
    val sourceReference: MemorySourceReference? = null
)

data class MemoryRecord(
    val id: MemoryRecordId,
    val provenance: MemoryProvenance,
    val content: String,
    val createdAt: Instant
) {
    constructor(
        id: MemoryRecordId,
        sourceId: MemorySourceId,
        content: String,
        createdAt: Instant
    ) : this(
        id = id,
        provenance = MemoryProvenance(sourceId = sourceId),
        content = content,
        createdAt = createdAt
    )

    val sourceId: MemorySourceId
        get() = provenance.sourceId

    init {
        require(content.isNotBlank()) { "memory content must not be blank" }
    }
}
