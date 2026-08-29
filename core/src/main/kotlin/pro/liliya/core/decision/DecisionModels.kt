package pro.liliya.core.decision

import java.time.Instant
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningGeneration

@JvmInline
value class DecisionId(val value: String) {
    init { require(value.isNotBlank()) { "decision id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class DecisionOptionId(val value: String) {
    init { require(value.isNotBlank()) { "decision option id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class DecisionGeneration(val value: Long) {
    init { require(value > 0L) { "decision generation must be positive" } }
    override fun toString(): String = value.toString()
}

sealed interface DecisionInputReference {
    data class Planning(
        val proposalId: PlanningProposalId,
        val generation: PlanningGeneration
    ) : DecisionInputReference

    data class Reasoning(
        val artifactId: ReasoningArtifactId,
        val generation: ReasoningGeneration
    ) : DecisionInputReference
}

class DecisionOption(
    val id: DecisionOptionId,
    val description: String
) {
    init { require(description.isNotBlank()) { "decision option description must not be blank" } }

    override fun equals(other: Any?): Boolean =
        other is DecisionOption && id == other.id && description == other.description

    override fun hashCode(): Int = 31 * id.hashCode() + description.hashCode()

    override fun toString(): String = "DecisionOption(id=$id, description=<redacted>)"
}

class DecisionRecord(
    val id: DecisionId,
    inputs: List<DecisionInputReference>,
    options: List<DecisionOption>,
    val selectedOptionId: DecisionOptionId,
    val rationale: String,
    val createdAt: Instant
) {
    val inputs: List<DecisionInputReference> = inputs.toList()
    val options: List<DecisionOption> = options.toList()

    init {
        require(this.inputs.isNotEmpty()) { "decision must contain at least one structural input reference" }
        require(this.options.isNotEmpty()) { "decision must contain at least one option" }
        require(this.options.map { it.id }.toSet().size == this.options.size) {
            "decision option ids must be unique"
        }
        require(this.options.any { it.id == selectedOptionId }) {
            "selected decision option must be present in options"
        }
        require(rationale.isNotBlank()) { "decision rationale must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is DecisionRecord &&
            id == other.id && inputs == other.inputs && options == other.options &&
            selectedOptionId == other.selectedOptionId && rationale == other.rationale &&
            createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + inputs.hashCode()
        result = 31 * result + options.hashCode()
        result = 31 * result + selectedOptionId.hashCode()
        result = 31 * result + rationale.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "DecisionRecord(id=$id, inputs=$inputs, options=<redacted:${options.size}>, selectedOptionId=$selectedOptionId, rationale=<redacted>, createdAt=$createdAt)"
}

data class DecisionSnapshot(
    val decision: DecisionRecord,
    val generation: DecisionGeneration
)
