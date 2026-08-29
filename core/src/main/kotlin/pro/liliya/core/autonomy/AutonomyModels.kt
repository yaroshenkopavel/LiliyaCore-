package pro.liliya.core.autonomy

import java.time.Instant
import pro.liliya.core.reflection.ReflectionGeneration
import pro.liliya.core.reflection.ReflectionRecordId

@JvmInline
value class AutonomyProposalId(val value: String) {
    init { require(value.isNotBlank()) { "autonomy proposal id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class AutonomySourceId(val value: String) {
    init { require(value.isNotBlank()) { "autonomy source id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class AutonomySourceReference(val value: String) {
    init { require(value.isNotBlank()) { "autonomy source reference must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class AutonomyGeneration(val value: Long) {
    init { require(value > 0L) { "autonomy generation must be positive" } }
    override fun toString(): String = value.toString()
}

enum class AutonomyPriority {
    LOW,
    NORMAL,
    HIGH
}

data class AutonomyBudget(
    val maxAttempts: Int
) {
    init { require(maxAttempts > 0) { "autonomy max attempts must be positive" } }
}

sealed interface AutonomyOrigin {
    data class Reflection(
        val recordId: ReflectionRecordId,
        val generation: ReflectionGeneration
    ) : AutonomyOrigin

    data class Declared(
        val sourceId: AutonomySourceId,
        val sourceReference: AutonomySourceReference? = null
    ) : AutonomyOrigin
}

class AutonomyProposal(
    val id: AutonomyProposalId,
    val origin: AutonomyOrigin,
    val objective: String,
    val triggerDescription: String,
    val priority: AutonomyPriority,
    val budget: AutonomyBudget,
    val createdAt: Instant
) {
    init {
        require(objective.isNotBlank()) { "autonomy objective must not be blank" }
        require(triggerDescription.isNotBlank()) { "autonomy trigger description must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is AutonomyProposal &&
            id == other.id &&
            origin == other.origin &&
            objective == other.objective &&
            triggerDescription == other.triggerDescription &&
            priority == other.priority &&
            budget == other.budget &&
            createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + origin.hashCode()
        result = 31 * result + objective.hashCode()
        result = 31 * result + triggerDescription.hashCode()
        result = 31 * result + priority.hashCode()
        result = 31 * result + budget.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "AutonomyProposal(id=$id, origin=$origin, objective=<redacted>, " +
            "triggerDescription=<redacted>, priority=$priority, budget=$budget, createdAt=$createdAt)"
}

data class AutonomySnapshot(
    val proposal: AutonomyProposal,
    val generation: AutonomyGeneration
)
