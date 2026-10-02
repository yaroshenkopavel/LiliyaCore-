package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingProvider
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingResult

@RunWith(AndroidJUnit4::class)
class PhysicalActivationDeviceBindingInstrumentedTest {
    @Test
    fun production_activation_binding_is_stable_and_keystore_backed() {
        val context = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext

        val provider = AndroidActivationDeviceBindingProvider(context)
        val first = assertIs<AndroidActivationDeviceBindingResult.Ready>(
            provider.loadOrCreate()
        ).binding
        val second = assertIs<AndroidActivationDeviceBindingResult.Ready>(
            provider.loadOrCreate()
        ).binding

        assertTrue(first.installationId.isNotBlank())
        assertTrue(first.deviceKeyFingerprint.isNotBlank())
        assertEquals(first.installationId, second.installationId)
        assertEquals(first.deviceKeyFingerprint, second.deviceKeyFingerprint)
    }
}
