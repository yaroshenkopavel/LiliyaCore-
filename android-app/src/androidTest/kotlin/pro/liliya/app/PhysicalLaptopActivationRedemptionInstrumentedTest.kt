package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import pro.liliya.core.licensetransport.ActivationRedemptionHttpClient
import pro.liliya.core.licensetransport.ActivationRedemptionHttpRequest
import pro.liliya.core.licensetransport.ActivationRedemptionHttpResult
import pro.liliya.core.licensetransport.LicenseHttpTlsTrust
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig

/**
 * #259 physical fresh-activation licensing acceptance.
 *
 * This test intentionally stops before Authority/Admission/Execution. It proves only:
 * Activation Code -> TLS server -> opaque subject -> one device binding -> signed License ->
 * local signature/device/product/offline-policy verification.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalLaptopActivationRedemptionInstrumentedTest {
    @Test
    fun activation_code_redeems_to_verified_unlimited_offline_device_bound_license() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext.applicationContext

        val endpoint = URL(required(args.getString(ARG_ENDPOINT)))
        assertEquals("https", endpoint.protocol)

        val caBytes = Base64.getDecoder().decode(required(args.getString(ARG_CA_BASE64)))
        val tlsTrust = try {
            LicenseHttpTlsTrust.ofCertificate(caBytes)
        } finally {
            caBytes.fill(0)
        }

        val binding = assertIs<AndroidActivationDeviceBindingResult.Ready>(
            AndroidActivationDeviceBindingProvider(context).loadOrCreate()
        ).binding
        val expectedBinding = LicenseDeviceBindingReferenceFactory.create(
            installationId = binding.installationId,
            deviceKeyFingerprint = binding.deviceKeyFingerprint
        )

        val activationCodeFile = java.io.File(context.filesDir, ACTIVATION_CODE_FILE)
        assertTrue(activationCodeFile.isFile)
        val activationCode = activationCodeFile.readText(Charsets.UTF_8).trim()
        assertTrue(activationCode.isNotBlank())
        assertTrue(activationCodeFile.delete())
        assertTrue(!activationCodeFile.exists())

        val result = ActivationRedemptionHttpClient(
            LicenseHttpTransportConfig(
                endpoint = endpoint,
                connectTimeoutMillis = 10_000,
                readTimeoutMillis = 60_000,
                developmentAllowInsecureHttp = false,
                tlsTrust = tlsTrust
            )
        ).redeem(
            ActivationRedemptionHttpRequest(
                activationCode = activationCode,
                attemptId = ProductionAndroidActivationAttemptIdentity.loadOrCreate(context),
                installationId = binding.installationId,
                deviceKeyFingerprint = binding.deviceKeyFingerprint
            )
        )

        val activated = assertIs<ActivationRedemptionHttpResult.Activated>(result)
        val publicKey = Base64.getDecoder().decode(
            required(args.getString(ARG_ENTITLEMENT_KEY_DER_BASE64))
        )
        val trusted = try {
            LicenseTrustedVerificationKey.of(
                keyId = activated.license.signingKeyId,
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
        ).verify(activated.license)

        val verified = assertIs<LicenseVerificationResult.Verified>(verification)
        assertEquals(LicenseProductId(required(args.getString(ARG_PRODUCT))), verified.entitlement.productId)
        assertEquals(activated.subject, verified.entitlement.subject.value)
        assertEquals(expectedBinding, assertNotNull(verified.entitlement.deviceBindingReference))
        assertNull(verified.entitlement.expiresAt)
        assertNull(verified.entitlement.offlineLeaseUntil)
        assertTrue(verified.entitlement.features.isNotEmpty())

        println(
            "LILIYA_PHYSICAL_ACTIVATION=" +
                "{\"https\":true," +
                "\"serverOpaqueSubject\":true," +
                "\"oneDeviceBinding\":true," +
                "\"signedLicense\":true," +
                "\"signatureVerified\":true," +
                "\"productMatched\":true," +
                "\"deviceBindingMatched\":true," +
                "\"expiresAtNull\":true," +
                "\"offlineLeaseNull\":true," +
                "\"authorityExecutionStarted\":false}"
        )
    }
    private fun required(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: error("missing physical activation acceptance argument")

    private companion object {
        const val ARG_ENDPOINT = "activationEndpoint"
        const val ARG_CA_BASE64 = "activationCaBase64"
        const val ARG_ENTITLEMENT_KEY_DER_BASE64 = "activationLicenseKeyDerBase64"
        const val ARG_PRODUCT = "activationProductId"
        const val ACTIVATION_CODE_FILE = "physical-activation-code.once"
        const val ALGORITHM = "ECDSA-P256-SHA256"
    }
}
