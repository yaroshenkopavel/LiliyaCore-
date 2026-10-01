package pro.liliya.core.memory

import java.text.Normalizer
import java.util.Locale

enum class MemoryRetentionClass {
    WORKING,
    EPISODIC,
    SEMANTIC
}

data class MemoryRetentionBudget(
    val maxRecords: Int,
    val maxContentChars: Int
) {
    init {
        require(maxRecords >= 0) { "memory retention record budget must not be negative" }
        require(maxContentChars >= 0) { "memory retention content budget must not be negative" }
    }
}

class MemoryRetentionPolicy(
    budgets: Map<MemoryRetentionClass, MemoryRetentionBudget>
) {
    private val budgetSnapshot = budgets.toMap()

    init {
        require(budgetSnapshot.keys == MemoryRetentionClass.entries.toSet()) {
            "memory retention policy must define every retention class"
        }
    }

    fun budgetFor(retentionClass: MemoryRetentionClass): MemoryRetentionBudget =
        checkNotNull(budgetSnapshot[retentionClass])

    fun snapshot(): Map<MemoryRetentionClass, MemoryRetentionBudget> = budgetSnapshot.toMap()

    override fun toString(): String = "MemoryRetentionPolicy(budgets=$budgetSnapshot)"
}

class MemoryRetentionCandidate(
    val snapshot: MemoryRecordSnapshot,
    val retentionClass: MemoryRetentionClass
) {
    override fun equals(other: Any?): Boolean =
        other is MemoryRetentionCandidate &&
            snapshot == other.snapshot &&
            retentionClass == other.retentionClass

    override fun hashCode(): Int = 31 * snapshot.hashCode() + retentionClass.hashCode()

    override fun toString(): String =
        "MemoryRetentionCandidate(recordId=${snapshot.record.id}, generation=${snapshot.generation}, retentionClass=$retentionClass)"
}

enum class MemoryRetentionDisposition {
    RETAINED,
    DUPLICATE_SUPPRESSED,
    RECORD_BUDGET_REJECTED,
    CONTENT_BUDGET_REJECTED
}

data class MemoryRetentionDecision(
    val recordId: MemoryRecordId,
    val retentionClass: MemoryRetentionClass,
    val disposition: MemoryRetentionDisposition,
    val duplicateOf: MemoryRecordId? = null
)

class MemoryRetentionPlan(
    retained: List<MemoryRetentionCandidate>,
    decisions: List<MemoryRetentionDecision>
) {
    private val retainedSnapshot = retained.toList()
    private val decisionSnapshot = decisions.toList()

    val retained: List<MemoryRetentionCandidate>
        get() = retainedSnapshot.toList()

    val decisions: List<MemoryRetentionDecision>
        get() = decisionSnapshot.toList()

    override fun toString(): String =
        "MemoryRetentionPlan(retained=${retainedSnapshot.size}, decisions=${decisionSnapshot.size})"
}

internal object MemoryRetentionFingerprint {
    fun of(content: String): String = Normalizer.normalize(content, Normalizer.Form.NFKC)
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
}
