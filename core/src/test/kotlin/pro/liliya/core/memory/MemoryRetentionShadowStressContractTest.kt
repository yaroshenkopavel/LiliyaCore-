package pro.liliya.core.memory

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryRetentionShadowStressContractTest {
    private val baseTime = Instant.parse("2026-09-17T16:00:00Z")

    @Test
    fun large_shadow_simulation_is_bounded_complete_and_input_order_independent() {
        val budgets = mapOf(
            MemoryRetentionClass.WORKING to MemoryRetentionBudget(90, 20_000),
            MemoryRetentionClass.EPISODIC to MemoryRetentionBudget(70, 16_000),
            MemoryRetentionClass.SEMANTIC to MemoryRetentionBudget(50, 12_000)
        )
        val policy = MemoryRetentionPolicy(budgets)
        val consolidator = MemoryRetentionShadowConsolidator(MemoryRetentionPlanner(policy))
        val candidates = (0 until 1_500).map { index -> candidate(index) }

        val forward = consolidator.simulate(candidates)
        val reversed = consolidator.simulate(candidates.asReversed())

        assertEquals(forward.ledger, reversed.ledger)
        assertEquals(candidates.size, forward.ledger.entries.size)
        assertEquals(candidates.map { it.snapshot.record.id }.toSet(), forward.ledger.entries.map { it.recordId }.toSet())

        MemoryRetentionClass.entries.forEach { retentionClass ->
            val classCandidates = candidates.filter { it.retentionClass == retentionClass }
            val retainedIds = forward.ledger.entries
                .filter {
                    it.retentionClass == retentionClass &&
                        it.disposition == MemoryRetentionDisposition.RETAINED
                }
                .map { it.recordId }
                .toSet()
            val retained = classCandidates.filter { it.snapshot.record.id in retainedIds }
            val budget = budgets.getValue(retentionClass)

            assertTrue(retained.size <= budget.maxRecords)
            assertTrue(retained.sumOf { it.snapshot.record.content.length } <= budget.maxContentChars)
            assertEquals(
                classCandidates.size,
                forward.ledger.summary(retentionClass).let {
                    it.retained + it.duplicateSuppressed + it.recordBudgetRejected + it.contentBudgetRejected
                }
            )
        }
    }

    private fun candidate(index: Int): MemoryRetentionCandidate {
        val retentionClass = MemoryRetentionClass.entries[index % MemoryRetentionClass.entries.size]
        val content = "stress-content-$index-" + "x".repeat(20 + (index % 41))
        return MemoryRetentionCandidate(
            snapshot = MemoryRecordSnapshot(
                record = MemoryRecord(
                    id = MemoryRecordId("stress-${index.toString().padStart(5, '0')}"),
                    sourceId = MemorySourceId("retention-shadow-stress"),
                    content = content,
                    createdAt = baseTime.minusSeconds(index.toLong())
                ),
                generation = MemoryGeneration(index.toLong() + 1L)
            ),
            retentionClass = retentionClass
        )
    }
}
