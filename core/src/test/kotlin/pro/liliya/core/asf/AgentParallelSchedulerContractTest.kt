package pro.liliya.core.asf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentParallelSchedulerContractTest {
    private val scope = AgentCognitiveScope.create(listOf("analysis"))
    private val blueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "parallel-worker",
        "bounded-analysis",
        scope
    )
    private val rootBudget = AgentWorkBudget(
        maxWallClockMillis = 10_000,
        maxInferenceUnits = 10_000,
        maxContextBytes = 32_000,
        maxRetrievalItems = 8,
        maxArtifacts = 4,
        maxDescendants = 4
    )
    private val childBudget = AgentWorkBudget(
        maxWallClockMillis = 2_000,
        maxInferenceUnits = 2_000,
        maxContextBytes = 8_000,
        maxRetrievalItems = 2,
        maxArtifacts = 1,
        maxDescendants = 0
    )
    private val aggregate = AgentAggregateBudget(
        maxWallClockMillis = 20_000,
        maxInferenceUnits = 20_000,
        maxContextBytes = 64_000,
        maxRetrievalItems = 16,
        maxArtifacts = 8,
        maxAgents = 4
    )

    @Test
    fun root_then_siblings_are_scheduled_as_two_dependency_waves() {
        val ready = assertIs<AgentParallelScheduleResult.Ready>(
            AgentParallelScheduler.schedule(
                plan = plan(
                    childIds = listOf("verify-b", "verify-a")
                ),
                aggregateBudget = aggregate
            )
        )

        assertEquals(2, ready.waves.size)
        assertEquals(
            listOf(AgentCoordinatorStepId("root")),
            ready.waves[0].stepIds
        )
        assertEquals(
            listOf(
                AgentCoordinatorStepId("verify-a"),
                AgentCoordinatorStepId("verify-b")
            ),
            ready.waves[1].stepIds
        )
        assertEquals(1, ready.waves[0].reservation.maxAgents)
        assertEquals(2, ready.waves[1].reservation.maxAgents)
        assertEquals(
            childBudget.maxInferenceUnits * 2,
            ready.waves[1].reservation.maxInferenceUnits
        )
    }

    @Test
    fun sibling_wave_order_is_deterministic_for_equivalent_valid_plans() {
        val first = assertIs<AgentParallelScheduleResult.Ready>(
            AgentParallelScheduler.schedule(
                plan(listOf("b", "a")),
                aggregate
            )
        )
        val second = assertIs<AgentParallelScheduleResult.Ready>(
            AgentParallelScheduler.schedule(
                plan(listOf("a", "b")),
                aggregate
            )
        )

        assertEquals(first.waves, second.waves)
    }

    @Test
    fun whole_plan_is_rejected_before_launch_when_cumulative_compute_ceiling_exceeds_aggregate_budget() {
        val expensiveChild = childBudget.copy(maxInferenceUnits = 4_000)
        val constrained = aggregate.copy(maxInferenceUnits = 10_000)
        val root = step("root", null, rootBudget)
        val plan = AgentCoordinatorPlan(
            rootTaskId = AgentRootTaskId("parallel-root"),
            steps = listOf(root) + listOf("a", "b", "c").map { id ->
                step(id, "root", expensiveChild)
            }
        )

        val rejected = assertIs<AgentParallelScheduleResult.Rejected>(
            AgentParallelScheduler.schedule(
                plan,
                constrained
            )
        )

        assertEquals(
            AgentParallelScheduleRejection.PLAN_AGGREGATE_BUDGET_EXCEEDED,
            rejected.reason
        )
    }

    @Test
    fun whole_plan_is_rejected_when_cumulative_agent_population_exceeds_aggregate_cap() {
        val constrained = aggregate.copy(maxAgents = 2)

        val rejected = assertIs<AgentParallelScheduleResult.Rejected>(
            AgentParallelScheduler.schedule(
                plan = plan(listOf("a", "b")),
                aggregateBudget = constrained
            )
        )

        assertEquals(
            AgentParallelScheduleRejection.PLAN_AGGREGATE_BUDGET_EXCEEDED,
            rejected.reason
        )
    }

    @Test
    fun reject_siblings_exceeding_root_descendant_cap_even_when_aggregate_fits() {
        val root = step("root", null, rootBudget.copy(maxDescendants = 1))
        val children = listOf("a", "b").map { step(it, "root", childBudget) }
        val result = assertIs<AgentParallelScheduleResult.Rejected>(
            AgentParallelScheduler.schedule(
                AgentCoordinatorPlan(AgentRootTaskId("parallel-root"), listOf(root) + children),
                aggregate
            )
        )
        assertEquals(AgentParallelScheduleRejection.PARENT_DESCENDANT_BUDGET_EXCEEDED, result.reason)
    }

    @Test
    fun reject_transitive_descendant_cap_violation_even_when_direct_children_fit() {
        val root = step("root", null, rootBudget.copy(maxDescendants = 1))
        val child = step("a", "root", childBudget.copy(maxDescendants = 1))
        val grandchild = step("b", "a", childBudget)
        val result = assertIs<AgentParallelScheduleResult.Rejected>(
            AgentParallelScheduler.schedule(
                AgentCoordinatorPlan(AgentRootTaskId("parallel-root"), listOf(root, child, grandchild)),
                aggregate
            )
        )
        assertEquals(AgentParallelScheduleRejection.PARENT_DESCENDANT_BUDGET_EXCEEDED, result.reason)
    }

    @Test
    fun nested_descendant_tree_at_exact_cap_remains_schedulable() {
        val root = step("root", null, rootBudget.copy(maxDescendants = 3))
        val childA = step("a", "root", childBudget.copy(maxDescendants = 1))
        val childB = step("b", "root", childBudget)
        val grandchild = step("c", "a", childBudget)
        val ready = assertIs<AgentParallelScheduleResult.Ready>(
            AgentParallelScheduler.schedule(
                AgentCoordinatorPlan(
                    AgentRootTaskId("parallel-root"),
                    listOf(root, childA, childB, grandchild)
                ),
                aggregate
            )
        )
        assertEquals(3, ready.waves.size)
        assertEquals(listOf(AgentCoordinatorStepId("c")), ready.waves[2].stepIds)
    }

    @Test
    fun scheduler_contract_contains_no_authority_execution_or_secret_fields() {
        val forbidden = listOf(
            "authority",
            "permission",
            "credential",
            "secret",
            "token",
            "license",
            "principal"
        )

        listOf(
            AgentParallelWaveReservation::class.java,
            AgentParallelWave::class.java,
            AgentParallelScheduleResult.Ready::class.java
        ).forEach { type ->
            val names = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { marker ->
                assertTrue(
                    names.none { marker in it },
                    type.simpleName + " contains forbidden field: " + marker
                )
            }
        }
    }

    private fun plan(
        childIds: List<String>
    ): AgentCoordinatorPlan {
        val root = step("root", null, rootBudget)
        val children = childIds.map { id ->
            step(id, "root", childBudget)
        }
        return AgentCoordinatorPlan(
            rootTaskId = AgentRootTaskId("parallel-root"),
            steps = listOf(root) + children
        )
    }

    private fun step(
        id: String,
        parent: String?,
        budget: AgentWorkBudget
    ): AgentCoordinatorStep =
        AgentCoordinatorStep.create(
            id = AgentCoordinatorStepId(id),
            parentStepId = parent?.let(::AgentCoordinatorStepId),
            blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
            cognitiveScope = scope,
            budget = budget,
            inputReferences = listOf("evidence:$id")
        )
}
