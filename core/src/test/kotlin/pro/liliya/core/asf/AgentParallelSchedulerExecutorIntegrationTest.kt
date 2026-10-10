package pro.liliya.core.asf

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Scheduler-to-executor contract only. Admission, authority and runtime commits
 * remain the responsibility of AgentCoordinator; this is not production integration.
 */
class AgentParallelSchedulerExecutorIntegrationTest {
    private val scope = AgentCognitiveScope.create(listOf("analysis"))
    private val blueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1), "parallel-integration-worker", "bounded-analysis", scope
    )
    private val rootBudget = AgentWorkBudget(2_000, 1_000, 4_000, 1, 1, 2)
    private val childBudget = AgentWorkBudget(2_000, 1_000, 4_000, 1, 1, 0)

    @Test
    fun dependency_root_runs_before_concurrent_siblings_and_results_are_canonical() {
        val root = AgentCoordinatorStepId("root")
        val first = AgentCoordinatorStepId("child-a")
        val second = AgentCoordinatorStepId("child-b")
        fun step(id: AgentCoordinatorStepId, parent: AgentCoordinatorStepId?, budget: AgentWorkBudget) =
            AgentCoordinatorStep.create(
                id = id,
                parentStepId = parent,
                blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
                cognitiveScope = scope,
                budget = budget,
                inputReferences = listOf("evidence:" + id.value)
            )

        val plan = AgentCoordinatorPlan(
            rootTaskId = AgentRootTaskId("parallel-integration-root"),
            steps = listOf(
                step(root, null, rootBudget),
                step(second, root, childBudget),
                step(first, root, childBudget)
            )
        )
        val aggregate = AgentAggregateBudget(
            maxWallClockMillis = 8_000,
            maxInferenceUnits = 5_000,
            maxContextBytes = 16_000,
            maxRetrievalItems = 4,
            maxArtifacts = 4,
            maxAgents = 3
        )
        val schedule = assertIs<AgentParallelScheduleResult.Ready>(
            AgentParallelScheduler.schedule(plan, aggregate)
        )
        assertEquals(2, schedule.waves.size)
        assertEquals(listOf(root), schedule.waves[0].stepIds)
        assertEquals(listOf(first, second), schedule.waves[1].stepIds)

        val pool = Executors.newFixedThreadPool(2)
        try {
            val executor = AgentParallelWaveExecutor(pool)
            val rootCompleted = CountDownLatch(1)
            val siblingStarts = CountDownLatch(2)
            val releaseSiblings = CountDownLatch(1)
            val releaser = Thread {
                if (siblingStarts.await(2, TimeUnit.SECONDS)) releaseSiblings.countDown()
            }
            releaser.start()
            val rootResult = executor.execute(
                wave = schedule.waves[0],
                tasks = mapOf(root to AgentParallelWaveTask {
                    rootCompleted.countDown()
                    AgentParallelWaveTaskOutcome.Completed(root, "root-artifact")
                }),
                timeoutMillis = 2_000
            )
            assertEquals(AgentParallelWaveExecutionState.COMPLETED, rootResult.state)
            assertTrue(rootCompleted.await(1, TimeUnit.SECONDS))

            val siblingResult = executor.execute(
                wave = schedule.waves[1],
                tasks = mapOf(
                    first to AgentParallelWaveTask {
                        assertTrue(rootCompleted.count == 0L)
                        siblingStarts.countDown()
                        assertTrue(releaseSiblings.await(2, TimeUnit.SECONDS))
                        AgentParallelWaveTaskOutcome.Completed(first)
                    },
                    second to AgentParallelWaveTask {
                        assertTrue(rootCompleted.count == 0L)
                        siblingStarts.countDown()
                        assertTrue(releaseSiblings.await(2, TimeUnit.SECONDS))
                        AgentParallelWaveTaskOutcome.Completed(second)
                    }
                ),
                timeoutMillis = 2_000
            )
            releaser.join(1_000)
            assertEquals(AgentParallelWaveExecutionState.COMPLETED, siblingResult.state)
            assertEquals(
                listOf(first, second),
                siblingResult.outcomes.map { (it as AgentParallelWaveTaskOutcome.Completed).stepId }
            )
        } finally {
            pool.shutdownNow()
        }
    }
}
