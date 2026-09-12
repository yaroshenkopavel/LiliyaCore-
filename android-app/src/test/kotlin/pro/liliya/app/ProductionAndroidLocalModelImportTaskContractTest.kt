package pro.liliya.app

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class ProductionAndroidLocalModelImportTaskContractTest {
    @Test
    fun recreated_observer_reuses_one_inflight_import_and_receives_terminal() {
        val held = AtomicReference<(() -> Unit)?>(null)
        val task = ProductionAndroidLocalModelImportTask(
            ProductionAndroidLocalModelImportExecutor { block ->
                check(held.compareAndSet(null, block))
            }
        )
        val first = mutableListOf<ProductionAndroidLocalModelImportTaskSnapshot.Completed>()
        val recreated = mutableListOf<ProductionAndroidLocalModelImportTaskSnapshot.Completed>()
        val expected = ProductionAndroidLocalModelSelectionResult.Selected(File("/tmp/model.bin"))

        val started = assertIs<ProductionAndroidLocalModelImportTaskRequestResult.Started>(
            task.request(importModel = { expected }, listener = first::add)
        )
        val observed = assertIs<ProductionAndroidLocalModelImportTaskSnapshot.InFlight>(
            task.observe(recreated::add)
        )
        assertEquals(started.requestId, observed.requestId)

        held.getAndSet(null)?.invoke() ?: error("import block not scheduled")

        assertEquals(1, first.size)
        assertEquals(1, recreated.size)
        assertEquals(started.requestId, recreated.single().requestId)
        assertSame(expected, recreated.single().result)
    }

    @Test
    fun terminal_is_retained_until_exact_request_is_consumed() {
        val task = ProductionAndroidLocalModelImportTask(
            ProductionAndroidLocalModelImportExecutor { block -> block() }
        )
        val expected = ProductionAndroidLocalModelSelectionResult.EmptyDocument
        val started = assertIs<ProductionAndroidLocalModelImportTaskRequestResult.Started>(
            task.request(importModel = { expected }, listener = {})
        )

        val observed = assertIs<ProductionAndroidLocalModelImportTaskSnapshot.Completed>(
            task.observe {}
        )
        assertEquals(started.requestId, observed.requestId)
        assertSame(expected, observed.result)
        assertFalse(task.consume(started.requestId + 1))
        assertTrue(task.consume(started.requestId))
        assertIs<ProductionAndroidLocalModelImportTaskSnapshot.Idle>(task.observe {})
    }

    @Test
    fun observing_retained_terminal_returns_snapshot_without_redelivering_listener() {
        val task = ProductionAndroidLocalModelImportTask(
            ProductionAndroidLocalModelImportExecutor { block -> block() }
        )
        var terminalRedeliveries = 0
        val started = assertIs<ProductionAndroidLocalModelImportTaskRequestResult.Started>(
            task.request(
                importModel = { ProductionAndroidLocalModelSelectionResult.EmptyDocument },
                listener = {}
            )
        )

        val retained = assertIs<ProductionAndroidLocalModelImportTaskSnapshot.Completed>(
            task.observe { terminalRedeliveries += 1 }
        )

        assertEquals(started.requestId, retained.requestId)
        assertEquals(0, terminalRedeliveries)
        assertTrue(task.consume(started.requestId))
        assertIs<ProductionAndroidLocalModelImportTaskSnapshot.Idle>(task.observe {})
    }

    @Test
    fun second_request_is_busy_while_inflight_or_terminal_unconsumed() {
        val held = AtomicReference<(() -> Unit)?>(null)
        val task = ProductionAndroidLocalModelImportTask(
            ProductionAndroidLocalModelImportExecutor { block ->
                check(held.compareAndSet(null, block))
            }
        )
        val started = assertIs<ProductionAndroidLocalModelImportTaskRequestResult.Started>(
            task.request(
                importModel = { ProductionAndroidLocalModelSelectionResult.EmptyDocument },
                listener = {}
            )
        )

        assertIs<ProductionAndroidLocalModelImportTaskRequestResult.Busy>(
            task.request(
                importModel = { ProductionAndroidLocalModelSelectionResult.Failed },
                listener = {}
            )
        )
        held.getAndSet(null)?.invoke() ?: error("import block not scheduled")
        assertIs<ProductionAndroidLocalModelImportTaskRequestResult.Busy>(
            task.request(
                importModel = { ProductionAndroidLocalModelSelectionResult.Failed },
                listener = {}
            )
        )
        assertTrue(task.consume(started.requestId))
        assertIs<ProductionAndroidLocalModelImportTaskRequestResult.Started>(
            task.request(
                importModel = { ProductionAndroidLocalModelSelectionResult.Failed },
                listener = {}
            )
        )
    }

    @Test
    fun execution_failure_becomes_terminal_failed_and_later_retry_is_allowed() {
        val task = ProductionAndroidLocalModelImportTask(
            ProductionAndroidLocalModelImportExecutor { block -> block() }
        )
        val first = assertIs<ProductionAndroidLocalModelImportTaskRequestResult.Started>(
            task.request(importModel = { error("provider failure") }, listener = {})
        )
        val terminal = assertIs<ProductionAndroidLocalModelImportTaskSnapshot.Completed>(
            task.observe {}
        )
        assertIs<ProductionAndroidLocalModelSelectionResult.Failed>(terminal.result)
        assertTrue(task.consume(first.requestId))

        assertIs<ProductionAndroidLocalModelImportTaskRequestResult.Started>(
            task.request(
                importModel = { ProductionAndroidLocalModelSelectionResult.EmptyDocument },
                listener = {}
            )
        )
    }

    @Test
    fun failing_listener_does_not_block_recreated_listener() {
        val held = AtomicReference<(() -> Unit)?>(null)
        val task = ProductionAndroidLocalModelImportTask(
            ProductionAndroidLocalModelImportExecutor { block -> held.set(block) }
        )
        var delivered = 0
        task.request(
            importModel = { ProductionAndroidLocalModelSelectionResult.EmptyDocument },
            listener = { error("stale activity callback") }
        )
        task.observe { delivered += 1 }

        held.getAndSet(null)?.invoke() ?: error("import block not scheduled")
        assertEquals(1, delivered)
    }
}
