package pro.liliya.core.memory

data class MemoryRetentionLedgerEntry(
    val recordId: MemoryRecordId,
    val generation: MemoryGeneration,
    val retentionClass: MemoryRetentionClass,
    val disposition: MemoryRetentionDisposition,
    val duplicateOf: MemoryRecordId? = null
) {
    override fun toString(): String =
        "MemoryRetentionLedgerEntry(recordId=$recordId, generation=$generation, retentionClass=$retentionClass, disposition=$disposition, duplicateOf=$duplicateOf)"
}

data class MemoryRetentionLedgerSummary(
    val retained: Int,
    val duplicateSuppressed: Int,
    val recordBudgetRejected: Int,
    val contentBudgetRejected: Int
)

class MemoryRetentionLedger(entries: List<MemoryRetentionLedgerEntry>) {
    private val entrySnapshot = entries.toList()

    val entries: List<MemoryRetentionLedgerEntry>
        get() = entrySnapshot.toList()

    fun summary(retentionClass: MemoryRetentionClass): MemoryRetentionLedgerSummary {
        val decisions = entrySnapshot.filter { it.retentionClass == retentionClass }
        return MemoryRetentionLedgerSummary(
            retained = decisions.count { it.disposition == MemoryRetentionDisposition.RETAINED },
            duplicateSuppressed = decisions.count { it.disposition == MemoryRetentionDisposition.DUPLICATE_SUPPRESSED },
            recordBudgetRejected = decisions.count { it.disposition == MemoryRetentionDisposition.RECORD_BUDGET_REJECTED },
            contentBudgetRejected = decisions.count { it.disposition == MemoryRetentionDisposition.CONTENT_BUDGET_REJECTED }
        )
    }

    override fun equals(other: Any?): Boolean =
        other is MemoryRetentionLedger && entrySnapshot == other.entrySnapshot

    override fun hashCode(): Int = entrySnapshot.hashCode()

    override fun toString(): String = "MemoryRetentionLedger(entries=${entrySnapshot.size})"
}

data class MemoryRetentionShadowReport(
    val plan: MemoryRetentionPlan,
    val ledger: MemoryRetentionLedger
)

/**
 * Read-only consolidation simulation. This layer cannot mutate Memory and carries no Authority.
 * It turns a deterministic retention plan into an inspectable ledger suitable for policy review,
 * regression comparison and later governed execution.
 */
class MemoryRetentionShadowConsolidator(
    private val planner: MemoryRetentionPlanner
) {
    fun simulate(candidates: List<MemoryRetentionCandidate>): MemoryRetentionShadowReport {
        val byId = candidates.associateBy { it.snapshot.record.id }
        require(byId.size == candidates.size) {
            "shadow consolidation requires unique memory record ids"
        }

        val plan = planner.plan(candidates)
        require(plan.decisions.size == candidates.size) {
            "shadow consolidation requires exactly one decision per candidate"
        }

        val ledgerEntries = plan.decisions.map { decision ->
            val candidate = checkNotNull(byId[decision.recordId]) {
                "retention decision references unknown memory record ${decision.recordId}"
            }
            require(candidate.retentionClass == decision.retentionClass) {
                "retention decision class does not match candidate class"
            }
            MemoryRetentionLedgerEntry(
                recordId = decision.recordId,
                generation = candidate.snapshot.generation,
                retentionClass = decision.retentionClass,
                disposition = decision.disposition,
                duplicateOf = decision.duplicateOf
            )
        }

        return MemoryRetentionShadowReport(
            plan = plan,
            ledger = MemoryRetentionLedger(ledgerEntries)
        )
    }
}
