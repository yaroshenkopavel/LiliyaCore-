package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class ProductionAndroidAppChatTaskContractTest {
    @Test
    fun in_flight_request_survives_observer_replacement_without_second_execution() {
        var held: (() -> Unit)? = null
        var executions = 0
        val task = ProductionAndroidAppChatTask<String>(
            executor = ProductionAndroidAppChatExecutor { block -> held = block }
        )
        val firstStates = mutableListOf<ProductionAndroidAppChatTaskState<String>>()
        val secondStates = mutableListOf<ProductionAndroidAppChatTaskState<String>>()
        val firstObserver: (ProductionAndroidAppChatTaskState<String>) -> Unit = { firstStates += it }
        val secondObserver: (ProductionAndroidAppChatTaskState<String>) -> Unit = { secondStates += it }

        task.observe(firstObserver)
        val started = assertIs<ProductionAndroidAppChatSubmitResult.Started>(
            task.submit(" hello ") {
                executions += 1
                "reply"
            }
        )
        task.removeObserver(firstObserver)

        val observed = assertIs<ProductionAndroidAppChatTaskState.InFlight>(
            task.observe(secondObserver)
        )
        assertEquals(started.requestId, observed.requestId)
        assertEquals("hello", observed.message)
        assertEquals(0, executions)

        held!!.invoke()
        assertEquals(1, executions)
        val terminal = assertIs<ProductionAndroidAppChatTaskState.Terminal<String>>(
            secondStates.single()
        )
        assertEquals(started.requestId, terminal.requestId)
        assertEquals("hello", terminal.message)
        assertEquals(
            "reply",
            assertIs<ProductionAndroidAppChatExecutionResult.Completed<String>>(terminal.result).value
        )
    }

    @Test
    fun terminal_result_is_retained_until_live_ui_consumes_it() {
        var held: (() -> Unit)? = null
        val task = ProductionAndroidAppChatTask<String>(
            executor = ProductionAndroidAppChatExecutor { block -> held = block }
        )
        val started = assertIs<ProductionAndroidAppChatSubmitResult.Started>(
            task.submit("request") { "reply" }
        )

        held!!.invoke()
        val terminal = assertIs<ProductionAndroidAppChatTaskState.Terminal<String>>(
            task.currentState()
        )
        assertEquals(started.requestId, terminal.requestId)

        val lateObserverStates = mutableListOf<ProductionAndroidAppChatTaskState<String>>()
        val lateObserver: (ProductionAndroidAppChatTaskState<String>) -> Unit = {
            lateObserverStates += it
        }
        val lateSnapshot = task.observe(lateObserver)
        assertSame(terminal, lateSnapshot)
        assertTrue(lateObserverStates.isEmpty())

        assertSame(terminal, task.consumeTerminal(started.requestId))
        assertNull(task.currentState())
        assertNull(task.consumeTerminal(started.requestId))
    }

    @Test
    fun second_submit_is_rejected_while_inflight_or_terminal_is_unconsumed() {
        var held: (() -> Unit)? = null
        val task = ProductionAndroidAppChatTask<String>(
            executor = ProductionAndroidAppChatExecutor { block -> held = block }
        )
        val started = assertIs<ProductionAndroidAppChatSubmitResult.Started>(
            task.submit("first") { "reply" }
        )

        val inFlightBusy = assertIs<ProductionAndroidAppChatSubmitResult.Busy>(
            task.submit("second") { "never" }
        )
        assertEquals(started.requestId, inFlightBusy.requestId)
        assertEquals("first", inFlightBusy.message)

        held!!.invoke()
        val terminalBusy = assertIs<ProductionAndroidAppChatSubmitResult.Busy>(
            task.submit("third") { "never" }
        )
        assertEquals(started.requestId, terminalBusy.requestId)

        task.consumeTerminal(started.requestId)
        assertIs<ProductionAndroidAppChatSubmitResult.Started>(
            task.submit("next") { "next-reply" }
        )
    }

    @Test
    fun execution_failure_is_terminal_and_retry_is_allowed_after_consumption() {
        val blocks = mutableListOf<() -> Unit>()
        val task = ProductionAndroidAppChatTask<String>(
            executor = ProductionAndroidAppChatExecutor { block -> blocks += block }
        )
        val started = assertIs<ProductionAndroidAppChatSubmitResult.Started>(
            task.submit("boom") { error("boom") }
        )

        blocks.removeAt(0).invoke()
        val terminal = assertIs<ProductionAndroidAppChatTaskState.Terminal<String>>(
            task.currentState()
        )
        assertSame(ProductionAndroidAppChatExecutionResult.Failed, terminal.result)
        task.consumeTerminal(started.requestId)

        assertIs<ProductionAndroidAppChatSubmitResult.Started>(
            task.submit("retry") { "ok" }
        )
    }

    @Test
    fun observer_failure_does_not_block_other_observers_or_terminal_retention() {
        var held: (() -> Unit)? = null
        val task = ProductionAndroidAppChatTask<String>(
            executor = ProductionAndroidAppChatExecutor { block -> held = block }
        )
        val delivered = mutableListOf<ProductionAndroidAppChatTaskState<String>>()
        task.observe { error("stale Activity") }
        task.observe { delivered += it }
        val started = assertIs<ProductionAndroidAppChatSubmitResult.Started>(
            task.submit("hello") { "reply" }
        )

        held!!.invoke()
        assertEquals(1, delivered.size)
        assertEquals(started.requestId, delivered.single().requestId)
        assertIs<ProductionAndroidAppChatTaskState.Terminal<String>>(task.currentState())
    }
}
