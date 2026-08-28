package pro.liliya.core.identity

import java.time.Instant
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItemId

@JvmInline
value class SelfIdentityId(val value: String) {
    init {
        require(value.isNotBlank()) { "self identity id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class SelfName(val value: String) {
    init {
        require(value.isNotBlank()) { "self name must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class SelfSourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "self source id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class SelfSourceReference(val value: String) {
    init {
        require(value.isNotBlank()) { "self source reference must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class SelfGeneration(val value: Long) {
    init {
        require(value > 0L) { "self generation must be positive" }
    }

    override fun toString(): String = value.toString()
}

sealed interface SelfOrigin {
    data class Knowledge(
        val itemId: KnowledgeItemId,
        val generation: KnowledgeGeneration
    ) : SelfOrigin

    data class Declared(
        val sourceId: SelfSourceId,
        val sourceReference: SelfSourceReference? = null
    ) : SelfOrigin
}

data class SelfIdentity(
    val id: SelfIdentityId,
    val name: SelfName,
    val origin: SelfOrigin,
    val createdAt: Instant
)

data class SelfIdentitySnapshot(
    val identity: SelfIdentity,
    val generation: SelfGeneration
)
