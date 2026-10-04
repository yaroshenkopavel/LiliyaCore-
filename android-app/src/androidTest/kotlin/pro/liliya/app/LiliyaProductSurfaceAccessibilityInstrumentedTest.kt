package pro.liliya.app

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiliyaProductSurfaceAccessibilityInstrumentedTest {
    @Test
    fun launcher_and_chat_controls_expose_accessible_labels_and_enabled_state() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        val launcher = PhysicalInstrumentedActivityLaunch.launchProvisioningActivity(
            instrumentation
        )
        try {
            instrumentation.runOnMainSync {
                val root = launcher.window.decorView
                val activation = assertNotNull(
                    findEditTextByHint(root, "Код активации или замены устройства")
                )
                val activate = assertNotNull(findButtonByText(root, "Активировать"))
                val status = assertNotNull(findNonBlankStatus(root, "Liliya — активация"))

                assertEditTextAccessibility(
                    activation,
                    expectedHint = "Код активации или замены устройства"
                )
                assertButtonAccessibility(activate, expectedText = "Активировать")
                assertTextAccessibility(status)
            }
        } finally {
            finish(instrumentation, launcher)
        }

        val runtime = PhysicalInstrumentedActivityLaunch.launchLiliyaActivity(
            instrumentation
        )
        try {
            instrumentation.runOnMainSync {
                val root = runtime.window.decorView
                val input = assertNotNull(findEditTextByHint(root, "Сообщение"))
                val send = assertNotNull(findButtonByText(root, "Отправить"))
                val status = assertNotNull(findNonBlankStatus(root, "Liliya"))

                assertEditTextAccessibility(input, expectedHint = "Сообщение")
                assertButtonAccessibility(send, expectedText = "Отправить")
                assertTextAccessibility(status)

                assertTrue(!input.isEnabled)
                assertTrue(!send.isEnabled)
            }
        } finally {
            finish(instrumentation, runtime)
        }
    }

    private fun assertEditTextAccessibility(
        view: EditText,
        expectedHint: String
    ) {
        assertTrue(view.isShown)
        assertEquals("android.widget.EditText", view.accessibilityClassName?.toString())
        assertEquals(expectedHint, view.hint?.toString())
        assertTrue(view.isImportantForAccessibility)
    }

    private fun assertButtonAccessibility(
        view: Button,
        expectedText: String
    ) {
        assertTrue(view.isShown)
        assertEquals("android.widget.Button", view.accessibilityClassName?.toString())
        assertEquals(expectedText, view.text?.toString())
        assertTrue(view.isImportantForAccessibility)
    }

    private fun assertTextAccessibility(view: TextView) {
        assertTrue(view.isShown)
        assertEquals("android.widget.TextView", view.accessibilityClassName?.toString())
        assertTrue(!view.text.isNullOrBlank())
        assertTrue(view.isImportantForAccessibility)
    }

    private fun findEditTextByHint(view: View, expected: String): EditText? {
        if (view is EditText && view.hint?.toString() == expected) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = findEditTextByHint(view.getChildAt(index), expected)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findButtonByText(view: View, expected: String): Button? {
        if (view is Button && view.text?.toString() == expected) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = findButtonByText(view.getChildAt(index), expected)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findNonBlankStatus(view: View, excluded: String): TextView? {
        if (
            view is TextView &&
            view !is Button &&
            view.text?.toString()?.isNotBlank() == true &&
            view.text?.toString() != excluded
        ) {
            return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = findNonBlankStatus(view.getChildAt(index), excluded)
                if (found != null) return found
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
}
