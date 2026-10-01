package pro.liliya.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.events.CoreEvent
import pro.liliya.core.events.EventEnvelope
import pro.liliya.core.logging.LogContext

class MemoryConsolidationTriggerPlannerContractTest {
    private val context = LogContext(
        module = "core",
        component = "MemoryConsolidation",
        operation = "test"
    )

    @Test
    fun task_completion_produces_deterministic_bounded_advisory_plan() {
        val result = assertIs<MemoryConsolidationPlanResult.Planned>(
            MemoryConsolidationTriggerPlanner().plan(
                event(MemoryConsolidationTriggerType.TASK_COMPLETED)
            )
        )

        assertEquals(
            listOf(
                MemoryConsolidationWorkKind.EPISODIC_EXTRACTION_CHECK,
                MemoryConsolidationWorkKind.SEMANTIC_CLAIM_EXTRACTION_CHECK,
                MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK,
                MemoryConsolidationWorkKind.RETRIEVAL_QUALITY_BOOKKEEPING
            ),
            result.work.map { it.kind }
        )
        assertEquals(MemoryConsolidationPlanStatus.PLANNED, result.audit.status)
        assertTrue(result.audit.advisoryOnly)
        assertEquals(4, result.audit.emittedWorkCount)
    }

    @Test
    fun every_non_governed_trigger_type_produces_explicit_bounded_work() {
        MemoryConsolidationTriggerType.entries
            .filter { it != MemoryConsolidationTriggerType.GOVERNED_MAINTENANCE_REQUEST }
            .forEach { type ->
                val result = assertIs<MemoryConsolidationPlanResult.Planned>(
                    MemoryConsolidationTriggerPlanner().plan(event(type))
                )
                assertTrue(result.work.isNotEmpty(), type.name)
                assertTrue(result.work.size <= MemoryConsolidationPlannerPolicy.MAX_WORK_ITEMS)
                assertEquals(type, result.audit.triggerType)
            }
    }

    @Test
    fun semantic_conflict_never_promotes_truth_and_only_schedules_review_work() {
        val result = assertIs<MemoryConsolidationPlanResult.Planned>(
            MemoryConsolidationTriggerPlanner().plan(
                event(MemoryConsolidationTriggerType.SEMANTIC_CONFLICT_DETECTED)
            )
        )

        assertEquals(
            setOf(
                MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK,
                MemoryConsolidationWorkKind.DUPLICATE_CONFLICT_REVIEW,
                MemoryConsolidationWorkKind.TEMPORAL_SUPERSESSION_REVIEW,
                MemoryConsolidationWorkKind.RETRIEVAL_QUALITY_BOOKKEEPING
            ),
            result.work.map { it.kind }.toSet()
        )
        assertTrue(result.audit.advisoryOnly)
    }

    @Test
    fun governed_request_deduplicates_and_preserves_stable_enum_order() {
        val trigger = MemoryConsolidationTriggerEvent(
            triggerId = "governed-1",
            triggerType = MemoryConsolidationTriggerType.GOVERNED_MAINTENANCE_REQUEST,
            subjectReference = "semantic:claim-a:v1",
            requestedWork = listOf(
                MemoryConsolidationWorkKind.GRAPH_PROJECTION_MAINTENANCE,
                MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK,
                MemoryConsolidationWorkKind.GRAPH_PROJECTION_MAINTENANCE
            ),
            context = context
        )

        val result = assertIs<MemoryConsolidationPlanResult.Planned>(
            MemoryConsolidationTriggerPlanner().plan(trigger)
        )

        assertEquals(
            listOf(
                MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK,
                MemoryConsolidationWorkKind.GRAPH_PROJECTION_MAINTENANCE
            ),
            result.work.map { it.kind }
        )
        assertEquals(1, result.audit.duplicateSuppressed)
        assertEquals(3, result.audit.requestedWorkCount)
        assertEquals("semantic:claim-a:v1", result.work.single { it.kind == MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK }.subjectReference)
    }

    @Test
    fun policy_hard_limit_is_32_and_over_limit_is_rejected() {
        assertEquals(32, MemoryConsolidationPlannerPolicy.MAX_WORK_ITEMS)
        assertFailsWith<IllegalArgumentException> {
            MemoryConsolidationPlannerPolicy(maxWorkItems = 33)
        }
    }

    @Test
    fun planner_exception_fails_closed_without_plan() {
        val planner = MemoryConsolidationTriggerPlanner.testing(
            rules = MemoryConsolidationRuleSource { throw IllegalStateException("boom") }
        )

        val result = assertIs<MemoryConsolidationPlanResult.Failed>(
            planner.plan(event(MemoryConsolidationTriggerType.TASK_COMPLETED))
        )

        assertEquals(MemoryConsolidationPlanStatus.FAILED, result.audit.status)
        assertEquals(0, result.audit.emittedWorkCount)
    }

    @Test
    fun event_listener_ignores_unrelated_events_and_only_delivers_planned_work() {
        val delivered = mutableListOf<MemoryConsolidationPlanResult.Planned>()
        val listener = MemoryConsolidationEventListener(
            planner = MemoryConsolidationTriggerPlanner(),
            sink = MemoryConsolidationPlanSink { delivered += it }
        )
        val unrelated = object : CoreEvent {
            override val type: String = "test.unrelated"
            override val context: LogContext = this@MemoryConsolidationTriggerPlannerContractTest.context
            override val metadata: Map<String, String> = emptyMap()
        }
        listener.onEvent(
            EventEnvelope(
                sequence = 1,
                timestampMillis = 1,
                event = unrelated,
                metadata = emptyMap()
            )
        )
        assertTrue(delivered.isEmpty())

        val trigger = event(MemoryConsolidationTriggerType.RETRIEVAL_INDEX_MAINTENANCE_NEEDED)
        listener.onEvent(
            EventEnvelope(
                sequence = 2,
                timestampMillis = 2,
                event = trigger,
                metadata = trigger.metadata
            )
        )

        assertEquals(1, delivered.size)
        assertEquals(
            listOf(
                MemoryConsolidationWorkKind.LEXICAL_VECTOR_INDEX_MAINTENANCE,
                MemoryConsolidationWorkKind.RETRIEVAL_QUALITY_BOOKKEEPING
            ),
            delivered.single().work.map { it.kind }
        )
    }

    @Test
    fun governed_request_payload_over_128_is_rejected_at_boundary() {
        assertFailsWith<IllegalArgumentException> {
            MemoryConsolidationTriggerEvent(
                triggerId = "too-many",
                triggerType = MemoryConsolidationTriggerType.GOVERNED_MAINTENANCE_REQUEST,
                requestedWork = List(129) {
                    MemoryConsolidationWorkKind.RETRIEVAL_QUALITY_BOOKKEEPING
                },
                context = context
            )
        }
    }

    @Test
    fun invalid_governed_request_without_work_is_rejected_at_boundary() {
        assertFailsWith<IllegalArgumentException> {
            MemoryConsolidationTriggerEvent(
                triggerId = "empty-governed",
                triggerType = MemoryConsolidationTriggerType.GOVERNED_MAINTENANCE_REQUEST,
                requestedWork = emptyList(),
                context = context
            )
        }
    }

    private fun event(type: MemoryConsolidationTriggerType) =
        MemoryConsolidationTriggerEvent(
            triggerId = "trigger-" + type.name.lowercase(),
            triggerType = type,
            subjectReference = "test-subject",
            context = context
        )
}