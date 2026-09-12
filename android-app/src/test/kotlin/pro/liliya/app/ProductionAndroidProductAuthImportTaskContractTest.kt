package pro.liliya.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionAndroidProductAuthImportTaskContractTest {
    @Test
    fun `terminal result survives observation until exact consumption without replay`() {
        var queued: (() -> Unit)? = null
        val task = ProductionAndroidProductAuthImportTask(
            ProductionAndroidProductAuthImportExecutor { block -> queued = block }
        )
        var imports = 0
        var completions = 0

        val started = task.request(
            importCredential = {
                imports += 1
                ProductionAndroidProductAuthImportResult.Imported
            },
            listener = { completions += 1 }
        ) as ProductionAndroidProductAuthImportTaskRequestResult.Started

        assertTrue(task.observe { completions += 1 } is ProductionAndroidProductAuthImportTaskSnapshot.InFlight)
        assertTrue(task.request({ ProductionAndroidProductAuthImportResult.Imported }) {} is ProductionAndroidProductAuthImportTaskRequestResult.Busy)

        queued!!.invoke()
        assertEquals(1, imports)
        assertEquals(2, completions)
        val completed = task.observe { completions += 10 }
            as ProductionAndroidProductAuthImportTaskSnapshot.Completed
        assertEquals(started.requestId, completed.requestId)
        assertEquals(ProductionAndroidProductAuthImportResult.Imported, completed.result)
        assertEquals(2, completions)

        assertFalse(task.consume(started.requestId + 1))
        assertTrue(task.consume(started.requestId))
        assertTrue(task.observe {} is ProductionAndroidProductAuthImportTaskSnapshot.Idle)
        assertEquals(1, imports)
    }

    @Test
    fun `executor and import failures become typed failed terminal result`() {
        val importFailure = ProductionAndroidProductAuthImportTask(
            ProductionAndroidProductAuthImportExecutor { block -> block() }
        )
        var result: ProductionAndroidProductAuthImportResult? = null
        importFailure.request(
            importCredential = { throw IllegalStateException("boom") },
            listener = { result = it.result }
        )
        assertEquals(ProductionAndroidProductAuthImportResult.Failed, result)

        val executorFailure = ProductionAndroidProductAuthImportTask(
            ProductionAndroidProductAuthImportExecutor { throw IllegalStateException("executor") }
        )
        result = null
        executorFailure.request(
            importCredential = { ProductionAndroidProductAuthImportResult.Imported },
            listener = { result = it.result }
        )
        assertEquals(ProductionAndroidProductAuthImportResult.Failed, result)
    }
}
