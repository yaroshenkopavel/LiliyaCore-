package pro.liliya.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
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

@RunWith(AndroidJUnit4::class)
class PhysicalProductionOfflineResumeColdLaunchUiInstrumentedTest {
    @Test
    fun cold_launcher_resumes_offline_to_ready_and_completes_new_ui_chat() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application =
            instrumentation.targetContext.applicationContext as LiliyaApplication

        assertEquals(
            ProductionAndroidAppRuntimeState.CONFIGURATION_REQUIRED,
            application.runtimeOwner.state()
        )
        assertIs<ProductionAndroidAppChatTaskSnapshot.Idle>(
            application.observeApplicationChat { }
        )
        assertTrue(!application.hasRuntimeStartupConfiguration())
        assertNotNull(ProductionAndroidLocalModelSelection.current())

        val monitor = instrumentation.addMonitor(
            LiliyaActivity::class.java.name,
            null,
            false
        )
        var runtimeActivity: LiliyaActivity? = null
        try {
            val launch = instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n " +
                    instrumentation.targetContext.packageName +
                    "/.LiliyaProvisioningActivity"
            )
            launch.close()

            runtimeActivity = monitor.waitForActivityWithTimeout(30_000L)
                as? LiliyaActivity
                ?: error("cold offline-resume launcher did not open LiliyaActivity")

            val activity = runtimeActivity
            assertTrue(
                waitForUi(instrumentation, READY_TIMEOUT_MILLIS) {
                    val root = activity.window.decorView
                    containsExactText(root, READY_TEXT) &&
                        findEditTextByHint(root, MESSAGE_HINT)?.isEnabled == true &&
                        findButtonByText(root, SEND_TEXT)?.isEnabled == true
                },
                "cold offline-resume UI did not reach READY"
            )

            val input = assertNotNull(
                findEditTextByHint(activity.window.decorView, MESSAGE_HINT)
            )
            val send = assertNotNull(
                findButtonByText(activity.window.decorView, SEND_TEXT)
            )
            val transcriptBefore = findTextContaining(
                activity.window.decorView,
                UI_CHAT_MESSAGE
            )?.text?.length ?: 0

            instrumentation.runOnMainSync {
                input.setText(UI_CHAT_MESSAGE)
                send.performClick()
            }

            val userOnlyTranscriptChars = assertNotNull(
                findTextContaining(activity.window.decorView, UI_CHAT_MESSAGE)
            ).text?.length ?: 0
            assertTrue(userOnlyTranscriptChars > transcriptBefore)

            assertTrue(
                waitForUi(instrumentation, CHAT_TIMEOUT_MILLIS) {
                    val root = activity.window.decorView
                    val transcript = findTextContaining(root, UI_CHAT_MESSAGE)
                    containsExactText(root, READY_TEXT) &&
                        transcript != null &&
                        (transcript.text?.length ?: 0) > userOnlyTranscriptChars
                },
                "cold offline-resume UI did not render new model response"
            )

            val finalTranscript = assertNotNull(
                findTextContaining(activity.window.decorView, UI_CHAT_MESSAGE)
            )
            assertEquals(
                ProductionAndroidAppRuntimeState.READY,
                application.runtimeOwner.state()
            )
            assertIs<ProductionAndroidAppChatTaskSnapshot.Idle>(
                application.observeApplicationChat { }
            )

            instrumentation.sendStatus(2, Bundle().apply {
                putString("offlineResumeColdLaunch.launcher", "PROVISIONING")
                putString("offlineResumeColdLaunch.runtimeActivity", "LILIYA")
                putString("offlineResumeColdLaunch.activationNetworkSupplied", "false")
                putString("offlineResumeColdLaunch.runtimeState", "READY")
                putString("offlineResumeColdLaunch.chatReplay", "absent")
                putString("offlineResumeColdLaunch.newUiChatCompleted", "true")
                putString(
                    "offlineResumeColdLaunch.transcriptChars",
                    (finalTranscript.text?.length ?: 0).toString()
                )
            })
        } finally {
            runtimeActivity?.let { activity ->
                instrumentation.runOnMainSync {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        activity.finish()
                    }
                }
            }
            instrumentation.removeMonitor(monitor)
            application.runtimeOwner.close()
            instrumentation.waitForIdleSync()
        }
    }

    private fun waitForUi(
        instrumentation: Instrumentation,
        timeoutMillis: Long,
        predicate: () -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            var matched = false
            instrumentation.runOnMainSync {
                matched = predicate()
            }
            if (matched) return true
            Thread.sleep(250L)
        }
        return false
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

    private fun findTextContaining(view: View, expected: String): TextView? {
        if (view is TextView && view.text?.toString()?.contains(expected) == true) {
            return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findTextContaining(view.getChildAt(index), expected)?.let {
                    return it
                }
            }
        }
        return null
    }

    private companion object {
        const val READY_TEXT = "Готова"
        const val MESSAGE_HINT = "Сообщение"
        const val SEND_TEXT = "Отправить"
        const val UI_CHAT_MESSAGE =
            "Hello from cold launcher offline resume /no_think"
        const val READY_TIMEOUT_MILLIS = 180_000L
        const val CHAT_TIMEOUT_MILLIS = 300_000L
    }
}
