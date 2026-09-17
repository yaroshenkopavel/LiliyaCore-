package pro.liliya.core.memory

import java.time.Instant

@JvmInline
value class MemoryRetentionMultiTargetId(val value: String) {
    init { require(value.isNotBlank()) { "memory retention multi-target id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class MemoryRetentionMultiTargetGeneration(val value: Long) {
    init { require(value > 0L) { "memory retention multi-target generation must be positive" } }
    override fun toString(): String = value.toString()
}

data class MemoryRetentionMultiTargetReference(
    val id: MemoryRetentionMultiTargetId,
    val generation: MemoryRetentionMultiTargetGeneration
)

enum class MemoryRetentionMultiTargetState {
    ACTIVE,
    COMPLETED,
    REJECTED
}

/**
 * Durable ordered parent plan for one or more exact retention targets.
 *
 * The historical class name is retained for compatibility, but a one-target parent is deliberate:
 * all reviewed prune plans share one durable parent namespace, while the single-target transaction
 * journal remains an internal child execution primitive.
 */
class MemoryRetentionMultiTargetPlan(
    val id: MemoryRetentionMultiTargetId,
    targets: List<MemoryRetentionTransactionTarget>,
    val createdAt: Instant
) {
    private val targetSnapshot = MemoryRetentionTransactionPlan.canonicalTargets(targets)

    init {
        require(targetSnapshot.isNotEmpty()) {
            "ordered retention parent plan requires at least one target"
        }
        require(targetSnapshot.map { it.recordId }.toSet().size == targetSnapshot.size) {
            "multi-target retention plan must not contain duplicate record ids"
        }
    }

    val targets: List<MemoryRetentionTransactionTarget>
        get() = targetSnapshot.toList()

    override fun equals(other: Any?): Boolean =
        other is MemoryRetentionMultiTargetPlan &&
            id == other.id &&
            targetSnapshot == other.targetSnapshot &&
            createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + targetSnapshot.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "MemoryRetentionMultiTargetPlan(id=$id, targets=${targetSnapshot.size}, createdAt=$createdAt)"
}

data class MemoryRetentionMultiTargetSnapshot(
    val plan: MemoryRetentionMultiTargetPlan,
    val generation: MemoryRetentionMultiTargetGeneration,
    val nextIndex: Int,
    val state: MemoryRetentionMultiTargetState
) {
    init {
        require(nextIndex in 0..plan.targets.size) {
            "multi-target retention next index is outside target bounds"
        }
        require(state != MemoryRetentionMultiTargetState.COMPLETED || nextIndex == plan.targets.size) {
            "completed multi-target retention must point past the final target"
        }
        require(state != MemoryRetentionMultiTargetState.ACTIVE || nextIndex < plan.targets.size) {
            "active multi-target retention must have a current target"
        }
    }

    val reference: MemoryRetentionMultiTargetReference
        get() = MemoryRetentionMultiTargetReference(plan.id, generation)

    val currentTarget: MemoryRetentionTransactionTarget?
        get() = if (state == MemoryRetentionMultiTargetState.ACTIVE) plan.targets[nextIndex] else null

    fun childTransactionId(index: Int = nextIndex): MemoryRetentionTransactionId {
        require(index in plan.targets.indices) { "multi-target retention child index is outside target bounds" }
        return MemoryRetentionTransactionId("${plan.id.value}:target:$index")
    }

    override fun toString(): String =
        "MemoryRetentionMultiTargetSnapshot(id=${plan.id}, generation=$generation, nextIndex=$nextIndex, state=$state, targets=${plan.targets.size})"
}
