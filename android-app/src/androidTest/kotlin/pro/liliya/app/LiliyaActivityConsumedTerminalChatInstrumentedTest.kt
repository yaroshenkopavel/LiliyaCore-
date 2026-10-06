package pro.liliya.app

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.runtime.ProductChatResult

@RunWith(AndroidJUnit4::class)
class LiliyaActivityConsumedTerminalChatInstrumentedTest {
    @Test
    fun matching_terminal_result_renders_even_if_an_earlier_listener_consumed_snapshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as LiliyaApplication

        val heldStartup = AtomicReference<(() -> Unit)?>(null)
        val startupTask = ProductionAndroidAppStartupTask(
            executor = ProductionAndroidAppStartupExecutor { block ->
                assertTrue(heldStartup.compareAndSet(null, block))
            }
        )
        val previousStartupTask = application.replaceStartupTaskForTests(startupTask)

        val heldChat = AtomicReference<(() -> Unit)?>(null)
        val chatTask = ProductionAndroidAppChatTask(
            executor = ProductionAndroidAppChatExecutor { block ->
                assertTrue(heldChat.compareAndSet(null, block))
            }
        )

        val started = assertIs<ProductionAndroidAppChatTaskRequestResult.Started>(
            chatTask.request(
                message = "Consumed-before-UI request",
                send = {
                    ProductChatResult.Completed(
                        reply = "Consumed-before-UI reply",
                        streamedChunkCount = 0,
                        streamedCharacterCount = 0
                    )
                },
                listener = { completed ->
                    // Reproduce the physical failure mode: another observer wins the
                    // application-scope consume race before the visible Activity callback.
                    chatTask.consume(completed.requestId)
                }
            )
        )
        assertTrue(started.requestId > 0L)

        val previousChatTask = application.replaceChatTaskForTests(chatTask)
        var launched: LiliyaActivity? = null

        try {
            launched = PhysicalInstrumentedActivityLaunch.launchLiliyaActivity(instrumentation)
            instrumentation.waitForIdleSync()

            assertTrue(hasTextContaining(instrumentation, launched, "Consumed-before-UI request"))
            assertTrue(hasExactText(instrumentation, launched, "Думаю…"))
            assertIs<ProductionAndroidAppChatTaskSnapshot.InFlight>(chatTask.snapshot())

            heldChat.getAndSet(null)?.invoke() ?: error("chat block missing")
            instrumentation.waitForIdleSync()

            assertIs<ProductionAndroidAppChatTaskSnapshot.Idle>(chatTask.snapshot())
            assertTrue(hasTextContaining(instrumentation, launched, "Consumed-before-UI reply"))
            assertTrue(hasExactText(instrumentation, launched, "Готова"))
        } finally {
            heldStartup.set(null)
            heldChat.set(null)
            application.replaceChatTaskForTests(previousChatTask)
            application.replaceStartupTaskForTests(previousStartupTask)
            launched?.let { activity ->
                instrumentation.runOnMainSync {
                    if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
                }
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun hasExactText(
        instrumentation: android.app.Instrumentation,
        activity: Activity,
        expected: String
    ): Boolean {
        val result = AtomicReference(false)
        instrumentation.runOnMainSync {
            result.set(containsText(activity.window.decorView, expected, exact = true))
        }
        return result.get()
    }

    private fun hasTextContaining(
        instrumentation: android.app.Instrumentation,
        activity: Activity,
        expected: String
    ): Boolean {
        val result = AtomicReference(false)
        instrumentation.runOnMainSync {
            result.set(containsText(activity.window.decorView, expected, exact = false))
        }
        return result.get()
    }

    private fun containsText(view: View, expected: String, exact: Boolean): Boolean {
        if (view is TextView) {
            val text = view.text?.toString().orEmpty()
            if ((exact && text == expected) || (!exact && text.contains(expected))) {
                return true
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (containsText(view.getChildAt(index), expected, exact)) return true
            }
        }
        return false
    }
}
