package pro.liliya.core.planning

import java.time.Instant

@JvmInline
value class PlanningProposalId(val value: String) {
    init { require(value.isNotBlank()) { "planning proposal id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class PlanningStepId(val value: String) {
    init { require(value.isNotBlank()) { "planning step id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class PlanningSourceId(val value: String) {
    init { require(value.isNotBlank()) { "planning source id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class PlanningSourceReference(val value: String) {
    init { require(value.isNotBlank()) { "planning source reference must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class PlanningGeneration(val value: Long) {
    init { require(value > 0L) { "planning generation must be positive" } }
    override fun toString(): String = value.toString()
}

data class PlanningOrigin(
    val sourceId: PlanningSourceId,
    val sourceReference: PlanningSourceReference? = null
)

class PlanningStep(
    val id: PlanningStepId,
    val description: String
) {
    init { require(description.isNotBlank()) { "planning step description must not be blank" } }

    override fun equals(other: Any?): Boolean =
        other is PlanningStep && id == other.id && description == other.description

    override fun hashCode(): Int = 31 * id.hashCode() + description.hashCode()

    override fun toString(): String = "PlanningStep(id=$id, description=<redacted>)"
}

class PlanningProposal(
    val id: PlanningProposalId,
    val origin: PlanningOrigin,
    val goal: String,
    steps: List<PlanningStep>,
    val createdAt: Instant
) {
    val steps: List<PlanningStep> = steps.toList()

    init {
        require(goal.isNotBlank()) { "planning goal must not be blank" }
        require(this.steps.isNotEmpty()) { "planning proposal must contain at least one step" }
        require(this.steps.map { it.id }.toSet().size == this.steps.size) {
            "planning step ids must be unique"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is PlanningProposal &&
            id == other.id &&
            origin == other.origin &&
            goal == other.goal &&
            steps == other.steps &&
            createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + origin.hashCode()
        result = 31 * result + goal.hashCode()
        result = 31 * result + steps.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "PlanningProposal(id=$id, origin=$origin, goal=<redacted>, steps=<redacted:${steps.size}>, createdAt=$createdAt)"
}

data class PlanningProposalSnapshot(
    val proposal: PlanningProposal,
    val generation: PlanningGeneration
)
