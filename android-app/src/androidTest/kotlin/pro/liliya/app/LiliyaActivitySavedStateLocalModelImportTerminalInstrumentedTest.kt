package pro.liliya.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiliyaActivitySavedStateLocalModelImportTerminalInstrumentedTest {
    @Test
    fun terminal_after_saved_state_is_retained_until_recreated_activity_consumes_it() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as LiliyaApplication
        ProductionAndroidLocalModelSelection.clearForTests()

        val heldStartup = AtomicReference<(() -> Unit)?>(null)
        val startupTask = ProductionAndroidAppStartupTask(
            executor = ProductionAndroidAppStartupExecutor { block ->
                assertTrue(heldStartup.compareAndSet(null, block))
            }
        )
        val previousStartupTask = application.replaceStartupTaskForTests(startupTask)

        val heldImport = AtomicReference<(() -> Unit)?>(null)
        val importTask = ProductionAndroidLocalModelImportTask(
            ProductionAndroidLocalModelImportExecutor { block ->
                assertTrue(heldImport.compareAndSet(null, block))
            }
        )
        assertIs<ProductionAndroidLocalModelImportTaskRequestResult.Started>(
            importTask.request(
                importModel = { ProductionAndroidLocalModelSelectionResult.EmptyDocument },
                listener = { }
            )
        )
        val previousImportTask = application.replaceLocalModelImportTaskForTests(importTask)

        val firstActivity = AtomicReference<LiliyaActivity?>(null)
        val importCompletedDuringSave = CountDownLatch(1)
        val recreatedCreated = CountDownLatch(1)
        val latestActivity = AtomicReference<LiliyaActivity?>(null)
        val creations = AtomicInteger(0)
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is LiliyaActivity) {
                    latestActivity.set(activity)
                    if (creations.incrementAndGet() >= 2) recreatedCreated.countDown()
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                if (activity === firstActivity.get()) {
                    heldImport.getAndSet(null)?.invoke() ?: error("import block missing")
                    importCompletedDuringSave.countDown()
                }
            }

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
            firstActivity.set(launched)
            firstForCleanup = launched
            instrumentation.waitForIdleSync()

            assertTrue(hasExactText(instrumentation, launched, "Импорт модели…"))
            assertIs<ProductionAndroidLocalModelImportTaskSnapshot.InFlight>(importTask.observe {})

            instrumentation.runOnMainSync { launched.recreate() }
            assertTrue(importCompletedDuringSave.await(10, TimeUnit.SECONDS))
            assertTrue(recreatedCreated.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()

            val recreated = assertNotNull(latestActivity.get())
            recreatedForCleanup = recreated
            assertTrue(recreated !== launched)
            assertIs<ProductionAndroidLocalModelImportTaskSnapshot.Idle>(importTask.observe { })
            assertTrue(!hasExactText(instrumentation, recreated, "Импорт модели…"))
            assertTrue(
                hasEnabledButtonWithExactText(
                    instrumentation = instrumentation,
                    activity = recreated,
                    expected = "Выбрать локальную модель"
                )
            )
        } finally {
            firstActivity.set(null)
            heldStartup.set(null)
            heldImport.set(null)
            application.unregisterActivityLifecycleCallbacks(callbacks)
            application.replaceLocalModelImportTaskForTests(previousImportTask)
            application.replaceStartupTaskForTests(previousStartupTask)
            ProductionAndroidLocalModelSelection.clearForTests()
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
            result.set(containsExactText(activity.window.decorView, expected))
        }
        return result.get()
    }

    private fun hasEnabledButtonWithExactText(
        instrumentation: android.app.Instrumentation,
        activity: Activity,
        expected: String
    ): Boolean {
        val result = AtomicReference(false)
        instrumentation.runOnMainSync {
            result.set(containsEnabledButtonWithExactText(activity.window.decorView, expected))
        }
        return result.get()
    }

    private fun containsExactText(view: View, expected: String): Boolean {
        if (view is TextView && view.text?.toString() == expected) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (containsExactText(view.getChildAt(index), expected)) return true
            }
        }
        return false
    }

    private fun containsEnabledButtonWithExactText(view: View, expected: String): Boolean {
        if (
            view is Button &&
            view.visibility == View.VISIBLE &&
            view.isEnabled &&
            view.text?.toString() == expected
        ) {
            return true
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (containsEnabledButtonWithExactText(view.getChildAt(index), expected)) return true
            }
        }
        return false
    }
}
