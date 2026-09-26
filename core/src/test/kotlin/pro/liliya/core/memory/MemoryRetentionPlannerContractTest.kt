package pro.liliya.core.memory

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoryRetentionPlannerContractTest {
    private val roomy = MemoryRetentionBudget(maxRecords = 10, maxContentChars = 1_000)

    @Test
    fun planning_is_deterministic_and_keeps_newest_records_within_budget() {
        val planner = planner(
            episodic = MemoryRetentionBudget(maxRecords = 2, maxContentChars = 1_000)
        )
        val old = candidate("old", "old memory", "2026-09-01T10:00:00Z")
        val middle = candidate("middle", "middle memory", "2026-09-02T10:00:00Z")
        val newest = candidate("newest", "newest memory", "2026-09-03T10:00:00Z")

        val first = planner.plan(listOf(old, newest, middle))
        val second = planner.plan(listOf(middle, old, newest))

        assertEquals(listOf("newest", "middle"), first.retained.map { it.snapshot.record.id.value })
        assertEquals(first.retained, second.retained)
        assertEquals(first.decisions, second.decisions)
        assertEquals(
            MemoryRetentionDisposition.RECORD_BUDGET_REJECTED,
            first.decisions.single { it.recordId == MemoryRecordId("old") }.disposition
        )
    }

    @Test
    fun canonical_duplicate_is_suppressed_before_budget_consumption() {
        val planner = planner(
            episodic = MemoryRetentionBudget(maxRecords = 2, maxContentChars = 1_000)
        )
        val newest = candidate("new", "  KEY   is ON the Table  ", "2026-09-03T10:00:00Z")
        val duplicate = candidate("duplicate", "key is on the table", "2026-09-02T10:00:00Z")
        val distinct = candidate("distinct", "bus twelve goes to station", "2026-09-01T10:00:00Z")

        val plan = planner.plan(listOf(distinct, duplicate, newest))

        assertEquals(listOf("new", "distinct"), plan.retained.map { it.snapshot.record.id.value })
        val duplicateDecision = plan.decisions.single { it.recordId == MemoryRecordId("duplicate") }
        assertEquals(MemoryRetentionDisposition.DUPLICATE_SUPPRESSED, duplicateDecision.disposition)
        assertEquals(MemoryRecordId("new"), duplicateDecision.duplicateOf)
    }

    @Test
    fun content_budget_is_bounded_without_partial_retention() {
        val planner = planner(
            episodic = MemoryRetentionBudget(maxRecords = 10, maxContentChars = 8)
        )
        val newest = candidate("new", "12345", "2026-09-03T10:00:00Z")
        val older = candidate("older", "6789", "2026-09-02T10:00:00Z")

        val plan = planner.plan(listOf(older, newest))

        assertEquals(listOf("new"), plan.retained.map { it.snapshot.record.id.value })
        val rejected = plan.decisions.single { it.recordId == MemoryRecordId("older") }
        assertEquals(MemoryRetentionDisposition.CONTENT_BUDGET_REJECTED, rejected.disposition)
        assertNull(rejected.duplicateOf)
    }

    @Test
    fun budgets_are_independent_between_retention_classes() {
        val planner = MemoryRetentionPlanner(
            MemoryRetentionPolicy(
                mapOf(
                    MemoryRetentionClass.WORKING to MemoryRetentionBudget(1, 1_000),
                    MemoryRetentionClass.EPISODIC to MemoryRetentionBudget(1, 1_000),
                    MemoryRetentionClass.SEMANTIC to MemoryRetentionBudget(1, 1_000)
                )
            )
        )
        val working = candidate("working", "same content", "2026-09-03T10:00:00Z", MemoryRetentionClass.WORKING)
        val episodic = candidate("episodic", "same content", "2026-09-03T10:00:00Z", MemoryRetentionClass.EPISODIC)
        val semantic = candidate("semantic", "same content", "2026-09-03T10:00:00Z", MemoryRetentionClass.SEMANTIC)

        val plan = planner.plan(listOf(semantic, working, episodic))

        assertEquals(
            listOf("working", "episodic", "semantic"),
            plan.retained.map { it.snapshot.record.id.value }
        )
        assertEquals(3, plan.decisions.count { it.disposition == MemoryRetentionDisposition.RETAINED })
    }

    private fun planner(
        working: MemoryRetentionBudget = roomy,
        episodic: MemoryRetentionBudget = roomy,
        semantic: MemoryRetentionBudget = roomy
    ) = MemoryRetentionPlanner(
        MemoryRetentionPolicy(
            mapOf(
                MemoryRetentionClass.WORKING to working,
                MemoryRetentionClass.EPISODIC to episodic,
                MemoryRetentionClass.SEMANTIC to semantic
            )
        )
    )

    private fun candidate(
        id: String,
        content: String,
        createdAt: String,
        retentionClass: MemoryRetentionClass = MemoryRetentionClass.EPISODIC
    ) = MemoryRetentionCandidate(
        snapshot = MemoryRecordSnapshot(
            record = MemoryRecord(
                id = MemoryRecordId(id),
                sourceId = MemorySourceId("retention-contract"),
                content = content,
                createdAt = Instant.parse(createdAt)
            ),
            generation = MemoryGeneration(id.length.toLong() + 1L)
        ),
        retentionClass = retentionClass
    )
}
