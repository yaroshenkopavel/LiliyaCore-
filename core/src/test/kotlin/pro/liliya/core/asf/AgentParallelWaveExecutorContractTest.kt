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
    fun mismatched_step_identity_fails_closed_and_cancels_sibling() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val siblingInterrupted = CountDownLatch(1)
            val siblingStarted = CountDownLatch(1)

            val result = AgentParallelWaveExecutor(pool).execute(
                wave = wave(),
                tasks = mapOf(
                    stepA to AgentParallelWaveTask {
                        siblingStarted.await(1, TimeUnit.SECONDS)
                        AgentParallelWaveTaskOutcome.Completed(
                            stepId = stepB,
                            artifactReference = "artifact:spoofed"
                        )
                    },
                    stepB to AgentParallelWaveTask {
                        siblingStarted.countDown()
                        try {
                            Thread.sleep(10_000)
                            AgentParallelWaveTaskOutcome.Completed(stepB)
                        } catch (_: InterruptedException) {
                            siblingInterrupted.countDown()
                            throw InterruptedException()
                        }
                    }
                ),
                timeoutMillis = 2_000
            )

            assertEquals(AgentParallelWaveExecutionState.PARTIAL, result.state)
            assertEquals(1, result.outcomes.size)
            val failed = kotlin.test.assertIs<AgentParallelWaveTaskOutcome.Failed>(
                result.outcomes.single()
            )
            assertEquals(stepA, failed.stepId)
            assertEquals(
                "parallel wave task returned mismatched step id",
                failed.reason
            )
            assertTrue(siblingInterrupted.await(1, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun caller_timeout_cannot_exceed_reserved_wave_wall_clock_budget() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val taskInterrupted = CountDownLatch(1)
            val result = AgentParallelWaveExecutor(pool).execute(
                wave = AgentParallelWave(
                    index = 0,
                    stepIds = listOf(stepA),
                    reservation = AgentParallelWaveReservation(
                        maxWallClockMillis = 40,
                        maxInferenceUnits = 100,
                        maxContextBytes = 100,
                        maxRetrievalItems = 0,
                        maxArtifacts = 1,
                        maxAgents = 1
                    )
                ),
                tasks = mapOf(
                    stepA to AgentParallelWaveTask {
                        try {
                            Thread.sleep(5_000)
                            AgentParallelWaveTaskOutcome.Completed(stepA)
                        } catch (_: InterruptedException) {
                            taskInterrupted.countDown()
                            throw InterruptedException()
                        }
                    }
                ),
                timeoutMillis = 5_000
            )
            assertEquals(AgentParallelWaveExecutionState.TIMED_OUT, result.state)
            assertTrue(result.outcomes.isEmpty())
            assertTrue(taskInterrupted.await(1, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun very_large_timeout_does_not_overflow_monotonic_deadline() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = AgentParallelWaveExecutor(pool).execute(
                wave = AgentParallelWave(
                    index = 0,
                    stepIds = listOf(stepA),
                    reservation = AgentParallelWaveReservation(
                        maxWallClockMillis = 1_000,
                        maxInferenceUnits = 1_000,
                        maxContextBytes = 1_000,
                        maxRetrievalItems = 0,
                        maxArtifacts = 1,
                        maxAgents = 1
                    )
                ),
                tasks = mapOf(
                    stepA to AgentParallelWaveTask {
                        AgentParallelWaveTaskOutcome.Completed(stepA)
                    }
                ),
                timeoutMillis = Long.MAX_VALUE
            )
            assertEquals(AgentParallelWaveExecutionState.COMPLETED, result.state)
            assertEquals(listOf(stepA), result.outcomes.map(::outcomeStepId))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun artifact_reference_surface_is_bounded() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            AgentParallelWaveTaskOutcome.Completed(
                stepId = stepA,
                artifactReference = " "
            )
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            AgentParallelWaveTaskOutcome.Completed(
                stepId = stepA,
                artifactReference = "x".repeat(257)
            )
        }
    }

    @Test
    fun executor_submission_rejection_fails_closed_without_throwing() {
        val pool = Executors.newFixedThreadPool(1)
        pool.shutdownNow()

        val result = AgentParallelWaveExecutor(pool).execute(
            wave = AgentParallelWave(
                index = 0,
                stepIds = listOf(stepA),
                reservation = AgentParallelWaveReservation(
                    maxWallClockMillis = 1_000,
                    maxInferenceUnits = 1_000,
                    maxContextBytes = 1_000,
                    maxRetrievalItems = 0,
                    maxArtifacts = 1,
                    maxAgents = 1
                )
            ),
            tasks = mapOf(
                stepA to AgentParallelWaveTask {
                    error("must not run")
                }
            ),
            timeoutMillis = 1_000
        )

        assertEquals(AgentParallelWaveExecutionState.PARTIAL, result.state)
        assertEquals(1, result.outcomes.size)
        assertEquals(
            "parallel wave task submission failed",
            (result.outcomes.single() as AgentParallelWaveTaskOutcome.Failed).reason
        )
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

    @Test
    fun interrupted_caller_cancels_running_tasks_and_preserves_interrupt_flag() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val bothStarted = CountDownLatch(2)
            val workersInterrupted = CountDownLatch(2)
            val finished = CountDownLatch(1)
            val callerInterrupted = AtomicBoolean(false)
            val state = java.util.concurrent.atomic.AtomicReference<AgentParallelWaveExecutionState>()
            val caller = Thread {
                val result = AgentParallelWaveExecutor(pool).execute(
                    wave = wave(),
                    tasks = mapOf(
                        stepA to blockingTask(stepA, bothStarted, workersInterrupted),
                        stepB to blockingTask(stepB, bothStarted, workersInterrupted)
                    ),
                    timeoutMillis = 2_000
                )
                state.set(result.state)
                callerInterrupted.set(Thread.currentThread().isInterrupted)
                finished.countDown()
            }
            caller.start()
            assertTrue(bothStarted.await(1, TimeUnit.SECONDS))
            caller.interrupt()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(AgentParallelWaveExecutionState.CANCELLED, state.get())
            assertTrue(callerInterrupted.get())
            assertTrue(workersInterrupted.await(1, TimeUnit.SECONDS))
            caller.join(1_000)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun terminal_task_failure_immediately_interrupts_running_sibling() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val siblingStarted = CountDownLatch(1)
            val siblingInterrupted = CountDownLatch(1)
            val result = AgentParallelWaveExecutor(pool).execute(
                wave = wave(),
                tasks = mapOf(
                    stepA to AgentParallelWaveTask {
                        assertTrue(siblingStarted.await(1, TimeUnit.SECONDS))
                        AgentParallelWaveTaskOutcome.Failed(stepA, "rejected")
                    },
                    stepB to AgentParallelWaveTask {
                        siblingStarted.countDown()
                        try {
                            Thread.sleep(10_000)
                            AgentParallelWaveTaskOutcome.Completed(stepB)
                        } catch (_: InterruptedException) {
                            siblingInterrupted.countDown()
                            throw InterruptedException()
                        }
                    }
                ),
                timeoutMillis = 2_000
            )
            assertEquals(AgentParallelWaveExecutionState.PARTIAL, result.state)
            assertEquals(1, result.outcomes.size)
            assertEquals(stepA, outcomeStepId(result.outcomes.single()))
            assertTrue(siblingInterrupted.await(1, TimeUnit.SECONDS))
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
