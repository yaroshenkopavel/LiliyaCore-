package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import pro.liliya.android.runtime.ProductChatResult

class ProductionAndroidAppChatTaskContractTest {
    @Test
    fun same_message_joins_single_inflight_execution_and_recreated_subscriber_receives_terminal_result() {
        var held: (() -> Unit)? = null
        var executions = 0
        val task = ProductionAndroidAppChatTask(
            ProductionAndroidAppChatExecutor { block ->
                executions += 1
                held = block
            }
        )
        val results = mutableListOf<ProductionAndroidAppChatTaskResult>()

        assertEquals(
            ProductionAndroidAppChatRequestDecision.STARTED,
            task.request("hello", { completed("reply") }) { results += it }
        )
        assertEquals(
            ProductionAndroidAppChatRequestDecision.JOINED,
            task.request("hello", { error("joined request must not execute") }) { results += it }
        )
        assertEquals("hello", task.subscribeCurrent { results += it })
        assertEquals(1, executions)
        assertEquals("hello", task.currentMessage())

        held?.invoke() ?: error("in-flight block missing")

        assertNull(task.currentMessage())
        assertEquals(3, results.size)
        results.forEach { result ->
            val completed = assertIs<ProductionAndroidAppChatTaskResult.Completed>(result)
            assertEquals("reply", assertIs<ProductChatResult.Completed>(completed.result).reply)
        }
    }

    @Test
    fun different_message_is_busy_and_is_not_subscribed_to_existing_request() {
        var held: (() -> Unit)? = null
        var secondCallbackCalled = false
        val task = ProductionAndroidAppChatTask(
            ProductionAndroidAppChatExecutor { block -> held = block }
        )

        assertEquals(
            ProductionAndroidAppChatRequestDecision.STARTED,
            task.request("first", { completed("done") }) { }
        )
        assertEquals(
            ProductionAndroidAppChatRequestDecision.BUSY,
            task.request("second", { error("busy request must not execute") }) {
                secondCallbackCalled = true
            }
        )

        held?.invoke() ?: error("in-flight block missing")
        assertTrue(!secondCallbackCalled)
        assertNull(task.currentMessage())
    }

    @Test
    fun completion_clears_inflight_before_callbacks_so_new_request_can_start() {
        val queued = ArrayDeque<() -> Unit>()
        val task = ProductionAndroidAppChatTask(
            ProductionAndroidAppChatExecutor { block -> queued.addLast(block) }
        )
        var nestedDecision: ProductionAndroidAppChatRequestDecision? = null

        task.request("first", { completed("one") }) {
            assertNull(task.currentMessage())
            nestedDecision = task.request(
                "second",
                { completed("two") }
            ) { }
        }

        queued.removeFirst().invoke()
        assertEquals(ProductionAndroidAppChatRequestDecision.STARTED, nestedDecision)
        assertEquals("second", task.currentMessage())
        queued.removeFirst().invoke()
        assertNull(task.currentMessage())
    }

    @Test
    fun send_exception_fails_terminally_and_next_request_can_start() {
        val queued = ArrayDeque<() -> Unit>()
        val task = ProductionAndroidAppChatTask(
            ProductionAndroidAppChatExecutor { block -> queued.addLast(block) }
        )
        var firstResult: ProductionAndroidAppChatTaskResult? = null

        task.request("first", { error("boom") }) { firstResult = it }
        queued.removeFirst().invoke()

        assertIs<ProductionAndroidAppChatTaskResult.Failed>(firstResult)
        assertNull(task.currentMessage())
        assertEquals(
            ProductionAndroidAppChatRequestDecision.STARTED,
            task.request("second", { completed("ok") }) { }
        )
    }

    @Test
    fun callback_failure_is_isolated_from_other_subscribers() {
        var held: (() -> Unit)? = null
        var delivered = false
        val task = ProductionAndroidAppChatTask(
            ProductionAndroidAppChatExecutor { block -> held = block }
        )

        task.request("hello", { completed("reply") }) {
            error("stale activity callback")
        }
        task.subscribeCurrent { delivered = true }
        held?.invoke() ?: error("in-flight block missing")

        assertTrue(delivered)
        assertNull(task.currentMessage())
    }

    @Test
    fun blank_message_is_rejected_without_execution_or_subscription() {
        var executions = 0
        var callbackCalled = false
        val task = ProductionAndroidAppChatTask(
            ProductionAndroidAppChatExecutor {
                executions += 1
            }
        )

        assertEquals(
            ProductionAndroidAppChatRequestDecision.INVALID,
            task.request("   ", { completed("unused") }) {
                callbackCalled = true
            }
        )
        assertEquals(0, executions)
        assertTrue(!callbackCalled)
        assertNull(task.currentMessage())
        assertNull(task.subscribeCurrent { })
    }

    private fun completed(reply: String): ProductChatResult.Completed =
        ProductChatResult.Completed(
            reply = reply,
            streamedChunkCount = 0,
            streamedCharacterCount = 0
        )
}
