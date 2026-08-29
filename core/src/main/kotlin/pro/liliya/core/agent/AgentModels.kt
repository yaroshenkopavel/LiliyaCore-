package pro.liliya.core.agent

import java.time.Instant
import pro.liliya.core.autonomy.AutonomyGeneration
import pro.liliya.core.autonomy.AutonomyProposalId

@JvmInline
value class AgentId(val value: String) {
    init { require(value.isNotBlank()) { "agent id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class AgentGeneration(val value: Long) {
    init { require(value > 0L) { "agent generation must be positive" } }
    override fun toString(): String = value.toString()
}

@JvmInline
value class AgentSourceId(val value: String) {
    init { require(value.isNotBlank()) { "agent source id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class AgentSourceReference(val value: String) {
    init { require(value.isNotBlank()) { "agent source reference must not be blank" } }
    override fun toString(): String = value
}

sealed interface AgentOrigin {
    data class Declared(
        val sourceId: AgentSourceId,
        val sourceReference: AgentSourceReference? = null
    ) : AgentOrigin

    data class Autonomy(
        val proposalId: AutonomyProposalId,
        val generation: AutonomyGeneration
    ) : AgentOrigin
}

class AgentRecord(
    val id: AgentId,
    val origin: AgentOrigin,
    val role: String,
    val purpose: String,
    val createdAt: Instant
) {
    init {
        require(role.isNotBlank()) { "agent role must not be blank" }
        require(purpose.isNotBlank()) { "agent purpose must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is AgentRecord &&
            id == other.id &&
            origin == other.origin &&
            role == other.role &&
            purpose == other.purpose &&
            createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + origin.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + purpose.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "AgentRecord(id=$id, origin=$origin, role=<redacted>, purpose=<redacted>, createdAt=$createdAt)"
}

data class AgentSnapshot(
    val agent: AgentRecord,
    val generation: AgentGeneration
)
