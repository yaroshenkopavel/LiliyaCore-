package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URL
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingProvider
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingResult
import pro.liliya.core.licensetransport.DeviceRebindHttpClient
import pro.liliya.core.licensetransport.DeviceRebindHttpRequest
import pro.liliya.core.licensetransport.DeviceRebindHttpResult
import pro.liliya.core.licensetransport.LicenseHttpTlsTrust
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig

@RunWith(AndroidJUnit4::class)
class PhysicalLaptopStaleLdr2InstrumentedTest {
    @Test
    fun stale_epoch_ldr2_is_rejected_as_replacement_state_changed() {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext
        val endpoint = URL(required(args.getString(ARG_ENDPOINT)))
        assertEquals("https", endpoint.protocol)

        val caBytes = Base64.getDecoder().decode(required(args.getString(ARG_CA_BASE64)))
        val tlsTrust = try { LicenseHttpTlsTrust.ofCertificate(caBytes) }
        finally { caBytes.fill(0) }

        val binding = assertIs<AndroidActivationDeviceBindingResult.Ready>(
            AndroidActivationDeviceBindingProvider(context).loadOrCreate()
        ).binding

        val codeFile = File(context.filesDir, STALE_REBIND_CODE_FILE)
        assertTrue(codeFile.isFile)
        val staleCode = codeFile.readText(Charsets.UTF_8).trim()
        assertTrue(staleCode.isNotBlank())
        assertTrue(codeFile.delete())

        val result = DeviceRebindHttpClient(
            LicenseHttpTransportConfig(
                endpoint = endpoint,
                connectTimeoutMillis = 10_000,
                readTimeoutMillis = 60_000,
                developmentAllowInsecureHttp = false,
                tlsTrust = tlsTrust
            )
        ).rebind(
            DeviceRebindHttpRequest(
                rebindCode = staleCode,
                attemptId = "stale-epoch-physical-" + System.nanoTime(),
                installationId = binding.installationId,
                deviceKeyFingerprint = binding.deviceKeyFingerprint
            )
        )

        val rejected = assertIs<DeviceRebindHttpResult.Rejected>(result)
        assertEquals("REPLACEMENT_STATE_CHANGED", rejected.reason)
        println("LILIYA_STALE_LDR2_RESULT=REPLACEMENT_STATE_CHANGED")
    }

    private fun required(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: error("missing stale LDR2 acceptance argument")

    private companion object {
        const val ARG_ENDPOINT = "rebindEndpoint"
        const val ARG_CA_BASE64 = "rebindCaBase64"
        const val STALE_REBIND_CODE_FILE = "physical-device-rebind-stale.once"
    }
}
