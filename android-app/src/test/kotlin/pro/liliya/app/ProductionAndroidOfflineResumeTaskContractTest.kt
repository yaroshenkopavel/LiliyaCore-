package pro.liliya.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionAndroidOfflineResumeTaskContractTest {
    @Test
    fun single_flight_and_recreation_observer_deliver_one_terminal_result() {
        val scheduled = mutableListOf<() -> Unit>()
        val task = ProductionAndroidOfflineResumeTask(
            ProductionAndroidOfflineResumeExecutor { scheduled += it }
        )
        var executions = 0
        val result = ProductionAndroidOfflineResumeBootstrapResult.Rejected(
            ProductionAndroidOfflineResumeBootstrapFailure.MATERIAL_REJECTED
        )
        val started = task.request(
            resume = { executions++; result },
            listener = { }
        ) as ProductionAndroidOfflineResumeTaskRequestResult.Started
        assertEquals(1, scheduled.size)
        assertEquals(
            ProductionAndroidOfflineResumeTaskRequestResult.Busy,
            task.request(resume = { executions++; result }, listener = { })
        )
        var observed: ProductionAndroidOfflineResumeTaskSnapshot.Completed? = null
        assertTrue(task.observe { observed = it } is ProductionAndroidOfflineResumeTaskSnapshot.InFlight)
        scheduled.single().invoke()
        assertEquals(1, executions)
        assertEquals(started.requestId, observed?.requestId)
        assertEquals(result, observed?.result)
        assertEquals(
            ProductionAndroidOfflineResumeTaskSnapshot.Completed(started.requestId, result),
            task.observe { }
        )
        assertFalse(task.consume(started.requestId + 1))
        assertTrue(task.consume(started.requestId))
        assertEquals(ProductionAndroidOfflineResumeTaskSnapshot.Idle, task.observe { })
    }

    @Test
    fun failure_is_retained_as_fail_closed_result() {
        val task = ProductionAndroidOfflineResumeTask(
            ProductionAndroidOfflineResumeExecutor { it() }
        )
        val started = task.request(
            resume = { error("I/O failure") },
            listener = { }
        ) as ProductionAndroidOfflineResumeTaskRequestResult.Started
        assertEquals(
            ProductionAndroidOfflineResumeTaskSnapshot.Completed(
                started.requestId,
                ProductionAndroidOfflineResumeBootstrapResult.Rejected(
                    ProductionAndroidOfflineResumeBootstrapFailure.FAILED
                )
            ),
            task.observe { }
        )
    }
}
