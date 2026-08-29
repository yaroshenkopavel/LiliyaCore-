package pro.liliya.core.agent

import java.time.Instant

@JvmInline
value class AgentCoordinationId(val value: String) {
    init { require(value.isNotBlank()) { "agent coordination id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class AgentCoordinationGeneration(val value: Long) {
    init { require(value > 0L) { "agent coordination generation must be positive" } }
    override fun toString(): String = value.toString()
}

class AgentCoordinationRecord(
    val id: AgentCoordinationId,
    participants: List<ExactAgentReference>,
    val purpose: String,
    val createdAt: Instant
) {
    val participants: List<ExactAgentReference> = participants.toList()

    init {
        require(this.participants.size >= 2) { "agent coordination requires at least two participants" }
        require(this.participants.distinct().size == this.participants.size) {
            "agent coordination participants must be exact-reference unique"
        }
        require(this.participants.map { it.id }.distinct().size == this.participants.size) {
            "agent coordination cannot contain multiple generations of the same agent id"
        }
        require(purpose.isNotBlank()) { "agent coordination purpose must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is AgentCoordinationRecord &&
            id == other.id &&
            participants == other.participants &&
            purpose == other.purpose &&
            createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + participants.hashCode()
        result = 31 * result + purpose.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "AgentCoordinationRecord(id=$id, participants=$participants, purpose=<redacted>, createdAt=$createdAt)"
}

data class AgentCoordinationSnapshot(
    val coordination: AgentCoordinationRecord,
    val generation: AgentCoordinationGeneration
)
