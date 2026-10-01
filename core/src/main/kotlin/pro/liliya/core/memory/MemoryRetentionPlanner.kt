package pro.liliya.core.memory

class MemoryRetentionPlanner(
    private val policy: MemoryRetentionPolicy
) {
    fun plan(candidates: List<MemoryRetentionCandidate>): MemoryRetentionPlan {
        val retained = mutableListOf<MemoryRetentionCandidate>()
        val decisions = mutableListOf<MemoryRetentionDecision>()

        MemoryRetentionClass.entries.forEach { retentionClass ->
            val budget = policy.budgetFor(retentionClass)
            var retainedCount = 0
            var retainedChars = 0
            val retainedFingerprints = linkedMapOf<String, MemoryRecordId>()

            candidates
                .asSequence()
                .filter { it.retentionClass == retentionClass }
                .sortedWith(
                    compareByDescending<MemoryRetentionCandidate> { it.snapshot.record.createdAt }
                        .thenBy { it.snapshot.record.id.value }
                        .thenByDescending { it.snapshot.generation.value }
                )
                .forEach { candidate ->
                    val record = candidate.snapshot.record
                    val fingerprint = MemoryRetentionFingerprint.of(record.content)
                    val duplicateOf = retainedFingerprints[fingerprint]
                    when {
                        duplicateOf != null -> decisions += MemoryRetentionDecision(
                            recordId = record.id,
                            retentionClass = retentionClass,
                            disposition = MemoryRetentionDisposition.DUPLICATE_SUPPRESSED,
                            duplicateOf = duplicateOf
                        )

                        retainedCount >= budget.maxRecords -> decisions += MemoryRetentionDecision(
                            recordId = record.id,
                            retentionClass = retentionClass,
                            disposition = MemoryRetentionDisposition.RECORD_BUDGET_REJECTED
                        )

                        retainedChars + record.content.length > budget.maxContentChars -> decisions += MemoryRetentionDecision(
                            recordId = record.id,
                            retentionClass = retentionClass,
                            disposition = MemoryRetentionDisposition.CONTENT_BUDGET_REJECTED
                        )

                        else -> {
                            retained += candidate
                            retainedFingerprints[fingerprint] = record.id
                            retainedCount += 1
                            retainedChars += record.content.length
                            decisions += MemoryRetentionDecision(
                                recordId = record.id,
                                retentionClass = retentionClass,
                                disposition = MemoryRetentionDisposition.RETAINED
                            )
                        }
                    }
                }
        }

        return MemoryRetentionPlan(retained, decisions)
    }
}
