package pro.liliya.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical-acceptance-only bridge through the real Product Auth provisioning gate.
 *
 * This test owns a temporary LPAUTH1 fixture and installs only an explicit test first-run
 * configuration marker. No production secret, deployment profile, trust key, Authority grant, or
 * release bypass is introduced. The separate First Working Liliya cold-start contract remains the
 * authoritative proof for real runtime READY and the first chat turn.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalProductAuthProvisioningToChatInstrumentedTest {
    @Test
    fun temporary_lpauth1_fixture_unlocks_provisioning_and_opens_real_chat_activity() {
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

            assertTrue(
                application.configureFirstRunAcquisition(
                    ProductionAndroidFirstRunConfiguration(
                        licenseAcquisition = ProductionAndroidFirstRunLicenseAcquisitionPort {
                            error("physical provisioning UI fixture must not acquire a License")
                        },
                        productInput = ProductionAndroidFirstRunProductInputPort { _, _ ->
                            error("physical provisioning UI fixture must not assemble product input")
                        }
                    )
                )
            )

            instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, LiliyaProvisioningActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            assertTrue(
                openedChat.await(10, TimeUnit.SECONDS),
                "Product Auth provisioning did not open LiliyaActivity"
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
