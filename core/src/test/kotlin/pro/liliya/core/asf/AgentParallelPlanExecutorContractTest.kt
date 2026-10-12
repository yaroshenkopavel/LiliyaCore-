package pro.liliya.core.asf

import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentParallelPlanExecutorContractTest {
    private val scope = AgentCognitiveScope.create(listOf("analysis"))
    private val blueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "parallel-plan",
        "bounded-plan-execution",
        scope
    )
    private val rootBudget = AgentWorkBudget(5_000, 5_000, 16_000, 4, 2, 2)
    private val childBudget = AgentWorkBudget(2_000, 2_000, 8_000, 2, 1, 0)
    private val aggregate = AgentAggregateBudget(20_000, 20_000, 64_000, 16, 8, 4)

    @Test
    fun root_wave_commits_before_sibling_wave_and_parent_artifact_is_forwarded() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val seenParents = mutableMapOf<AgentCoordinatorStepId, String?>()
            val result = AgentParallelPlanExecutor(
                waveExecutor = AgentParallelWaveExecutor(pool),
                taskFactory = AgentParallelPlanTaskFactory { step, parentArtifact ->
                    seenParents[step.id] = parentArtifact
                    AgentParallelWaveTask {
                        AgentParallelWaveTaskOutcome.Completed(
                            stepId = step.id,
                            artifactReference = "artifact:" + step.id.value
                        )
                    }
                }
            ).execute(
                plan = plan(includeParentArtifact = true),
                aggregateBudget = aggregate,
                timeoutPerWaveMillis = 2_000
            )

            assertEquals(AgentParallelPlanExecutionState.COMPLETED, result.state)
            assertEquals(2, result.completedWaves)
            assertEquals(3, result.outcomes.size)
            assertNull(seenParents.getValue(AgentCoordinatorStepId("root")))
            assertEquals(
                "artifact:root",
                seenParents.getValue(AgentCoordinatorStepId("child-a"))
            )
            assertEquals(
                "artifact:root",
                seenParents.getValue(AgentCoordinatorStepId("child-b"))
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun missing_committed_parent_artifact_stops_before_child_wave() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            var created = 0
            val result = AgentParallelPlanExecutor(
                waveExecutor = AgentParallelWaveExecutor(pool),
                taskFactory = AgentParallelPlanTaskFactory { step, _ ->
                    created += 1
                    AgentParallelWaveTask {
                        AgentParallelWaveTaskOutcome.Completed(
                            stepId = step.id,
                            artifactReference = if (step.id.value == "root") null else "unexpected"
                        )
                    }
                }
            ).execute(
                plan = plan(includeParentArtifact = true),
                aggregateBudget = aggregate,
                timeoutPerWaveMillis = 2_000
            )

            assertEquals(AgentParallelPlanExecutionState.PARTIAL, result.state)
            assertEquals(1, result.completedWaves)
            assertEquals(1, result.outcomes.size)
            assertEquals(1, created)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun partial_wave_prevents_later_dependency_wave_from_starting() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            var created = 0
            val result = AgentParallelPlanExecutor(
                waveExecutor = AgentParallelWaveExecutor(pool),
                taskFactory = AgentParallelPlanTaskFactory { step, _ ->
                    created += 1
                    AgentParallelWaveTask {
                        if (step.id.value == "child-a") {
                            AgentParallelWaveTaskOutcome.Failed(step.id, "bounded failure")
                        } else {
                            AgentParallelWaveTaskOutcome.Completed(
                                step.id,
                                "artifact:" + step.id.value
                            )
                        }
                    }
                }
            ).execute(
                plan = plan(includeParentArtifact = false),
                aggregateBudget = aggregate,
                timeoutPerWaveMillis = 2_000
            )

            assertEquals(AgentParallelPlanExecutionState.PARTIAL, result.state)
            assertEquals(1, result.completedWaves)
            assertEquals(3, created)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun plan(
        includeParentArtifact: Boolean
    ): AgentCoordinatorPlan {
        val root = step("root", null, rootBudget, false)
        val a = step("child-a", "root", childBudget, includeParentArtifact)
        val b = step("child-b", "root", childBudget, includeParentArtifact)
        return AgentCoordinatorPlan(
            rootTaskId = AgentRootTaskId("parallel-plan-root"),
            steps = listOf(root, a, b)
        )
    }

    private fun step(
        id: String,
        parent: String?,
        budget: AgentWorkBudget,
        includeParentArtifact: Boolean
    ): AgentCoordinatorStep =
        AgentCoordinatorStep.create(
            id = AgentCoordinatorStepId(id),
            parentStepId = parent?.let(::AgentCoordinatorStepId),
            blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
            cognitiveScope = scope,
            budget = budget,
            inputReferences = listOf("evidence:$id"),
            includeParentArtifact = includeParentArtifact
        )
}
