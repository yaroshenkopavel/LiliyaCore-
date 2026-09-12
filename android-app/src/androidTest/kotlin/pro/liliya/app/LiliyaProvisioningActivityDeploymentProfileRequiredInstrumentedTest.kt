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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiliyaProvisioningActivityDeploymentProfileRequiredInstrumentedTest {
    @Test
    fun provisioned_credential_without_deployment_profile_fails_closed_before_runtime_host() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as LiliyaApplication
        val store = ProductionAndroidProductAuthEncryptedStore.create(application)
        store.deleteForTests()
        ProductionAndroidFirstRunProductProfileSourceOwner.clearForTests()
        val runtimeOpened = AtomicBoolean(false)
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is LiliyaActivity) runtimeOpened.set(true)
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        var launched: LiliyaProvisioningActivity? = null
        try {
            val secret = "profile-required-product-auth".encodeToByteArray()
            try {
                assertTrue(store.provision(secret) is ProductionAndroidProductAuthProvisionResult.Provisioned)
            } finally {
                secret.fill(0)
            }
            launched = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, LiliyaProvisioningActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ) as LiliyaProvisioningActivity
            instrumentation.waitForIdleSync()

            assertTrue(containsExactText(launched.window.decorView, "Требуется профиль продукта"))
            assertFalse(runtimeOpened.get())
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            ProductionAndroidFirstRunProductProfileSourceOwner.clearForTests()
            store.deleteForTests()
            launched?.let { activity ->
                instrumentation.runOnMainSync {
                    if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
                }
            }
            instrumentation.waitForIdleSync()
        }
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
