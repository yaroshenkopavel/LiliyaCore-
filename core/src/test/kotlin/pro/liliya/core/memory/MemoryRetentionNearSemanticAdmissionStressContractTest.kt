package pro.liliya.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MemoryRetentionNearSemanticAdmissionStressContractTest {
    @Test
    fun bounded_large_evidence_set_is_deterministic_independent_of_ledger_and_evidence_order() {
        val candidateCount = 500
        val anchors = listOf(
            entry("anchor-a", 1, MemoryRetentionDisposition.RETAINED),
            entry("anchor-b", 2, MemoryRetentionDisposition.RETAINED)
        )
        val candidates = (0 until candidateCount).map { index ->
            entry(
                id = "candidate-${index.toString().padStart(4, '0')}",
                generation = 100L + index,
                disposition = if (index % 2 == 0) {
                    MemoryRetentionDisposition.RECORD_BUDGET_REJECTED
                } else {
                    MemoryRetentionDisposition.CONTENT_BUDGET_REJECTED
                }
            )
        }
        val ledgerEntries = anchors + candidates
        val evidence = candidates.mapIndexed { index, candidate ->
            MemoryRetentionNearSemanticEvidence(
                left = MemoryRetentionSemanticReference(candidate.recordId, candidate.generation),
                right = MemoryRetentionSemanticReference(
                    recordId = if (index % 3 == 0) MemoryRecordId("anchor-b") else MemoryRecordId("anchor-a"),
                    generation = if (index % 3 == 0) MemoryGeneration(2) else MemoryGeneration(1)
                ),
                similarity = if (index % 5 == 0) 0.89 else 0.95
            )
        }
        val admission = MemoryRetentionNearSemanticAdmission(
            MemoryRetentionNearSemanticPolicy(
                minimumSimilarity = 0.90,
                maxEvidence = candidateCount
            )
        )

        val first = assertIs<MemoryRetentionNearSemanticAdmissionResult.Evaluated>(
            admission.evaluate(MemoryRetentionLedger(ledgerEntries), evidence)
        )
        val second = assertIs<MemoryRetentionNearSemanticAdmissionResult.Evaluated>(
            admission.evaluate(MemoryRetentionLedger(ledgerEntries.reversed()), evidence.reversed())
        )

        assertEquals(first, second)
        assertEquals(candidateCount, first.decisions.size)
        assertEquals(400, first.decisions.count {
            it.disposition == MemoryRetentionNearSemanticDisposition.ADMITTED
        })
        assertEquals(100, first.decisions.count {
            it.disposition == MemoryRetentionNearSemanticDisposition.NO_MATCH
        })
        assertEquals(
            candidates.map { it.recordId.value }.sorted(),
            first.decisions.map { it.candidate.recordId.value }
        )
    }

    private fun entry(
        id: String,
        generation: Long,
        disposition: MemoryRetentionDisposition
    ): MemoryRetentionLedgerEntry = MemoryRetentionLedgerEntry(
        recordId = MemoryRecordId(id),
        generation = MemoryGeneration(generation),
        retentionClass = MemoryRetentionClass.EPISODIC,
        disposition = disposition,
        action = if (disposition == MemoryRetentionDisposition.RETAINED) {
            MemoryRetentionShadowAction.KEEP
        } else {
            MemoryRetentionShadowAction.PRUNE_CANDIDATE
        }
    )
}
