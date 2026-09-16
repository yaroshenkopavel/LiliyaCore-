package pro.liliya.core.memory

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class MemoryRetentionShadowConsolidationContractTest {
    private val policy = MemoryRetentionPolicy(
        mapOf(
            MemoryRetentionClass.WORKING to MemoryRetentionBudget(maxRecords = 1, maxContentChars = 100),
            MemoryRetentionClass.EPISODIC to MemoryRetentionBudget(maxRecords = 2, maxContentChars = 100),
            MemoryRetentionClass.SEMANTIC to MemoryRetentionBudget(maxRecords = 1, maxContentChars = 5)
        )
    )
    private val shadow = MemoryRetentionShadowConsolidator(MemoryRetentionPlanner(policy))

    @Test
    fun repeated_simulation_produces_identical_explainable_ledger() {
        val candidates = listOf(
            candidate("working-old", 1, "Alpha", "2026-09-01T10:00:00Z", MemoryRetentionClass.WORKING),
            candidate("working-new", 2, "Beta", "2026-09-01T11:00:00Z", MemoryRetentionClass.WORKING),
            candidate("episode-new", 3, "Remember Me", "2026-09-01T12:00:00Z", MemoryRetentionClass.EPISODIC),
            candidate("episode-duplicate", 4, "  remember   me  ", "2026-09-01T11:30:00Z", MemoryRetentionClass.EPISODIC),
            candidate("semantic-too-large", 5, "abcdef", "2026-09-01T13:00:00Z", MemoryRetentionClass.SEMANTIC)
        )

        val first = shadow.simulate(candidates)
        val second = shadow.simulate(candidates.reversed())

        assertEquals(first.ledger, second.ledger)
        assertEquals(
            listOf(
                MemoryRetentionDisposition.RETAINED,
                MemoryRetentionDisposition.RECORD_BUDGET_REJECTED,
                MemoryRetentionDisposition.RETAINED,
                MemoryRetentionDisposition.DUPLICATE_SUPPRESSED,
                MemoryRetentionDisposition.CONTENT_BUDGET_REJECTED
            ),
            first.ledger.entries.map { it.disposition }
        )
        assertEquals(
            MemoryRecordId("episode-new"),
            first.ledger.entries.single { it.recordId == MemoryRecordId("episode-duplicate") }.duplicateOf
        )
    }

    @Test
    fun ledger_summaries_are_partitioned_by_retention_class() {
        val report = shadow.simulate(
            listOf(
                candidate("w1", 1, "one", "2026-09-01T10:00:00Z", MemoryRetentionClass.WORKING),
                candidate("w2", 2, "two", "2026-09-01T11:00:00Z", MemoryRetentionClass.WORKING),
                candidate("e1", 3, "same", "2026-09-01T12:00:00Z", MemoryRetentionClass.EPISODIC),
                candidate("e2", 4, "SAME", "2026-09-01T11:00:00Z", MemoryRetentionClass.EPISODIC)
            )
        )

        assertEquals(MemoryRetentionLedgerSummary(1, 0, 1, 0), report.ledger.summary(MemoryRetentionClass.WORKING))
        assertEquals(MemoryRetentionLedgerSummary(1, 1, 0, 0), report.ledger.summary(MemoryRetentionClass.EPISODIC))
        assertEquals(MemoryRetentionLedgerSummary(0, 0, 0, 0), report.ledger.summary(MemoryRetentionClass.SEMANTIC))
    }

    @Test
    fun ledger_rendering_never_contains_memory_content() {
        val secret = "PRIVATE-RETENTION-MEMORY-CONTENT"
        val report = shadow.simulate(
            listOf(candidate("secret", 1, secret, "2026-09-01T10:00:00Z", MemoryRetentionClass.WORKING))
        )

        val rendering = report.toString() + "\n" + report.ledger + "\n" + report.ledger.entries.joinToString("\n")
        assertFalse(rendering.contains(secret))
    }

    @Test
    fun ambiguous_duplicate_record_ids_are_rejected_before_planning() {
        val first = candidate("same-id", 1, "one", "2026-09-01T10:00:00Z", MemoryRetentionClass.WORKING)
        val second = candidate("same-id", 2, "two", "2026-09-01T11:00:00Z", MemoryRetentionClass.EPISODIC)

        assertFailsWith<IllegalArgumentException> {
            shadow.simulate(listOf(first, second))
        }
    }

    private fun candidate(
        id: String,
        generation: Long,
        content: String,
        createdAt: String,
        retentionClass: MemoryRetentionClass
    ): MemoryRetentionCandidate = MemoryRetentionCandidate(
        snapshot = MemoryRecordSnapshot(
            record = MemoryRecord(
                id = MemoryRecordId(id),
                sourceId = MemorySourceId("shadow-contract"),
                content = content,
                createdAt = Instant.parse(createdAt)
            ),
            generation = MemoryGeneration(generation)
        ),
        retentionClass = retentionClass
    )
}
