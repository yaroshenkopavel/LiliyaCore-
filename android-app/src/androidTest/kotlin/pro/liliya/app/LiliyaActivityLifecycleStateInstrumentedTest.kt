package pro.liliya.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
class LiliyaActivityLifecycleStateInstrumentedTest {
    @Test
    fun in_flight_chat_recreation_reattaches_without_duplicate_inference() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as LiliyaApplication

        val heldStartup = AtomicReference<(() -> Unit)?>(null)
        val startupTask = ProductionAndroidAppStartupTask(
            executor = ProductionAndroidAppStartupExecutor { block ->
                heldStartup.compareAndSet(null, block)
            }
        )
        val previousStartupTask = application.replaceStartupTaskForTests(startupTask)

        val executorCalls = AtomicInteger(0)
        val heldChat = AtomicReference<(() -> Unit)?>(null)
        val chatTask = ProductionAndroidAppChatTask(
            executor = ProductionAndroidAppChatExecutor { block ->
                executorCalls.incrementAndGet()
                assertTrue(heldChat.compareAndSet(null, block))
            }
        )
        val started = assertIs<ProductionAndroidAppChatTaskRequestResult.Started>(
            chatTask.request(
                message = "Lifecycle in-flight request",
                send = {
                    ProductChatResult.Completed(
                        reply = "Lifecycle reply",
                        streamedChunkCount = 0,
                        streamedCharacterCount = 0
                    )
                },
                listener = { }
            )
        )
        val previousChatTask = application.replaceChatTaskForTests(chatTask)

        val secondCreated = CountDownLatch(1)
        val latestActivity = AtomicReference<LiliyaActivity?>(null)
        val creations = AtomicInteger(0)
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is LiliyaActivity) {
                    latestActivity.set(activity)
                    if (creations.incrementAndGet() >= 2) secondCreated.countDown()
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
            val launched = PhysicalInstrumentedActivityLaunch.launchLiliyaActivity(instrumentation)
            firstForCleanup = launched
            instrumentation.waitForIdleSync()

            assertEquals(1, executorCalls.get())
            assertInFlightUi(instrumentation, launched, "Lifecycle in-flight request")

            instrumentation.runOnMainSync { launched.recreate() }
            assertTrue(secondCreated.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()

            val recreated = assertNotNull(latestActivity.get())
            recreatedForCleanup = recreated
            assertTrue(recreated !== launched)
            assertEquals(1, executorCalls.get())
            val snapshot = assertIs<ProductionAndroidAppChatTaskSnapshot.InFlight>(chatTask.snapshot())
            assertEquals(started.requestId, snapshot.requestId)
            assertInFlightUi(instrumentation, recreated, "Lifecycle in-flight request")

            heldChat.getAndSet(null)?.invoke() ?: error("chat block missing")
            instrumentation.waitForIdleSync()

            assertIs<ProductionAndroidAppChatTaskSnapshot.Idle>(chatTask.snapshot())
            assertTrue(hasTextContaining(instrumentation, recreated, "Lifecycle reply"))
        } finally {
            heldStartup.set(null)
            heldChat.set(null)
            application.unregisterActivityLifecycleCallbacks(callbacks)
            application.replaceChatTaskForTests(previousChatTask)
            application.replaceStartupTaskForTests(previousStartupTask)
            recreatedForCleanup?.let { finish(instrumentation, it) }
            firstForCleanup?.let { finish(instrumentation, it) }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun draft_is_restored_after_activity_recreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as LiliyaApplication

        val heldStartup = AtomicReference<(() -> Unit)?>(null)
        val startupTask = ProductionAndroidAppStartupTask(
            executor = ProductionAndroidAppStartupExecutor { block ->
                heldStartup.compareAndSet(null, block)
            }
        )
        val previousStartupTask = application.replaceStartupTaskForTests(startupTask)

        val secondCreated = CountDownLatch(1)
        val latestActivity = AtomicReference<LiliyaActivity?>(null)
        val creations = AtomicInteger(0)
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is LiliyaActivity) {
                    latestActivity.set(activity)
                    if (creations.incrementAndGet() >= 2) secondCreated.countDown()
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
            val launched = PhysicalInstrumentedActivityLaunch.launchLiliyaActivity(instrumentation)
            firstForCleanup = launched
            instrumentation.waitForIdleSync()

            instrumentation.runOnMainSync {
                val input = assertNotNull(findEditTextByHint(launched.window.decorView, "Сообщение"))
                input.setText(DRAFT)
                input.setSelection(DRAFT.length)
            }

            instrumentation.runOnMainSync { launched.recreate() }
            assertTrue(secondCreated.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()

            val recreated = assertNotNull(latestActivity.get())
            recreatedForCleanup = recreated
            assertTrue(recreated !== launched)
            instrumentation.runOnMainSync {
                val input = assertNotNull(findEditTextByHint(recreated.window.decorView, "Сообщение"))
                assertEquals(DRAFT, input.text?.toString())
            }
        } finally {
            heldStartup.set(null)
            application.unregisterActivityLifecycleCallbacks(callbacks)
            application.replaceStartupTaskForTests(previousStartupTask)
            recreatedForCleanup?.let { finish(instrumentation, it) }
            firstForCleanup?.let { finish(instrumentation, it) }
            instrumentation.waitForIdleSync()
        }
    }

    private fun assertInFlightUi(
        instrumentation: android.app.Instrumentation,
        activity: LiliyaActivity,
        expectedMessage: String
    ) {
        instrumentation.runOnMainSync {
            val root = activity.window.decorView
            val input = assertNotNull(findEditTextByHint(root, "Сообщение"))
            val send = assertNotNull(findButtonByText(root, "Отправить"))
            assertEquals(expectedMessage, input.text?.toString())
            assertTrue(!input.isEnabled)
            assertTrue(!send.isEnabled)
            assertTrue(containsText(root, "Думаю…", exact = true))
        }
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

    private fun findEditTextByHint(view: View, expected: String): EditText? {
        if (view is EditText && view.hint?.toString() == expected) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findEditTextByHint(view.getChildAt(index), expected)?.let { return it }
            }
        }
        return null
    }

    private fun findButtonByText(view: View, expected: String): Button? {
        if (view is Button && view.text?.toString() == expected) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findButtonByText(view.getChildAt(index), expected)?.let { return it }
            }
        }
        return null
    }

    private fun finish(
        instrumentation: android.app.Instrumentation,
        activity: Activity
    ) {
        instrumentation.runOnMainSync {
            if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
        }
    }

    private companion object {
        const val DRAFT = "Черновик после recreation"
    }
}
