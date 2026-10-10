package pro.liliya.core.asf

import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

fun interface AgentParallelWaveTask {
    fun run(): AgentParallelWaveTaskOutcome
}

sealed interface AgentParallelWaveTaskOutcome {
    data class Completed(
        val stepId: AgentCoordinatorStepId,
        val artifactReference: String? = null
    ) : AgentParallelWaveTaskOutcome {
        init {
            artifactReference?.let {
                require(it.isNotBlank()) {
                    "parallel wave artifact reference must not be blank"
                }
                require(it.toByteArray(StandardCharsets.UTF_8).size <= 256) {
                    "parallel wave artifact reference exceeds bounded size"
                }
            }
        }
    }

    data class Failed(
        val stepId: AgentCoordinatorStepId,
        val reason: String
    ) : AgentParallelWaveTaskOutcome {
        init {
            require(reason.isNotBlank())
            require(reason.toByteArray(StandardCharsets.UTF_8).size <= 4096) {
                "parallel wave failure reason exceeds bounded size"
            }
        }
    }
}

enum class AgentParallelWaveExecutionState {
    COMPLETED,
    PARTIAL,
    CANCELLED,
    TIMED_OUT
}

data class AgentParallelWaveExecutionResult(
    val state: AgentParallelWaveExecutionState,
    val outcomes: List<AgentParallelWaveTaskOutcome>
) {
    init {
        val ids = outcomes.map {
            when (it) {
                is AgentParallelWaveTaskOutcome.Completed -> it.stepId
                is AgentParallelWaveTaskOutcome.Failed -> it.stepId
            }
        }
        require(ids == ids.sortedBy { it.value }) {
            "parallel wave outcomes must use canonical step order"
        }
        require(ids.distinct().size == ids.size) {
            "parallel wave outcome step ids must be unique"
        }
    }
}

/**
 * Bounded concurrent executor for one already-admitted dependency wave.
 *
 * The scheduler decides which steps may run together and reserves their ceilings before this
 * executor is invoked. This executor owns no Authority, capability grant, tool permission, license
 * state, agent factory or durable permission-bearing state. It only runs the supplied wave tasks
 * concurrently and publishes terminal outcomes in deterministic step-id order.
 */
