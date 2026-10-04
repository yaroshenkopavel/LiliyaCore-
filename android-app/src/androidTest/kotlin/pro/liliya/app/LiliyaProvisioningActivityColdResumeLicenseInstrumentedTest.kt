package pro.liliya.app

import android.app.Instrumentation
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseCanonicalPayload
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseSignature
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseVersion

@RunWith(AndroidJUnit4::class)
class LiliyaProvisioningActivityColdResumeLicenseInstrumentedTest {
    @Test
    fun durable_license_blocks_reactivation_and_tamper_fails_closed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = ProductionAndroidActivatedLicenseEncryptedStore.create(context)

        assertIs<ProductionAndroidActivatedLicenseLoadResult.Missing>(store.load())
        try {
            assertIs<ProductionAndroidActivatedLicenseStoreResult.Stored>(
                store.store(envelope())
            )
            assertColdResumeUi(
                instrumentation = instrumentation,
                expectedStatus =
                    "Лицензия сохранена. Требуется восстановление профиля продукта"
            )

            val published = store.publishedFileForTest()
            val bytes = published.readBytes()
            try {
                assertTrue(bytes.size > 32)
                bytes[bytes.lastIndex] =
                    (bytes.last().toInt() xor 0x01).toByte()
                published.writeBytes(bytes)
            } finally {
                bytes.fill(0)
            }

            assertIs<ProductionAndroidActivatedLicenseLoadResult.Rejected>(
                store.load()
            )
            assertColdResumeUi(
                instrumentation = instrumentation,
                expectedStatus = "Сохранённое состояние лицензии повреждено"
            )
        } finally {
            store.deleteForTests()
        }
    }

    private fun assertColdResumeUi(
        instrumentation: Instrumentation,
        expectedStatus: String
    ) {
        val monitor = instrumentation.addMonitor(
            LiliyaProvisioningActivity::class.java.name,
            null,
            false
        )
        val launch = instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${instrumentation.targetContext.packageName}/.LiliyaProvisioningActivity"
        )
        launch.close()

        val activity = monitor.waitForActivityWithTimeout(15_000L)
            as? LiliyaProvisioningActivity
            ?: error("cold-resume provisioning activity did not launch")
        try {
            instrumentation.runOnMainSync {
                val root = activity.window.decorView
                assertNotNull(findExactText(root, expectedStatus))
                val activation = assertNotNull(
                    findEditTextByHint(
                        root,
                        "Код активации или замены устройства"
                    )
                )
                val activate = assertNotNull(
                    findButtonByText(root, "Активировать")
                )
                assertEquals(View.GONE, activation.visibility)
                assertEquals(View.GONE, activate.visibility)
                assertTrue(!activation.isEnabled)
                assertTrue(!activate.isEnabled)
                assertTrue(
                    (activity.application as LiliyaApplication)
                        .runtimeOwner.state() != ProductionAndroidAppRuntimeState.READY
                )
            }
        } finally {
            instrumentation.runOnMainSync {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.finish()
                }
            }
        }
    }

    private fun envelope() = LicenseSignedEnvelope(
        schemaVersion = LicenseVersion(1),
        algorithm = LicenseAlgorithm("ECDSA-P256-SHA256"),
        signingKeyId = LicenseKeyId("cold-resume-ui-test-key"),
        payload = LicenseCanonicalPayload.of(byteArrayOf(1, 2, 3)),
        signature = LicenseSignature.of(ByteArray(64) { 7 })
    )

    private fun findExactText(view: View, expected: String): TextView? {
        if (view is TextView && view.text?.toString() == expected) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findExactText(view.getChildAt(index), expected)?.let { return it }
            }
        }
        return null
    }

    private fun findEditTextByHint(view: View, expected: String): EditText? {
        if (view is EditText && view.hint?.toString() == expected) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findEditTextByHint(view.getChildAt(index), expected)?.let {
                    return it
                }
            }
        }
        return null
    }

    private fun findButtonByText(view: View, expected: String): Button? {
        if (view is Button && view.text?.toString() == expected) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findButtonByText(view.getChildAt(index), expected)?.let {
                    return it
                }
            }
        }
        return null
    }
}
