package pro.liliya.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.logging.LogContext

class MemoryConsolidationExecutionCoordinatorContractTest {
    private val context = LogContext(
        module = "core",
        component = "MemoryConsolidationExecution",
        operation = "test"
    )

    @Test
    fun executes_exact_plan_order_once_without_parallel_or_retry_semantics() {
        val calls = mutableListOf<MemoryConsolidationWorkKind>()
        val plan = planned(
            MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK,
            MemoryConsolidationWorkKind.GRAPH_PROJECTION_MAINTENANCE,
            MemoryConsolidationWorkKind.RETRIEVAL_QUALITY_BOOKKEEPING
        )
        val coordinator = coordinatorFor(plan.work.map { it.kind }) { item ->
            calls += item.kind
            MemoryConsolidationProcessorResult.Completed(item.kind.name)
        }

        val result = assertIs<MemoryConsolidationExecutionResult.Completed>(
            coordinator.execute(plan)
        )

        assertEquals(plan.work.map { it.kind }, calls)
        assertEquals(3, result.audit.executedCount)
        assertEquals(3, result.audit.completedCount)
        assertEquals(0, result.audit.skippedCount)
        assertEquals(MemoryConsolidationExecutionStatus.COMPLETED, result.audit.status)
        assertTrue(result.audit.advisoryOrchestrationOnly)
    }

    @Test
    fun missing_processor_rejects_before_first_processor_call() {
        var calls = 0
        val plan = planned(
            MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK,
            MemoryConsolidationWorkKind.GRAPH_PROJECTION_MAINTENANCE
        )
        val registry = MemoryConsolidationProcessorRegistry.of(
            listOf(
                MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK to
                    MemoryConsolidationWorkProcessor {
                        calls += 1
                        MemoryConsolidationProcessorResult.Completed()
                    }
            )
        )

        val result = assertIs<MemoryConsolidationExecutionResult.Rejected>(
            MemoryConsolidationExecutionCoordinator(registry).execute(plan)
        )

        assertTrue(result.reason.contains("missing"))
        assertEquals(0, calls)
        assertEquals(0, result.audit.executedCount)
    }

    @Test
    fun first_processor_failure_returns_failed_and_stops_following_work() {
        val calls = mutableListOf<MemoryConsolidationWorkKind>()
        val plan = planned(
            MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK,
            MemoryConsolidationWorkKind.GRAPH_PROJECTION_MAINTENANCE
        )
        val registry = MemoryConsolidationProcessorRegistry.of(
            listOf(
                MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK to
                    MemoryConsolidationWorkProcessor {
                        calls += it.kind
                        MemoryConsolidationProcessorResult.Failed("blocked")
                    },
                MemoryConsolidationWorkKind.GRAPH_PROJECTION_MAINTENANCE to
                    MemoryConsolidationWorkProcessor {
                        calls += it.kind
                        MemoryConsolidationProcessorResult.Completed()
                    }
            )
        )

        val result = assertIs<MemoryConsolidationExecutionResult.Failed>(
            MemoryConsolidationExecutionCoordinator(registry).execute(plan)
        )

        assertEquals(listOf(MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK), calls)
        assertEquals(1, result.audit.executedCount)
        assertEquals(0, result.audit.completedCount)
        assertEquals(
            MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK,
            result.audit.failedKind
        )
    }

    @Test
    fun later_failure_is_explicit_partial_failure_with_exact_prior_outcomes() {
        val calls = mutableListOf<MemoryConsolidationWorkKind>()
        val plan = planned(
            MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK,
            MemoryConsolidationWorkKind.GRAPH_PROJECTION_MAINTENANCE,
            MemoryConsolidationWorkKind.RETRIEVAL_QUALITY_BOOKKEEPING
        )
        val registry = MemoryConsolidationProcessorRegistry.of(
            plan.work.map { item ->
                item.kind to MemoryConsolidationWorkProcessor {
                    calls += it.kind
                    when (it.kind) {
                        MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK ->
                            MemoryConsolidationProcessorResult.Completed("linked")
                        MemoryConsolidationWorkKind.GRAPH_PROJECTION_MAINTENANCE ->
                            MemoryConsolidationProcessorResult.Failed("graph unavailable")
                        else -> MemoryConsolidationProcessorResult.Completed()
                    }
                }
            }
        )

        val result = assertIs<MemoryConsolidationExecutionResult.PartialFailure>(
            MemoryConsolidationExecutionCoordinator(registry).execute(plan)
        )

        assertEquals(
            listOf(
                MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK,
                MemoryConsolidationWorkKind.GRAPH_PROJECTION_MAINTENANCE
            ),
            calls
        )
        assertEquals(1, result.completedOutcomes.size)
        assertEquals(
            MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK,
            result.completedOutcomes.single().item.kind
        )
        assertEquals(2, result.audit.executedCount)
        assertEquals(1, result.audit.completedCount)
        assertEquals(MemoryConsolidationExecutionStatus.PARTIAL_FAILURE, result.audit.status)
    }

    @Test
    fun processor_exception_is_caught_and_reported_as_failure() {
        val plan = planned(MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK)
        val registry = MemoryConsolidationProcessorRegistry.of(
            listOf(
                MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK to
                    MemoryConsolidationWorkProcessor {
                        throw IllegalStateException("boom")
                    }
            )
        )

        val result = assertIs<MemoryConsolidationExecutionResult.Failed>(
            MemoryConsolidationExecutionCoordinator(registry).execute(plan)
        )

        assertTrue(result.reason.contains("threw"))
        assertIs<IllegalStateException>(result.throwable)
        assertEquals(1, result.audit.executedCount)
    }

