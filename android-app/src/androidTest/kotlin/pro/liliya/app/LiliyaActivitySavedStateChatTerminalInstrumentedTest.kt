package pro.liliya.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.runtime.ProductChatResult

@RunWith(AndroidJUnit4::class)
class LiliyaActivitySavedStateChatTerminalInstrumentedTest {
    @Test
    fun terminal_after_saved_state_is_retained_until_recreated_activity_consumes_it() {
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
                message = "Saved-state request",
                send = {
                    ProductChatResult.Completed(
                        reply = "Saved-state reply",
                        streamedChunkCount = 0,
                        streamedCharacterCount = 0
                    )
                },
                listener = { }
            )
        )
        val previousChatTask = application.replaceChatTaskForTests(chatTask)

        val recreatedCreated = CountDownLatch(1)
        val latestActivity = AtomicReference<LiliyaActivity?>(null)
        val creations = AtomicInteger(0)
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is LiliyaActivity) {
                    latestActivity.set(activity)
                    if (creations.incrementAndGet() >= 2) {
                        recreatedCreated.countDown()
                    }
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

        application.registerActivityLifecycleCallbacks(callbacks)
        var firstForCleanup: LiliyaActivity? = null
        var recreatedForCleanup: LiliyaActivity? = null
        try {
            val launched = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, LiliyaActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ) as LiliyaActivity
            firstForCleanup = launched
            instrumentation.waitForIdleSync()

            assertTrue(hasTextContaining(instrumentation, launched, "Saved-state request"))
            assertTrue(hasExactText(instrumentation, launched, "Думаю…"))
            assertIs<ProductionAndroidAppChatTaskSnapshot.InFlight>(chatTask.snapshot())

            val savedState = Bundle()
            instrumentation.runOnMainSync {
                instrumentation.callActivityOnSaveInstanceState(launched, savedState)
            }

            heldChat.getAndSet(null)?.invoke() ?: error("chat block missing")
            instrumentation.waitForIdleSync()

            val retained = assertIs<ProductionAndroidAppChatTaskSnapshot.Completed>(chatTask.snapshot())
            assertEquals(started.requestId, retained.requestId)
            assertTrue(!hasTextContaining(instrumentation, launched, "Saved-state reply"))

            instrumentation.runOnMainSync { launched.recreate() }
            assertTrue(recreatedCreated.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()

            val recreated = assertNotNull(latestActivity.get())
            recreatedForCleanup = recreated
            assertTrue(recreated !== launched)
            assertIs<ProductionAndroidAppChatTaskSnapshot.Idle>(chatTask.snapshot())
            assertTrue(hasTextContaining(instrumentation, recreated, "Saved-state reply"))
        } finally {
            heldStartup.set(null)
            heldChat.set(null)
            application.unregisterActivityLifecycleCallbacks(callbacks)
            application.replaceChatTaskForTests(previousChatTask)
            application.replaceStartupTaskForTests(previousStartupTask)
            recreatedForCleanup?.let { activity ->
                instrumentation.runOnMainSync {
                    if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
                }
            }
            firstForCleanup?.let { activity ->
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
