package pro.liliya.core.memory

import java.time.Instant

@JvmInline
value class MemoryRetentionTransactionId(val value: String) {
    init { require(value.isNotBlank()) { "memory retention transaction id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class MemoryRetentionTransactionGeneration(val value: Long) {
    init { require(value > 0L) { "memory retention transaction generation must be positive" } }
    override fun toString(): String = value.toString()
}

data class MemoryRetentionTransactionReference(
    val transactionId: MemoryRetentionTransactionId,
    val generation: MemoryRetentionTransactionGeneration
)

data class MemoryRetentionTransactionTarget(
    val recordId: MemoryRecordId,
    val generation: MemoryGeneration,
    val retentionClass: MemoryRetentionClass,
    val disposition: MemoryRetentionDisposition
) {
    init {
        require(disposition != MemoryRetentionDisposition.RETAINED) {
            "retained memory must not be a retention transaction mutation target"
        }
    }

    override fun toString(): String =
        "MemoryRetentionTransactionTarget(recordId=$recordId, generation=$generation, retentionClass=$retentionClass, disposition=$disposition)"
}

class MemoryRetentionTransactionPlan(
    val id: MemoryRetentionTransactionId,
    targets: List<MemoryRetentionTransactionTarget>,
    val createdAt: Instant
) {
    private val targetSnapshot = canonicalTargets(targets)

    init {
        require(targetSnapshot.isNotEmpty()) {
            "memory retention transaction must contain at least one prune target"
        }
        require(targetSnapshot.map { it.recordId }.toSet().size == targetSnapshot.size) {
            "memory retention transaction must not contain duplicate record ids"
        }
    }

    val targets: List<MemoryRetentionTransactionTarget>
        get() = targetSnapshot.toList()

    override fun equals(other: Any?): Boolean =
        other is MemoryRetentionTransactionPlan &&
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
        "MemoryRetentionTransactionPlan(id=$id, targets=${targetSnapshot.size}, createdAt=$createdAt)"

    companion object {
        internal fun canonicalTargets(
            targets: List<MemoryRetentionTransactionTarget>
        ): List<MemoryRetentionTransactionTarget> = targets.sortedWith(
            compareBy<MemoryRetentionTransactionTarget>(
                { it.retentionClass.ordinal },
                { it.recordId.value },
                { it.generation.value },
                { it.disposition.ordinal }
            )
        )
    }
}

enum class MemoryRetentionTransactionState {
    PLANNED,
    AUTHORIZED,
    COMMITTED,
    REJECTED,
    RECOVERY_REQUIRED
}

data class MemoryRetentionTransactionSnapshot(
    val plan: MemoryRetentionTransactionPlan,
    val generation: MemoryRetentionTransactionGeneration,
    val state: MemoryRetentionTransactionState
) {
    val reference: MemoryRetentionTransactionReference
        get() = MemoryRetentionTransactionReference(plan.id, generation)

    override fun toString(): String =
        "MemoryRetentionTransactionSnapshot(transactionId=${plan.id}, generation=$generation, state=$state, targets=${plan.targets.size})"
}
