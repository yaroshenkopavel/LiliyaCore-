package pro.liliya.app

import android.app.Activity
import android.os.Bundle
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
class LiliyaActivitySavedStateResumeInstrumentedTest {
    @Test
    fun terminal_after_saved_state_is_consumed_on_resume_without_recreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as LiliyaApplication

        val heldStartup = AtomicReference<(() -> Unit)?>(null)
        val previousStartupTask = application.replaceStartupTaskForTests(
            ProductionAndroidAppStartupTask(
                executor = ProductionAndroidAppStartupExecutor { block ->
                    heldStartup.compareAndSet(null, block)
                }
            )
        )

        val heldChat = AtomicReference<(() -> Unit)?>(null)
        val chatTask = ProductionAndroidAppChatTask(
            executor = ProductionAndroidAppChatExecutor { block ->
                assertTrue(heldChat.compareAndSet(null, block))
            }
        )
        chatTask.request(
            message = "Saved-state resume request",
            send = {
                ProductChatResult.Completed(
                    reply = "Saved-state resume reply",
                    streamedChunkCount = 0,
                    streamedCharacterCount = 0
                )
            },
            listener = { }
        )
        val previousChatTask = application.replaceChatTaskForTests(chatTask)

        var launched: LiliyaActivity? = null
        try {
            launched = PhysicalInstrumentedActivityLaunch.launchLiliyaActivity(instrumentation)
            instrumentation.waitForIdleSync()

            assertTrue(hasTextContaining(instrumentation, launched, "Saved-state resume request"))
            assertTrue(hasExactText(instrumentation, launched, "Думаю…"))

            val savedState = Bundle()
            instrumentation.runOnMainSync {
                instrumentation.callActivityOnSaveInstanceState(launched, savedState)
            }

            heldChat.getAndSet(null)?.invoke() ?: error("chat block missing")
            instrumentation.waitForIdleSync()

            assertIs<ProductionAndroidAppChatTaskSnapshot.Completed>(chatTask.snapshot())
            assertTrue(!hasTextContaining(instrumentation, launched, "Saved-state resume reply"))

            instrumentation.runOnMainSync {
                instrumentation.callActivityOnResume(launched)
            }
            instrumentation.waitForIdleSync()

            assertIs<ProductionAndroidAppChatTaskSnapshot.Idle>(chatTask.snapshot())
            assertTrue(hasTextContaining(instrumentation, launched, "Saved-state resume reply"))
        } finally {
            heldStartup.set(null)
            heldChat.set(null)
            application.replaceChatTaskForTests(previousChatTask)
            application.replaceStartupTaskForTests(previousStartupTask)
            launched?.let { finish(instrumentation, it) }
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
            if ((exact && text == expected) || (!exact && text.contains(expected))) return true
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (containsText(view.getChildAt(index), expected, exact)) return true
            }
        }
        return false
    }

    private fun finish(
        instrumentation: android.app.Instrumentation,
        activity: Activity
    ) {
        instrumentation.runOnMainSync {
            if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
        }
    }
}
