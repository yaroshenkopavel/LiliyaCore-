package pro.liliya.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiliyaProvisioningActivityProductAuthRecreationInstrumentedTest {
    @Test
    fun in_flight_import_survives_recreation_without_second_execution() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as LiliyaApplication
        val store = ProductionAndroidProductAuthEncryptedStore.create(application)
        store.deleteForTests()
        val executorCalls = AtomicInteger(0)
        val heldBlock = AtomicReference<(() -> Unit)?>(null)
        val heldTask = ProductionAndroidProductAuthImportTask(
            ProductionAndroidProductAuthImportExecutor { block ->
                executorCalls.incrementAndGet()
                assertTrue(heldBlock.compareAndSet(null, block))
            }
        )
        val previousTask = application.replaceProductAuthImportTaskForTests(heldTask)
        val secondCreated = CountDownLatch(1)
        val latestActivity = AtomicReference<LiliyaProvisioningActivity?>(null)
        val creations = AtomicInteger(0)
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is LiliyaProvisioningActivity) {
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
        var firstForCleanup: LiliyaProvisioningActivity? = null
        var recreatedForCleanup: LiliyaProvisioningActivity? = null
        try {
            val launched = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, LiliyaProvisioningActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ) as LiliyaProvisioningActivity
            firstForCleanup = launched
            instrumentation.waitForIdleSync()

            instrumentation.runOnMainSync {
                launched.deliverProductAuthSelectionResultForTests(
                    Activity.RESULT_OK,
                    Intent().setData(Uri.parse("content://liliya.test/product-auth.lpauth"))
                )
            }
            instrumentation.waitForIdleSync()

            assertEquals(1, executorCalls.get())
            assertTrue(hasExactText(instrumentation, launched, "Импорт доступа продукта…"))
            assertNotNull(heldBlock.get())

            instrumentation.runOnMainSync { launched.recreate() }
            assertTrue(secondCreated.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            val recreated = assertNotNull(latestActivity.get())
            recreatedForCleanup = recreated
            assertTrue(recreated !== launched)
            assertEquals(1, executorCalls.get())
            assertTrue(hasExactText(instrumentation, recreated, "Импорт доступа продукта…"))
            assertNotNull(heldBlock.get())
        } finally {
            heldBlock.set(null)
            application.unregisterActivityLifecycleCallbacks(callbacks)
            application.replaceProductAuthImportTaskForTests(previousTask)
            store.deleteForTests()
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

    private fun containsExactText(view: View, expected: String): Boolean {
        if (view is TextView && view.text?.toString() == expected) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (containsExactText(view.getChildAt(index), expected)) return true
            }
        }
        return false
    }
}
