package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URL
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingProvider
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingResult
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseDeviceBindingReferenceFactory
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseTrustedVerificationKey
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.license.LicenseVerifier
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.licensetransport.DeviceRebindHttpClient
import pro.liliya.core.licensetransport.DeviceRebindHttpRequest
import pro.liliya.core.licensetransport.DeviceRebindHttpResult
import pro.liliya.core.licensetransport.LicenseHttpTlsTrust
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig

@RunWith(AndroidJUnit4::class)
class PhysicalLaptopDeviceRebindInstrumentedTest {
    @Test
    fun finite_ldr2_rebinds_new_installation_to_verified_unlimited_offline_license() {
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
        val expectedBinding = LicenseDeviceBindingReferenceFactory.create(
            installationId = binding.installationId,
            deviceKeyFingerprint = binding.deviceKeyFingerprint
        )

        val codeFile = File(context.filesDir, REBIND_CODE_FILE)
        assertTrue(codeFile.isFile)
        val rebindCode = codeFile.readText(Charsets.UTF_8).trim()
        assertTrue(rebindCode.isNotBlank())
        assertTrue(codeFile.delete())
        assertTrue(!codeFile.exists())

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
                rebindCode = rebindCode,
                attemptId = ProductionAndroidDeviceRebindAttemptIdentity.loadOrCreate(context),
                installationId = binding.installationId,
                deviceKeyFingerprint = binding.deviceKeyFingerprint
            )
        )

        val rebound = assertIs<DeviceRebindHttpResult.Rebound>(result)
        val publicKey = Base64.getDecoder().decode(
            required(args.getString(ARG_ENTITLEMENT_KEY_DER_BASE64))
        )
        val trusted = try {
            LicenseTrustedVerificationKey.of(
                keyId = rebound.license.signingKeyId,
                algorithm = LicenseAlgorithm(ALGORITHM),
                material = publicKey
            )
        } finally {
            publicKey.fill(0)
        }

        val verification = LicenseVerifier(
            supportedSchemaVersion = LicenseVersion(1),
            supportedAlgorithms = setOf(LicenseAlgorithm(ALGORITHM)),
            trustedKeys = LicenseTrustedKeyResolver { requested: LicenseKeyId ->
                trusted.takeIf { it.keyId == requested }
            },
            signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
        ).verify(rebound.license)

        val verified = assertIs<LicenseVerificationResult.Verified>(verification)
        assertEquals(LicenseProductId(required(args.getString(ARG_PRODUCT))), verified.entitlement.productId)
        assertEquals(rebound.subject, verified.entitlement.subject.value)
        assertEquals(expectedBinding, assertNotNull(verified.entitlement.deviceBindingReference))
        assertNull(verified.entitlement.expiresAt)
        assertNull(verified.entitlement.offlineLeaseUntil)
        assertTrue(verified.entitlement.features.isNotEmpty())
    }

    private fun required(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: error("missing physical rebind acceptance argument")

    private companion object {
        const val ARG_ENDPOINT = "rebindEndpoint"
        const val ARG_CA_BASE64 = "rebindCaBase64"
        const val ARG_ENTITLEMENT_KEY_DER_BASE64 = "rebindLicenseKeyDerBase64"
        const val ARG_PRODUCT = "rebindProductId"
        const val REBIND_CODE_FILE = "physical-device-rebind-code.once"
        const val ALGORITHM = "ECDSA-P256-SHA256"
    }
}
