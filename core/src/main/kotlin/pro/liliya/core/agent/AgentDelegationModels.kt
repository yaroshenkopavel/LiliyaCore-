package pro.liliya.core.agent

import java.time.Instant

@JvmInline
value class AgentDelegationId(val value: String) {
    init { require(value.isNotBlank()) { "agent delegation id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class AgentDelegationGeneration(val value: Long) {
    init { require(value > 0L) { "agent delegation generation must be positive" } }
    override fun toString(): String = value.toString()
}

data class ExactAgentReference(
    val id: AgentId,
    val generation: AgentGeneration
)

class AgentDelegationRecord(
    val id: AgentDelegationId,
    val parent: ExactAgentReference,
    val child: ExactAgentReference,
    val purpose: String,
    val createdAt: Instant
) {
    init {
        require(parent.id != child.id) { "agent delegation self-reference is not allowed" }
        require(purpose.isNotBlank()) { "agent delegation purpose must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is AgentDelegationRecord &&
            id == other.id &&
            parent == other.parent &&
            child == other.child &&
            purpose == other.purpose &&
            createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + parent.hashCode()
        result = 31 * result + child.hashCode()
        result = 31 * result + purpose.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "AgentDelegationRecord(" +
            "id=$id, parent=$parent, child=$child, purpose=<redacted>, createdAt=$createdAt)"
}

data class AgentDelegationSnapshot(
    val delegation: AgentDelegationRecord,
    val generation: AgentDelegationGeneration
)
