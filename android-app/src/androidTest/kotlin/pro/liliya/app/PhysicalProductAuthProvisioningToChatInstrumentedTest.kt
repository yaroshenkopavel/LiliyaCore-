package pro.liliya.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression guard for the legacy Product Auth provisioning boundary after Activation Code
 * bootstrap became the fresh-install user path.
 *
 * Product Auth may still be provisioned for protected post-activation licensing requests, but
 * possession of Product Auth alone must never bypass Activation Code redemption or open runtime.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalProductAuthProvisioningToChatInstrumentedTest {
    @Test
    fun temporary_lpauth1_fixture_does_not_bypass_activation_or_open_chat_activity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as LiliyaApplication
        val store = ProductionAndroidProductAuthEncryptedStore.create(application)
        val artifact = File(application.cacheDir, "physical-acceptance-product-auth.lpauth1")
        val secret = "physical-arm64-first-working-liliya-fixture".encodeToByteArray()
        val openedChat = CountDownLatch(1)
        val chatActivity = AtomicReference<LiliyaActivity?>(null)

        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is LiliyaActivity) {
                    chatActivity.set(activity)
                    openedChat.countDown()
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

        store.deleteForTests()
        ProductionAndroidFirstRunConfigurationOwner.clearForTests()
        artifact.delete()
        application.registerActivityLifecycleCallbacks(callbacks)

        try {
            FileOutputStream(artifact, false).use { output ->
                output.write("LPAUTH1\n".encodeToByteArray())
                output.write(secret)
                output.flush()
                output.fd.sync()
            }

            val imported = ProductionAndroidProductAuthCredentialImport.import(
                openInput = { FileInputStream(artifact) },
                provision = store::provision
            )
            assertTrue(imported === ProductionAndroidProductAuthImportResult.Imported)

            val opened = store.openSecret()
            try {
                assertContentEquals(secret, opened)
            } finally {
                opened.fill(0)
            }

            val launchOutput = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "am start -n pro.liliya.app/.LiliyaProvisioningActivity"
                )
            ).bufferedReader().use { reader -> reader.readText() }
            assertTrue(
                !launchOutput.contains("Error:"),
                "Product Auth provisioning launch failed: ${launchOutput.trim()}"
            )
            assertFalse(
                openedChat.await(1, TimeUnit.SECONDS),
                "Product Auth alone must not bypass Activation Code bootstrap"
            )
            instrumentation.waitForIdleSync()
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            chatActivity.get()?.let { activity ->
                instrumentation.runOnMainSync {
                    if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
                }
            }
            ProductionAndroidFirstRunConfigurationOwner.clearForTests()
            store.deleteForTests()
            artifact.delete()
            secret.fill(0)
            instrumentation.waitForIdleSync()
        }
    }
}
