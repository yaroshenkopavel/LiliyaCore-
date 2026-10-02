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
class PhysicalDeviceRebindPreparationInstrumentedTest {
    @Test
    fun replacement_attempt_and_keystore_binding_are_stable_on_physical_device() {
        val context = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext

        val firstAttempt =
            ProductionAndroidDeviceRebindAttemptIdentity.loadOrCreate(context)
        val secondAttempt =
            ProductionAndroidDeviceRebindAttemptIdentity.loadOrCreate(context)

        assertTrue(firstAttempt.startsWith("device-rebind-attempt-v1:"))
        assertEquals(firstAttempt, secondAttempt)

        val provider = AndroidActivationDeviceBindingProvider(context)
        val firstBinding = assertIs<AndroidActivationDeviceBindingResult.Ready>(
            provider.loadOrCreate()
        ).binding
        val secondBinding = assertIs<AndroidActivationDeviceBindingResult.Ready>(
            provider.loadOrCreate()
        ).binding

        assertTrue(firstBinding.installationId.isNotBlank())
        assertTrue(firstBinding.deviceKeyFingerprint.isNotBlank())
        assertEquals(firstBinding.installationId, secondBinding.installationId)
        assertEquals(
            firstBinding.deviceKeyFingerprint,
            secondBinding.deviceKeyFingerprint
        )
    }
}
