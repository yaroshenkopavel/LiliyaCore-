package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.asf.AgentCoordinatorStepId
import pro.liliya.core.asf.AgentParallelWave
import pro.liliya.core.asf.AgentParallelWaveExecutionState
import pro.liliya.core.asf.AgentParallelWaveExecutor
import pro.liliya.core.asf.AgentParallelWaveReservation
import pro.liliya.core.asf.AgentParallelWaveTask
import pro.liliya.core.asf.AgentParallelWaveTaskOutcome

/** Direct on-device executor contract; not proof of production runtime integration. */
@RunWith(AndroidJUnit4::class)
class AsfParallelWaveExecutorInstrumentedTest {
    private val a = AgentCoordinatorStepId("a")
    private val b = AgentCoordinatorStepId("b")

    @Test
    fun parallel_siblings_complete_in_canonical_order_on_device() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val started = CountDownLatch(2)
            val release = CountDownLatch(1)
            val releaser = Thread {
                if (started.await(3, TimeUnit.SECONDS)) release.countDown()
            }
            releaser.start()
            val result = AgentParallelWaveExecutor(pool).execute(
                wave = wave(listOf(a, b), 4_000),
                tasks = mapOf(
                    a to AgentParallelWaveTask {
                        started.countDown()
                        assertTrue(release.await(3, TimeUnit.SECONDS))
                        AgentParallelWaveTaskOutcome.Completed(a)
                    },
                    b to AgentParallelWaveTask {
                        started.countDown()
                        assertTrue(release.await(3, TimeUnit.SECONDS))
                        AgentParallelWaveTaskOutcome.Completed(b)
                    }
                ),
                timeoutMillis = 4_000
            )
            releaser.join(3_000)
            assertEquals(AgentParallelWaveExecutionState.COMPLETED, result.state)
            assertEquals(
                listOf(a, b),
                result.outcomes.map {
                    when (it) {
                        is AgentParallelWaveTaskOutcome.Completed -> it.stepId
                        is AgentParallelWaveTaskOutcome.Failed -> it.stepId
                    }
                }
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun reserved_wall_clock_ceiling_stops_long_task_on_device() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val interrupted = CountDownLatch(1)
            val result = AgentParallelWaveExecutor(pool).execute(
                wave = wave(listOf(a), 150),
                tasks = mapOf(
                    a to AgentParallelWaveTask {
                        try {
                            Thread.sleep(5_000)
                            AgentParallelWaveTaskOutcome.Completed(a)
                        } catch (_: InterruptedException) {
                            interrupted.countDown()
                            throw InterruptedException()
                        }
                    }
                ),
                timeoutMillis = 5_000
            )
            assertEquals(AgentParallelWaveExecutionState.TIMED_OUT, result.state)
            assertTrue(result.outcomes.isEmpty())
            assertTrue(interrupted.await(3, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    private fun wave(ids: List<AgentCoordinatorStepId>, maxWallClockMillis: Long) =
        AgentParallelWave(
            index = 0,
            stepIds = ids,
            reservation = AgentParallelWaveReservation(
                maxWallClockMillis = maxWallClockMillis,
                maxInferenceUnits = 1_000,
                maxContextBytes = 1_000,
                maxRetrievalItems = 0,
                maxArtifacts = ids.size,
                maxAgents = ids.size
            )
        )
}
