package pro.liliya.app

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiliyaProvisioningActivityDeploymentProfileRequiredInstrumentedTest {
    @Test
    fun activation_without_explicit_deployment_profile_fails_closed_before_network_or_runtime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as LiliyaApplication
        ProductionAndroidActivationProfileSourceOwner.clearForTests()

        var launched: LiliyaProvisioningActivity? = null
        try {
            launched = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, LiliyaProvisioningActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ) as LiliyaProvisioningActivity
            instrumentation.waitForIdleSync()

            instrumentation.runOnMainSync {
                val code = findEditText(launched.window.decorView)
                code.setText("LAC1.test-only")
                findButton(launched.window.decorView, "Активировать").performClick()
            }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var profileRequired = false
            while (System.nanoTime() < deadline && !profileRequired) {
                instrumentation.waitForIdleSync()
                profileRequired = containsExactText(
                    launched.window.decorView,
                    "Профиль активации не настроен"
                )
                if (!profileRequired) Thread.sleep(50)
            }

            assertTrue(profileRequired)
            assertFalse(launched.isFinishing)
        } finally {
            ProductionAndroidActivationProfileSourceOwner.clearForTests()
            launched?.let { activity ->
                instrumentation.runOnMainSync {
                    if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
                }
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun findEditText(view: View): EditText {
        if (view is EditText) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                runCatching { return findEditText(view.getChildAt(index)) }
            }
        }
        error("activation code input not found")
    }

    private fun findButton(view: View, text: String): Button {
        if (view is Button && view.text?.toString() == text) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                runCatching { return findButton(view.getChildAt(index), text) }
            }
        }
        error("button not found: $text")
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
