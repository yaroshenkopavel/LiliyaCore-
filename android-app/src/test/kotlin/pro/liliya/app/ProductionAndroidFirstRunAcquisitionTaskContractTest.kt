package pro.liliya.app

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class ProductionAndroidFirstRunAcquisitionTaskContractTest {
    @Test
    fun recreated_observer_joins_same_inflight_attempt_without_replay() {
        val held = AtomicReference<(() -> Unit)?>(null)
        val task = ProductionAndroidFirstRunAcquisitionTask(
            ProductionAndroidFirstRunAcquisitionExecutor { block ->
                assertTrue(held.compareAndSet(null, block))
            }
        )
        var executions = 0
        var firstDeliveries = 0
        var recreatedDeliveries = 0

        val started = assertIs<ProductionAndroidFirstRunAcquisitionTaskRequestResult.Started>(
            task.request(
                acquireAndInstall = {
                    executions += 1
                    ProductionAndroidFirstRunAcquisitionResult.HostConfigurationRequired
                },
                listener = { firstDeliveries += 1 }
            )
        )
        val inFlight = assertIs<ProductionAndroidFirstRunAcquisitionTaskSnapshot.InFlight>(
            task.observe { recreatedDeliveries += 1 }
        )
        assertEquals(started.requestId, inFlight.requestId)
        assertEquals(0, executions)

        held.getAndSet(null)?.invoke() ?: error("first-run block missing")

        assertEquals(1, executions)
        assertEquals(1, firstDeliveries)
        assertEquals(1, recreatedDeliveries)
        val completed = assertIs<ProductionAndroidFirstRunAcquisitionTaskSnapshot.Completed>(
            task.observe { error("retained terminal must not redeliver") }
        )
        assertEquals(started.requestId, completed.requestId)
        assertIs<ProductionAndroidFirstRunAcquisitionResult.HostConfigurationRequired>(
            completed.result
        )
    }

    @Test
    fun terminal_is_busy_until_exact_request_is_consumed() {
        val task = immediateTask()
        val first = assertIs<ProductionAndroidFirstRunAcquisitionTaskRequestResult.Started>(
            task.request(
                acquireAndInstall = {
                    ProductionAndroidFirstRunAcquisitionResult.LocalModelRequired
                },
                listener = {}
            )
        )

        assertIs<ProductionAndroidFirstRunAcquisitionTaskRequestResult.Busy>(
            task.request(
                acquireAndInstall = { error("must not execute while terminal retained") },
                listener = {}
            )
        )
        assertEquals(false, task.consume(first.requestId + 1))
        assertIs<ProductionAndroidFirstRunAcquisitionTaskSnapshot.Completed>(task.observe {})
        assertTrue(task.consume(first.requestId))
        assertIs<ProductionAndroidFirstRunAcquisitionTaskSnapshot.Idle>(task.observe {})

        assertIs<ProductionAndroidFirstRunAcquisitionTaskRequestResult.Started>(
            task.request(
                acquireAndInstall = {
                    ProductionAndroidFirstRunAcquisitionResult.HostConfigurationRequired
                },
                listener = {}
            )
        )
    }

    @Test
    fun acquisition_exception_becomes_failed_terminal_and_retry_is_allowed_after_consume() {
        val task = immediateTask()
        val first = assertIs<ProductionAndroidFirstRunAcquisitionTaskRequestResult.Started>(
            task.request(
                acquireAndInstall = { error("private first-run failure") },
                listener = {}
            )
        )
        val failed = assertIs<ProductionAndroidFirstRunAcquisitionTaskSnapshot.Completed>(
            task.observe {}
        )
        assertIs<ProductionAndroidFirstRunAcquisitionResult.Failed>(failed.result)
        assertTrue(task.consume(first.requestId))

        val second = assertIs<ProductionAndroidFirstRunAcquisitionTaskRequestResult.Started>(
            task.request(
                acquireAndInstall = {
                    ProductionAndroidFirstRunAcquisitionResult.LocalModelRequired
                },
                listener = {}
            )
        )
        assertTrue(second.requestId > first.requestId)
    }

    @Test
    fun executor_rejection_becomes_failed_terminal() {
        val task = ProductionAndroidFirstRunAcquisitionTask(
            ProductionAndroidFirstRunAcquisitionExecutor {
                error("executor rejected")
            }
        )

        val started = assertIs<ProductionAndroidFirstRunAcquisitionTaskRequestResult.Started>(
            task.request(
                acquireAndInstall = { error("must not execute") },
                listener = {}
            )
        )
        val completed = assertIs<ProductionAndroidFirstRunAcquisitionTaskSnapshot.Completed>(
            task.observe {}
        )
        assertEquals(started.requestId, completed.requestId)
        assertIs<ProductionAndroidFirstRunAcquisitionResult.Failed>(completed.result)
    }

    @Test
    fun one_listener_failure_does_not_block_other_observers_or_terminal_retention() {
        val held = AtomicReference<(() -> Unit)?>(null)
        val task = ProductionAndroidFirstRunAcquisitionTask(
            ProductionAndroidFirstRunAcquisitionExecutor { block -> held.set(block) }
        )
        var healthyDeliveries = 0
        val started = assertIs<ProductionAndroidFirstRunAcquisitionTaskRequestResult.Started>(
            task.request(
                acquireAndInstall = {
                    ProductionAndroidFirstRunAcquisitionResult.HostConfigurationRequired
                },
                listener = { error("stale Activity callback") }
            )
        )
        task.observe { healthyDeliveries += 1 }

        held.getAndSet(null)?.invoke() ?: error("first-run block missing")

        assertEquals(1, healthyDeliveries)
        val completed = assertIs<ProductionAndroidFirstRunAcquisitionTaskSnapshot.Completed>(
            task.observe {}
        )
        assertEquals(started.requestId, completed.requestId)
        assertTrue(task.consume(started.requestId))
    }

    private fun immediateTask() = ProductionAndroidFirstRunAcquisitionTask(
        ProductionAndroidFirstRunAcquisitionExecutor { block -> block() }
    )
}
