package pro.liliya.core.asf

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentParallelWaveExecutorContractTest {
    private val stepA = AgentCoordinatorStepId("a")
    private val stepB = AgentCoordinatorStepId("b")

    @Test
    fun sibling_tasks_run_concurrently_but_results_publish_in_canonical_order() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val bothStarted = CountDownLatch(2)
            val release = CountDownLatch(1)

            val releaser = Thread {
                if (bothStarted.await(1, TimeUnit.SECONDS)) {
                    release.countDown()
                }
            }.apply { start() }

            val result = AgentParallelWaveExecutor(pool).execute(
                wave = wave(),
                tasks = mapOf(
                    stepA to AgentParallelWaveTask {
                        bothStarted.countDown()
                        release.await(2, TimeUnit.SECONDS)
                        AgentParallelWaveTaskOutcome.Completed(stepA, "artifact:a")
                    },
                    stepB to AgentParallelWaveTask {
                        bothStarted.countDown()
                        release.await(2, TimeUnit.SECONDS)
                        AgentParallelWaveTaskOutcome.Completed(stepB, "artifact:b")
                    }
                ),
                timeoutMillis = 2_000
            )

            releaser.join(1_000)
            assertEquals(AgentParallelWaveExecutionState.COMPLETED, result.state)
            assertEquals(
                listOf(stepA, stepB),
                result.outcomes.map(::outcomeStepId)
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun concurrent_completion_order_does_not_change_published_step_order() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val bCompleted = CountDownLatch(1)
            val result = AgentParallelWaveExecutor(pool).execute(
                wave = wave(),
                tasks = mapOf(
                    stepA to AgentParallelWaveTask {
                        bCompleted.await(1, TimeUnit.SECONDS)
                        AgentParallelWaveTaskOutcome.Completed(stepA, "artifact:a")
                    },
                    stepB to AgentParallelWaveTask {
                        bCompleted.countDown()
                        AgentParallelWaveTaskOutcome.Completed(stepB, "artifact:b")
                    }
                ),
                timeoutMillis = 2_000
            )

            assertEquals(AgentParallelWaveExecutionState.COMPLETED, result.state)
            assertEquals(listOf(stepA, stepB), result.outcomes.map(::outcomeStepId))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun cancellation_is_polled_and_fans_out_to_live_tasks() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val cancel = AtomicBoolean(false)
            val interrupted = CountDownLatch(2)
            val tasksStarted = CountDownLatch(2)

            val toggler = Thread {
                tasksStarted.await(1, TimeUnit.SECONDS)
                cancel.set(true)
            }.apply { start() }

            val result = AgentParallelWaveExecutor(pool).execute(
                wave = wave(),
                tasks = mapOf(
                    stepA to blockingTask(stepA, tasksStarted, interrupted),
                    stepB to blockingTask(stepB, tasksStarted, interrupted)
                ),
                timeoutMillis = 2_000,
                cancelled = cancel::get
            )

            toggler.join(1_000)
            assertEquals(AgentParallelWaveExecutionState.CANCELLED, result.state)
            assertTrue(interrupted.await(1, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun timeout_cancels_live_tasks_and_publishes_no_late_outcomes() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val interrupted = CountDownLatch(2)
            val started = CountDownLatch(2)
            val result = AgentParallelWaveExecutor(pool).execute(
                wave = wave(),
                tasks = mapOf(
                    stepA to blockingTask(stepA, started, interrupted),
                    stepB to blockingTask(stepB, started, interrupted)
                ),
                timeoutMillis = 50
            )

            assertEquals(AgentParallelWaveExecutionState.TIMED_OUT, result.state)
            assertTrue(result.outcomes.isEmpty())
            assertTrue(interrupted.await(1, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    private fun blockingTask(
        stepId: AgentCoordinatorStepId,
        started: CountDownLatch,
        interrupted: CountDownLatch
    ) = AgentParallelWaveTask {
        started.countDown()
        try {
            Thread.sleep(10_000)
            AgentParallelWaveTaskOutcome.Completed(stepId)
        } catch (_: InterruptedException) {
            interrupted.countDown()
            throw InterruptedException()
        }
    }

    private fun outcomeStepId(
        outcome: AgentParallelWaveTaskOutcome
    ): AgentCoordinatorStepId =
        when (outcome) {
            is AgentParallelWaveTaskOutcome.Completed -> outcome.stepId
            is AgentParallelWaveTaskOutcome.Failed -> outcome.stepId
        }

    private fun wave() =
        AgentParallelWave(
            index = 1,
            stepIds = listOf(stepA, stepB),
            reservation = AgentParallelWaveReservation(
                maxWallClockMillis = 2_000,
                maxInferenceUnits = 2_000,
                maxContextBytes = 2_000,
                maxRetrievalItems = 0,
                maxArtifacts = 2,
                maxAgents = 2
            )
        )
}