    @Test
    fun skipped_processor_is_explicit_and_does_not_count_as_completed() {
        val plan = planned(MemoryConsolidationWorkKind.RETRIEVAL_QUALITY_BOOKKEEPING)
        val registry = MemoryConsolidationProcessorRegistry.of(
            listOf(
                MemoryConsolidationWorkKind.RETRIEVAL_QUALITY_BOOKKEEPING to
                    MemoryConsolidationWorkProcessor {
                        MemoryConsolidationProcessorResult.Skipped("not needed")
                    }
            )
        )

        val result = assertIs<MemoryConsolidationExecutionResult.Completed>(
            MemoryConsolidationExecutionCoordinator(registry).execute(plan)
        )

        assertEquals(0, result.audit.completedCount)
        assertEquals(1, result.audit.skippedCount)
        assertEquals(
            MemoryConsolidationProcessorOutcomeStatus.SKIPPED,
            result.outcomes.single().status
        )
    }

    @Test
    fun plan_over_hard_32_item_bound_rejects_before_first_processor_call() {
        var calls = 0
        val kinds = MemoryConsolidationWorkKind.entries
        val oversized = List(33) { index ->
            item(kinds[index % kinds.size], triggerId = "trigger-over-bound")
        }
        val plan = MemoryConsolidationPlanResult.Planned(
            work = oversized,
            audit = plannerAudit(oversized.size)
        )
        val coordinator = coordinatorFor(kinds) {
            calls += 1
            MemoryConsolidationProcessorResult.Completed()
        }

        val result = assertIs<MemoryConsolidationExecutionResult.Rejected>(
            coordinator.execute(plan)
        )

        assertTrue(result.reason.contains("hard work bound"))
        assertEquals(0, calls)
        assertEquals(33, result.audit.plannedCount)
        assertEquals(0, result.audit.executedCount)
    }

    @Test
    fun duplicate_processor_registration_is_rejected() {
        val processor = MemoryConsolidationWorkProcessor {
            MemoryConsolidationProcessorResult.Completed()
        }
        assertFailsWith<IllegalArgumentException> {
            MemoryConsolidationProcessorRegistry.of(
                listOf(
                    MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK to processor,
                    MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK to processor
                )
            )
        }
    }

    @Test
    fun mixed_trigger_plan_is_rejected_before_execution() {
        var calls = 0
        val first = item(
            MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK,
            triggerId = "trigger-a"
        )
        val second = item(
            MemoryConsolidationWorkKind.GRAPH_PROJECTION_MAINTENANCE,
            triggerId = "trigger-b"
        )
        val plan = MemoryConsolidationPlanResult.Planned(
            work = listOf(first, second),
            audit = plannerAudit(2)
        )
        val coordinator = coordinatorFor(plan.work.map { it.kind }) {
            calls += 1
            MemoryConsolidationProcessorResult.Completed()
        }

        val result = assertIs<MemoryConsolidationExecutionResult.Rejected>(
            coordinator.execute(plan)
        )

        assertTrue(result.reason.contains("trigger ids"))
        assertEquals(0, calls)
    }

    @Test
    fun duplicate_work_kind_plan_is_rejected_before_execution() {
        var calls = 0
        val duplicate = item(MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK)
        val plan = MemoryConsolidationPlanResult.Planned(
            work = listOf(duplicate, duplicate),
            audit = plannerAudit(2)
        )
        val coordinator = coordinatorFor(
            listOf(MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK)
        ) {
            calls += 1
            MemoryConsolidationProcessorResult.Completed()
        }

        val result = assertIs<MemoryConsolidationExecutionResult.Rejected>(
            coordinator.execute(plan)
        )

        assertTrue(result.reason.contains("duplicate"))
        assertEquals(0, calls)
    }

    private fun coordinatorFor(
        kinds: List<MemoryConsolidationWorkKind>,
        processor: (MemoryConsolidationWorkItem) -> MemoryConsolidationProcessorResult
    ): MemoryConsolidationExecutionCoordinator =
        MemoryConsolidationExecutionCoordinator(
            MemoryConsolidationProcessorRegistry.of(
                kinds.distinct().map { kind ->
                    kind to MemoryConsolidationWorkProcessor { item -> processor(item) }
                }
            )
        )

    private fun planned(
        vararg kinds: MemoryConsolidationWorkKind
    ): MemoryConsolidationPlanResult.Planned =
        MemoryConsolidationPlanResult.Planned(
            work = kinds.map { item(it) },
            audit = plannerAudit(kinds.size)
        )

    private fun item(
        kind: MemoryConsolidationWorkKind,
        triggerId: String = "trigger-1"
    ) = MemoryConsolidationWorkItem(
        triggerId = triggerId,
        subjectReference = "subject-1",
        kind = kind
    )

    private fun plannerAudit(count: Int) = MemoryConsolidationPlanAudit(
        triggerType = MemoryConsolidationTriggerType.GOVERNED_MAINTENANCE_REQUEST,
        requestedWorkCount = count,
        emittedWorkCount = count,
        duplicateSuppressed = 0,
        truncated = false,
        status = MemoryConsolidationPlanStatus.PLANNED
    )
}