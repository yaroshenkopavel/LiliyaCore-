package pro.liliya.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MemoryRetentionNearSemanticAdmissionContractTest {
    private val policy = MemoryRetentionNearSemanticPolicy(
        minimumSimilarity = 0.90,
        maxEvidence = 8
    )

    @Test
    fun only_budget_rejected_entries_are_evaluated_and_canonical_duplicates_keep_precedence() {
        val ledger = MemoryRetentionLedger(
            listOf(
                entry("anchor", 1, MemoryRetentionDisposition.RETAINED),
                entry(
                    "canonical-duplicate",
                    2,
                    MemoryRetentionDisposition.DUPLICATE_SUPPRESSED,
                    duplicateOf = "anchor"
                ),
                entry("candidate", 3, MemoryRetentionDisposition.RECORD_BUDGET_REJECTED)
            )
        )
        val result = assertIs<MemoryRetentionNearSemanticAdmissionResult.Evaluated>(
            admission().evaluate(
                ledger,
                listOf(evidence("candidate", 3, "anchor", 1, 0.95))
            )
        )

        assertEquals(1, result.decisions.size)
        val decision = result.decisions.single()
        assertEquals(MemoryRecordId("candidate"), decision.candidate.recordId)
        assertEquals(MemoryRetentionNearSemanticDisposition.ADMITTED, decision.disposition)
        assertEquals(MemoryRecordId("anchor"), decision.anchor?.recordId)
        assertEquals(0.95, decision.similarity)
    }

    @Test
    fun strongest_baseline_anchor_wins_deterministically_independent_of_evidence_order() {
        val ledger = MemoryRetentionLedger(
            listOf(
                entry("anchor-b", 2, MemoryRetentionDisposition.RETAINED),
                entry("candidate", 3, MemoryRetentionDisposition.CONTENT_BUDGET_REJECTED),
                entry("anchor-a", 1, MemoryRetentionDisposition.RETAINED)
            )
        )
        val firstEvidence = listOf(
            evidence("candidate", 3, "anchor-a", 1, 0.94),
            evidence("anchor-b", 2, "candidate", 3, 0.97)
        )
        val secondEvidence = firstEvidence.reversed()

        val first = assertIs<MemoryRetentionNearSemanticAdmissionResult.Evaluated>(
            admission().evaluate(ledger, firstEvidence)
        )
        val second = assertIs<MemoryRetentionNearSemanticAdmissionResult.Evaluated>(
            admission().evaluate(ledger, secondEvidence)
        )

        assertEquals(first, second)
        assertEquals(MemoryRecordId("anchor-b"), first.decisions.single().anchor?.recordId)
        assertEquals(0.97, first.decisions.single().similarity)
    }

    @Test
    fun threshold_is_inclusive_and_missing_or_below_threshold_evidence_cannot_admit() {
        val ledger = MemoryRetentionLedger(
            listOf(
                entry("anchor", 1, MemoryRetentionDisposition.RETAINED),
                entry("at-threshold", 2, MemoryRetentionDisposition.RECORD_BUDGET_REJECTED),
                entry("below", 3, MemoryRetentionDisposition.RECORD_BUDGET_REJECTED),
                entry("missing", 4, MemoryRetentionDisposition.RECORD_BUDGET_REJECTED)
            )
        )
        val result = assertIs<MemoryRetentionNearSemanticAdmissionResult.Evaluated>(
            admission().evaluate(
                ledger,
                listOf(
                    evidence("at-threshold", 2, "anchor", 1, 0.90),
                    evidence("below", 3, "anchor", 1, 0.899)
                )
            )
        )
        val byId = result.decisions.associateBy { it.candidate.recordId.value }

        assertEquals(MemoryRetentionNearSemanticDisposition.ADMITTED, byId.getValue("at-threshold").disposition)
        assertEquals(MemoryRetentionNearSemanticDisposition.NO_MATCH, byId.getValue("below").disposition)
        assertNull(byId.getValue("below").anchor)
        assertEquals(MemoryRetentionNearSemanticDisposition.NO_MATCH, byId.getValue("missing").disposition)
    }

    @Test
    fun candidate_to_candidate_evidence_is_rejected_so_admission_cannot_chain_transitively() {
        val ledger = MemoryRetentionLedger(
            listOf(
                entry("anchor", 1, MemoryRetentionDisposition.RETAINED),
                entry("candidate-a", 2, MemoryRetentionDisposition.RECORD_BUDGET_REJECTED),
                entry("candidate-b", 3, MemoryRetentionDisposition.RECORD_BUDGET_REJECTED)
            )
        )
        val rejected = assertIs<MemoryRetentionNearSemanticAdmissionResult.Rejected>(
            admission().evaluate(
                ledger,
                listOf(
                    evidence("candidate-a", 2, "anchor", 1, 0.95),
                    evidence("candidate-b", 3, "candidate-a", 2, 0.99)
                )
            )
        )

        assertEquals(MemoryRetentionNearSemanticFailure.INELIGIBLE_PAIR, rejected.failure)
    }

    @Test
    fun stale_generation_cross_class_and_duplicate_pair_evidence_fail_closed() {
        val ledger = MemoryRetentionLedger(
            listOf(
                entry("anchor", 1, MemoryRetentionDisposition.RETAINED),
                entry("candidate", 2, MemoryRetentionDisposition.RECORD_BUDGET_REJECTED),
                entry(
                    "semantic-anchor",
                    3,
                    MemoryRetentionDisposition.RETAINED,
                    retentionClass = MemoryRetentionClass.SEMANTIC
                )
            )
        )

        val stale = assertIs<MemoryRetentionNearSemanticAdmissionResult.Rejected>(
            admission().evaluate(ledger, listOf(evidence("candidate", 999, "anchor", 1, 0.95)))
        )
        assertEquals(MemoryRetentionNearSemanticFailure.STALE_GENERATION, stale.failure)

        val crossClass = assertIs<MemoryRetentionNearSemanticAdmissionResult.Rejected>(
            admission().evaluate(ledger, listOf(evidence("candidate", 2, "semantic-anchor", 3, 0.95)))
        )
        assertEquals(MemoryRetentionNearSemanticFailure.CROSS_CLASS_PAIR, crossClass.failure)

        val duplicate = assertIs<MemoryRetentionNearSemanticAdmissionResult.Rejected>(
            admission().evaluate(
                ledger,
                listOf(
                    evidence("candidate", 2, "anchor", 1, 0.95),
                    evidence("anchor", 1, "candidate", 2, 0.95)
                )
            )
        )
        assertEquals(MemoryRetentionNearSemanticFailure.DUPLICATE_PAIR, duplicate.failure)
    }

    @Test
    fun invalid_scores_and_evidence_overflow_fail_closed() {
        val ledger = MemoryRetentionLedger(
            listOf(
                entry("anchor", 1, MemoryRetentionDisposition.RETAINED),
                entry("candidate", 2, MemoryRetentionDisposition.RECORD_BUDGET_REJECTED)
            )
        )

        val nonFinite = assertIs<MemoryRetentionNearSemanticAdmissionResult.Rejected>(
            admission().evaluate(ledger, listOf(evidence("candidate", 2, "anchor", 1, Double.NaN)))
        )
        assertEquals(MemoryRetentionNearSemanticFailure.NON_FINITE_SIMILARITY, nonFinite.failure)

        val outOfRange = assertIs<MemoryRetentionNearSemanticAdmissionResult.Rejected>(
            admission().evaluate(ledger, listOf(evidence("candidate", 2, "anchor", 1, 1.01)))
        )
        assertEquals(MemoryRetentionNearSemanticFailure.SIMILARITY_OUT_OF_RANGE, outOfRange.failure)

        val tinyBound = MemoryRetentionNearSemanticAdmission(
            MemoryRetentionNearSemanticPolicy(minimumSimilarity = 0.9, maxEvidence = 1)
        )
        val overflow = assertIs<MemoryRetentionNearSemanticAdmissionResult.Rejected>(
            tinyBound.evaluate(
                ledger,
                listOf(
                    evidence("candidate", 2, "anchor", 1, 0.95),
                    evidence("candidate", 2, "anchor", 1, 0.96)
                )
            )
        )
        assertEquals(MemoryRetentionNearSemanticFailure.EVIDENCE_LIMIT_EXCEEDED, overflow.failure)
    }

    private fun admission() = MemoryRetentionNearSemanticAdmission(policy)

    private fun entry(
        id: String,
        generation: Long,
        disposition: MemoryRetentionDisposition,
        retentionClass: MemoryRetentionClass = MemoryRetentionClass.EPISODIC,
        duplicateOf: String? = null
    ): MemoryRetentionLedgerEntry = MemoryRetentionLedgerEntry(
        recordId = MemoryRecordId(id),
        generation = MemoryGeneration(generation),
        retentionClass = retentionClass,
        disposition = disposition,
        action = if (disposition == MemoryRetentionDisposition.RETAINED) {
            MemoryRetentionShadowAction.KEEP
        } else {
            MemoryRetentionShadowAction.PRUNE_CANDIDATE
        },
        duplicateOf = duplicateOf?.let(::MemoryRecordId)
    )

    private fun evidence(
        leftId: String,
        leftGeneration: Long,
        rightId: String,
        rightGeneration: Long,
        similarity: Double
    ): MemoryRetentionNearSemanticEvidence = MemoryRetentionNearSemanticEvidence(
        left = MemoryRetentionSemanticReference(MemoryRecordId(leftId), MemoryGeneration(leftGeneration)),
        right = MemoryRetentionSemanticReference(MemoryRecordId(rightId), MemoryGeneration(rightGeneration)),
        similarity = similarity
    )
}
