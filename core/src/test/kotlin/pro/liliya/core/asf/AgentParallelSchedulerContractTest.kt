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
    fun fanout_is_rejected_before_launch_when_wave_ceiling_exceeds_aggregate_budget() {
        val constrained = aggregate.copy(
            maxInferenceUnits = childBudget.maxInferenceUnits
        )

        val rejected = assertIs<AgentParallelScheduleResult.Rejected>(
            AgentParallelScheduler.schedule(
                plan(listOf("a", "b")),
                constrained
            )
        )

        assertEquals(
            AgentParallelScheduleRejection.WAVE_AGGREGATE_BUDGET_EXCEEDED,
            rejected.reason
        )
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
