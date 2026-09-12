package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import pro.liliya.android.runtime.ProductChatResult

class ProductionAndroidAppChatTaskContractTest {
    @Test
    fun recreated_observer_shares_one_inflight_request_and_receives_same_terminal_result() {
        var held: (() -> Unit)? = null
        var executorCalls = 0
        var sendCalls = 0
        val task = ProductionAndroidAppChatTask(
            ProductionAndroidAppChatExecutor { block ->
                executorCalls += 1
                held = block
            }
        )
        val firstResults = mutableListOf<ProductionAndroidAppChatTaskSnapshot.Completed>()
        val recreatedResults = mutableListOf<ProductionAndroidAppChatTaskSnapshot.Completed>()

        val started = assertIs<ProductionAndroidAppChatTaskRequestResult.Started>(
            task.request(
                message = "  Привет  ",
                send = { text ->
                    sendCalls += 1
                    completed("reply:$text")
                },
                listener = firstResults::add
            )
        )
        val observed = assertIs<ProductionAndroidAppChatTaskSnapshot.InFlight>(
            task.observe(recreatedResults::add)
        )

        assertEquals(started.requestId, observed.requestId)
        assertEquals("Привет", observed.message)
        assertEquals(1, executorCalls)
        assertEquals(0, sendCalls)

        requireNotNull(held).invoke()

        assertEquals(1, sendCalls)
        assertEquals(1, firstResults.size)
        assertEquals(1, recreatedResults.size)
        assertEquals(firstResults.single(), recreatedResults.single())
        assertIs<ProductionAndroidAppChatTaskSnapshot.Completed>(task.snapshot())
    }

    @Test
    fun terminal_result_is_retained_until_matching_request_is_consumed() {
        val task = ProductionAndroidAppChatTask(
            ProductionAndroidAppChatExecutor { block -> block() }
        )

        val started = assertIs<ProductionAndroidAppChatTaskRequestResult.Started>(
            task.request("hello", { completed("reply") }) { }
        )
        val terminal = assertIs<ProductionAndroidAppChatTaskSnapshot.Completed>(
            task.observe { error("completed state must be returned, not replayed through listener") }
        )

        assertEquals(started.requestId, terminal.requestId)
        assertFalse(task.consume(started.requestId + 1))
        assertIs<ProductionAndroidAppChatTaskSnapshot.Completed>(task.snapshot())
        assertTrue(task.consume(started.requestId))
        assertIs<ProductionAndroidAppChatTaskSnapshot.Idle>(task.snapshot())
    }

    @Test
    fun second_request_is_rejected_while_inflight_or_unconsumed_terminal_exists() {
        var held: (() -> Unit)? = null
        val task = ProductionAndroidAppChatTask(
            ProductionAndroidAppChatExecutor { block -> held = block }
        )
        val first = assertIs<ProductionAndroidAppChatTaskRequestResult.Started>(
            task.request("first", { completed("one") }) { }
        )

        assertIs<ProductionAndroidAppChatTaskRequestResult.Busy>(
            task.request("second", { completed("two") }) { }
        )

        requireNotNull(held).invoke()
        assertIs<ProductionAndroidAppChatTaskRequestResult.Busy>(
            task.request("second", { completed("two") }) { }
        )

        assertTrue(task.consume(first.requestId))
        assertIs<ProductionAndroidAppChatTaskRequestResult.Started>(
            task.request("second", { completed("two") }) { }
        )
    }

    @Test
    fun send_exception_becomes_retained_failed_terminal_and_next_request_can_start_after_consume() {
        val task = ProductionAndroidAppChatTask(
            ProductionAndroidAppChatExecutor { block -> block() }
        )

        val first = assertIs<ProductionAndroidAppChatTaskRequestResult.Started>(
            task.request("boom", { throw IllegalStateException("hidden") }) { }
        )
        val terminal = assertIs<ProductionAndroidAppChatTaskSnapshot.Completed>(task.snapshot())
        assertIs<ProductionAndroidAppChatTaskOutcome.Failed>(terminal.outcome)
        assertTrue(task.consume(first.requestId))

        assertIs<ProductionAndroidAppChatTaskRequestResult.Started>(
            task.request("retry", { completed("ok") }) { }
        )
    }

    @Test
    fun one_listener_failure_does_not_block_other_recreation_observer() {
        var held: (() -> Unit)? = null
        val delivered = mutableListOf<ProductionAndroidAppChatTaskSnapshot.Completed>()
        val task = ProductionAndroidAppChatTask(
            ProductionAndroidAppChatExecutor { block -> held = block }
        )

        task.request("hello", { completed("reply") }) {
            throw IllegalStateException("stale activity")
        }
        task.observe(delivered::add)

        requireNotNull(held).invoke()

        assertEquals(1, delivered.size)
        assertIs<ProductionAndroidAppChatTaskSnapshot.Completed>(task.snapshot())
    }

    @Test
    fun blank_request_is_rejected_before_executor_or_send() {
        var executorCalls = 0
        val task = ProductionAndroidAppChatTask(
            ProductionAndroidAppChatExecutor { executorCalls += 1 }
        )

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            task.request("   ", { completed("never") }) { }
        }

        assertEquals(0, executorCalls)
        assertIs<ProductionAndroidAppChatTaskSnapshot.Idle>(task.snapshot())
    }

    private fun completed(reply: String): ProductChatResult =
        ProductChatResult.Completed(
            reply = reply,
            streamedChunkCount = 0,
            streamedCharacterCount = 0
        )
}
