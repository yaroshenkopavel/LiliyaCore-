package pro.liliya.core.orchestration

import java.time.Instant
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.decision.DecisionOptionId

@JvmInline
value class OrchestrationIntentId(val value: String) {
    init { require(value.isNotBlank()) { "orchestration intent id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class OrchestrationGeneration(val value: Long) {
    init { require(value > 0L) { "orchestration generation must be positive" } }
    override fun toString(): String = value.toString()
}

data class OrchestrationDecisionReference(
    val decisionId: DecisionId,
    val generation: DecisionGeneration,
    val selectedOptionId: DecisionOptionId
)

class OrchestrationIntent(
    val id: OrchestrationIntentId,
    val decision: OrchestrationDecisionReference,
    val description: String,
    val createdAt: Instant
) {
    init {
        require(description.isNotBlank()) { "orchestration intent description must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is OrchestrationIntent &&
            id == other.id &&
            decision == other.decision &&
            description == other.description &&
            createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + decision.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "OrchestrationIntent(id=$id, decision=$decision, description=<redacted>, createdAt=$createdAt)"
}

data class OrchestrationSnapshot(
    val intent: OrchestrationIntent,
    val generation: OrchestrationGeneration
)
