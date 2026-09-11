package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class ProductionAndroidAppStartupTaskContractTest {
    @Test
    fun concurrent_activity_subscribers_share_one_in_flight_startup() {
        val executor = QueuedExecutor()
        val task = ProductionAndroidAppStartupTask(executor)
        var firstStarts = 0
        var secondStarts = 0
        val firstResults = mutableListOf<ProductionAndroidAppStartupTaskResult>()
        val secondResults = mutableListOf<ProductionAndroidAppStartupTaskResult>()

        task.request(
            startup = {
                firstStarts += 1
                ProductionAndroidAppStartupOutcome.Runtime(
                    ProductionAndroidAppRuntimeState.READY
                )
            },
            callback = { firstResults += it }
        )
        task.request(
            startup = {
                secondStarts += 1
                ProductionAndroidAppStartupOutcome.Runtime(
                    ProductionAndroidAppRuntimeState.FAILED
                )
            },
            callback = { secondResults += it }
        )

        assertEquals(1, executor.pendingCount)
        executor.runNext()

        assertEquals(1, firstStarts)
        assertEquals(0, secondStarts)
        assertEquals(1, firstResults.size)
        assertEquals(1, secondResults.size)
        assertEquals(
            ProductionAndroidAppRuntimeState.READY,
            assertIs<ProductionAndroidAppStartupTaskResult.Completed>(firstResults.single())
                .outcome.let { assertIs<ProductionAndroidAppStartupOutcome.Runtime>(it).state }
        )
        assertEquals(
            ProductionAndroidAppRuntimeState.READY,
            assertIs<ProductionAndroidAppStartupTaskResult.Completed>(secondResults.single())
                .outcome.let { assertIs<ProductionAndroidAppStartupOutcome.Runtime>(it).state }
        )
    }

    @Test
    fun completed_configuration_required_does_not_block_a_later_startup_request() {
        val executor = QueuedExecutor()
        val task = ProductionAndroidAppStartupTask(executor)
        var starts = 0
        val results = mutableListOf<ProductionAndroidAppStartupTaskResult>()

        task.request(
            startup = {
                starts += 1
                ProductionAndroidAppStartupOutcome.ConfigurationRequired
            },
            callback = { results += it }
        )
        executor.runNext()

        task.request(
            startup = {
                starts += 1
                ProductionAndroidAppStartupOutcome.Runtime(
                    ProductionAndroidAppRuntimeState.READY
                )
            },
            callback = { results += it }
        )
        executor.runNext()

        assertEquals(2, starts)
        assertIs<ProductionAndroidAppStartupOutcome.ConfigurationRequired>(
            assertIs<ProductionAndroidAppStartupTaskResult.Completed>(results[0]).outcome
        )
        assertEquals(
            ProductionAndroidAppRuntimeState.READY,
            assertIs<ProductionAndroidAppStartupOutcome.Runtime>(
                assertIs<ProductionAndroidAppStartupTaskResult.Completed>(results[1]).outcome
            ).state
        )
    }

    @Test
    fun startup_exception_is_reported_and_does_not_poison_a_later_request() {
        val executor = QueuedExecutor()
        val task = ProductionAndroidAppStartupTask(executor)
        var starts = 0
        val results = mutableListOf<ProductionAndroidAppStartupTaskResult>()

        task.request(
            startup = {
                starts += 1
                error("boom")
            },
            callback = { results += it }
        )
        executor.runNext()

        task.request(
            startup = {
                starts += 1
                ProductionAndroidAppStartupOutcome.ConfigurationRequired
            },
            callback = { results += it }
        )
        executor.runNext()

        assertEquals(2, starts)
        assertIs<ProductionAndroidAppStartupTaskResult.Failed>(results[0])
        assertIs<ProductionAndroidAppStartupTaskResult.Completed>(results[1])
    }

    @Test
    fun one_callback_failure_does_not_block_recreated_activity_callback() {
        val executor = QueuedExecutor()
        val task = ProductionAndroidAppStartupTask(executor)
        var delivered = 0

        task.request(
            startup = { ProductionAndroidAppStartupOutcome.ConfigurationRequired },
            callback = { error("destroyed activity callback") }
        )
        task.request(
            startup = { error("must not run second startup") },
            callback = { delivered += 1 }
        )
        executor.runNext()

        assertEquals(1, delivered)
    }

    private class QueuedExecutor : ProductionAndroidAppStartupExecutor {
        private val pending = ArrayDeque<() -> Unit>()

        val pendingCount: Int
            get() = pending.size

        override fun execute(block: () -> Unit) {
            pending += block
        }

        fun runNext() {
            pending.removeFirst().invoke()
        }
    }
}
