package pro.liliya.core.autonomy

import java.time.Instant

@JvmInline
value class AutonomyDeliberationRequestId(val value: String) {
    init { require(value.isNotBlank()) { "autonomy deliberation request id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class AutonomyDeliberationGeneration(val value: Long) {
    init { require(value > 0L) { "autonomy deliberation generation must be positive" } }
    override fun toString(): String = value.toString()
}

data class AutonomyAttemptReference(
    val proposalId: AutonomyProposalId,
    val proposalGeneration: AutonomyGeneration,
    val attemptNumber: Int
) {
    init { require(attemptNumber > 0) { "autonomy deliberation attempt number must be positive" } }
}

class AutonomyDeliberationRequest(
    val id: AutonomyDeliberationRequestId,
    val autonomy: AutonomyAttemptReference,
    val objective: String,
    val createdAt: Instant
) {
    init { require(objective.isNotBlank()) { "autonomy deliberation objective must not be blank" } }

    override fun equals(other: Any?): Boolean =
        other is AutonomyDeliberationRequest &&
            id == other.id && autonomy == other.autonomy && objective == other.objective && createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + autonomy.hashCode()
        result = 31 * result + objective.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "AutonomyDeliberationRequest(id=$id, autonomy=$autonomy, objective=<redacted>, createdAt=$createdAt)"
}

data class AutonomyDeliberationSnapshot(
    val request: AutonomyDeliberationRequest,
    val generation: AutonomyDeliberationGeneration
)