class AgentParallelWaveExecutor(
    private val executor: ExecutorService
) {
    fun execute(
        wave: AgentParallelWave,
        tasks: Map<AgentCoordinatorStepId, AgentParallelWaveTask>,
        timeoutMillis: Long,
        cancelled: () -> Boolean = { false }
    ): AgentParallelWaveExecutionResult {
        require(timeoutMillis > 0L) {
            "parallel wave timeout must be positive"
        }
        require(tasks.keys == wave.stepIds.toSet()) {
            "parallel wave tasks must exactly match scheduled step ids"
        }
        if (cancelled()) {
            return AgentParallelWaveExecutionResult(
                state = AgentParallelWaveExecutionState.CANCELLED,
                outcomes = emptyList()
            )
        }

        // Use elapsed monotonic time rather than adding to nanoTime: the origin can
        // be negative and a saturated nanos timeout must not overflow the deadline.
        val startNanos = System.nanoTime()
        // Never exceed the wall-clock ceiling already reserved by the scheduler.
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(minOf(timeoutMillis, wave.reservation.maxWallClockMillis))
        val futures = linkedMapOf<AgentCoordinatorStepId, Future<AgentParallelWaveTaskOutcome>>()

        try {
            wave.stepIds.forEach { stepId ->
                val future = try {
                    executor.submit(
                        Callable {
                            tasks.getValue(stepId).run()
                        }
                    )
                } catch (_: Exception) {
                    cancelAll(futures.values)
                    return AgentParallelWaveExecutionResult(
                        state = AgentParallelWaveExecutionState.PARTIAL,
                        outcomes = listOf(
                            AgentParallelWaveTaskOutcome.Failed(
                                stepId = stepId,
                                reason = "parallel wave task submission failed"
                            )
                        )
                    )
                }
                futures[stepId] = future
            }

            val outcomes = mutableListOf<AgentParallelWaveTaskOutcome>()
            for (stepId in wave.stepIds) {
                if (cancelled()) {
                    cancelAll(futures.values)
                    return AgentParallelWaveExecutionResult(
                        state = if (outcomes.isEmpty()) {
                            AgentParallelWaveExecutionState.CANCELLED
                        } else {
                            AgentParallelWaveExecutionState.PARTIAL
                        },
                        outcomes = outcomes
                    )
                }

                val remainingNanos = timeoutNanos - (System.nanoTime() - startNanos)
                if (remainingNanos <= 0L) {
                    cancelAll(futures.values)
                    return AgentParallelWaveExecutionResult(
                        state = if (outcomes.isEmpty()) {
                            AgentParallelWaveExecutionState.TIMED_OUT
                        } else {
                            AgentParallelWaveExecutionState.PARTIAL
                        },
                        outcomes = outcomes
                    )
                }

                val outcome = await(
                    stepId = stepId,
                    future = futures.getValue(stepId),
                    startNanos = startNanos,
                    timeoutNanos = timeoutNanos,
                    cancelled = cancelled
                ) ?: run {
                    cancelAll(futures.values)
                    return AgentParallelWaveExecutionResult(
                        state = if (outcomes.isEmpty()) {
                            if (cancelled()) AgentParallelWaveExecutionState.CANCELLED
                            else AgentParallelWaveExecutionState.TIMED_OUT
                        } else {
                            AgentParallelWaveExecutionState.PARTIAL
                        },
                        outcomes = outcomes
                    )
                }

                val returnedId = when (outcome) {
                    is AgentParallelWaveTaskOutcome.Completed -> outcome.stepId
                    is AgentParallelWaveTaskOutcome.Failed -> outcome.stepId
                }
                if (returnedId != stepId) {
                    cancelAll(futures.values)
                    outcomes += AgentParallelWaveTaskOutcome.Failed(
                        stepId = stepId,
                        reason = "parallel wave task returned mismatched step id"
                    )
                    return AgentParallelWaveExecutionResult(
                        state = AgentParallelWaveExecutionState.PARTIAL,
                        outcomes = outcomes
                    )
                }
                outcomes += outcome
                if (outcome is AgentParallelWaveTaskOutcome.Failed) {
                    // A terminal failure must stop the wave immediately. Waiting for
                    // siblings would allow avoidable work after fail-closed rejection.
                    cancelAll(futures.values)
                    return AgentParallelWaveExecutionResult(
                        state = AgentParallelWaveExecutionState.PARTIAL,
                        outcomes = outcomes
                    )
                }
            }

            val state = if (outcomes.all { it is AgentParallelWaveTaskOutcome.Completed }) {
                AgentParallelWaveExecutionState.COMPLETED
            } else {
                AgentParallelWaveExecutionState.PARTIAL
            }
            return AgentParallelWaveExecutionResult(
                state = state,
                outcomes = outcomes
            )
        } catch (_: InterruptedException) {
            cancelAll(futures.values)
            Thread.currentThread().interrupt()
            return AgentParallelWaveExecutionResult(
                state = AgentParallelWaveExecutionState.CANCELLED,
                outcomes = emptyList()
            )
        } catch (_: ArithmeticException) {
            cancelAll(futures.values)
            return AgentParallelWaveExecutionResult(
                state = AgentParallelWaveExecutionState.TIMED_OUT,
                outcomes = emptyList()
            )
        } finally {
            if (cancelled()) {
                cancelAll(futures.values)
            }
        }
    }

    private fun await(
        stepId: AgentCoordinatorStepId,
        future: Future<AgentParallelWaveTaskOutcome>,
        startNanos: Long,
        timeoutNanos: Long,
        cancelled: () -> Boolean
    ): AgentParallelWaveTaskOutcome? {
        val pollNanos = TimeUnit.MILLISECONDS.toNanos(10)
        while (true) {
            if (cancelled()) return null
            val remaining = timeoutNanos - (System.nanoTime() - startNanos)
            if (remaining <= 0L) return null
            try {
                return future.get(minOf(remaining, pollNanos), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                // Re-check cancellation and deadline.
            } catch (interrupted: InterruptedException) {
                throw interrupted
            } catch (_: Exception) {
                return AgentParallelWaveTaskOutcome.Failed(
                    stepId = stepId,
                    reason = "parallel wave task failed"
                )
            }
        }
    }

    private fun cancelAll(
        futures: Collection<Future<AgentParallelWaveTaskOutcome>>
    ) {
        futures.forEach { future ->
            if (!future.isDone) {
                future.cancel(true)
            }
        }
    }
}
