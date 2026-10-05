package pro.liliya.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhysicalProductionColdResumeFailClosedInstrumentedTest {
    @Test
    fun cold_process_restores_only_durable_state_and_requires_explicit_product_profile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as LiliyaApplication

        assertNull(ProductionAndroidRuntimeStartupInputConfiguration.current())
        assertNull(ProductionAndroidFirstRunConfigurationOwner.current())
        assertNull(ProductionAndroidFirstRunProductProfileSourceOwner.current())
        assertNull(ProductionAndroidActivationProfileSourceOwner.current())
        assertNull(ProductionAndroidLicenseServiceSecuritySyncProfileSourceOwner.current())
        assertIs<ProductionAndroidAppChatTaskSnapshot.Idle>(
            application.observeApplicationChat { }
        )
        assertEquals(
            ProductionAndroidAppRuntimeState.CONFIGURATION_REQUIRED,
            application.runtimeOwner.state()
        )

        val durableProductAuth = ProductionAndroidProductAuthEncryptedStore.create(application)
        val secret = durableProductAuth.openSecret()
        try {
            assertTrue(secret.isNotEmpty())
        } finally {
            secret.fill(0)
        }
        val activatedLicenseState = application.activatedLicenseState()
        val expectedLauncherStatus = when (activatedLicenseState) {
            ProductionAndroidActivatedLicenseState.MISSING ->
                "Введите код активации"
            ProductionAndroidActivatedLicenseState.AVAILABLE ->
                when (ProductionAndroidOfflineResumeMaterialLoader.load(application)) {
                    is ProductionAndroidOfflineResumeMaterialLoadResult.Ready ->
                        "Лицензия сохранена. Требуется восстановление профиля продукта"
                    is ProductionAndroidOfflineResumeMaterialLoadResult.Rejected ->
                        "Сохранённое состояние запуска недоступно"
                }
            ProductionAndroidActivatedLicenseState.REJECTED ->
                "Сохранённое состояние лицензии повреждено"
        }

        val runtimeCreated = AtomicBoolean(false)
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is LiliyaActivity) runtimeCreated.set(true)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

        application.registerActivityLifecycleCallbacks(callbacks)
        var launcher: LiliyaProvisioningActivity? = null
        try {
            launcher = PhysicalInstrumentedActivityLaunch.launchProvisioningActivity(
                instrumentation,
                timeoutMillis = 30_000L
            )
            instrumentation.waitForIdleSync()

            assertTrue(
                hasExactText(
                    instrumentation,
                    launcher,
                    expectedLauncherStatus
                )
            )
            assertTrue(!runtimeCreated.get())
            assertNull(ProductionAndroidRuntimeStartupInputConfiguration.current())
            assertNull(ProductionAndroidFirstRunConfigurationOwner.current())
            assertIs<ProductionAndroidAppChatTaskSnapshot.Idle>(
                application.observeApplicationChat { }
            )
            assertEquals(
                ProductionAndroidAppRuntimeState.CONFIGURATION_REQUIRED,
                application.runtimeOwner.state()
            )

            instrumentation.sendStatus(2, Bundle().apply {
                putString("coldResume.launcher", "PROVISIONING")
                putString("coldResume.productAuthDurable", "true")
                putString("coldResume.activatedLicenseState", activatedLicenseState.name)
                putString("coldResume.runtimeStartupProcessLocal", "absent")
                putString("coldResume.productProfileProcessLocal", "absent")
                putString("coldResume.chatReplay", "absent")
                putString("coldResume.runtimeState", "CONFIGURATION_REQUIRED")
                putString("coldResume.failClosed", "true")
            })
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            launcher?.let { activity ->
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
        var found = false
        instrumentation.runOnMainSync {
            found = containsExactText(activity.window.decorView, expected)
        }
        return found
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
