package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseSubject
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseTrustedVerificationKey
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.license.LicenseVerifier
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.licensetransport.LicenseHttpTransportClient
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig
import pro.liliya.core.licensetransport.LicenseServiceTransportRequest

/**
 * Physical-device local acceptance overlay.
 *
 * Frozen production sources are unchanged.
 *
 * Proves:
 * Android encrypted Product Auth store
 * -> production bearer adapter
 * -> production HTTPS client
 * -> live Licensing Service
 * -> signed License
 * -> frozen Core signature verification.
 *
 * Public acceptance CA/key arrive only as instrumentation arguments.
 * Product Auth bearer never does.
 *
 * Authority, runtime and model execution are intentionally not invoked.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalLiveLicenseAcquisitionInstrumentedTest {

    @Test
    fun encrypted_product_auth_acquires_and_verifies_live_signed_license() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()

        val application =
            instrumentation.targetContext.applicationContext as LiliyaApplication

        val endpoint = URL(requiredArgument(arguments.getString(ARG_ENDPOINT)))

        assertEquals(
            "https",
            endpoint.protocol,
            "physical live License acceptance requires HTTPS"
        )

        val caBytes = Base64.getDecoder().decode(
            requiredArgument(arguments.getString(ARG_CA_BASE64))
        )

        try {
            installAcceptanceTrust(caBytes)
        } finally {
            caBytes.fill(0)
        }

        val store = ProductionAndroidProductAuthEncryptedStore.create(application)

        val probe = store.openSecret()
        try {
            assertTrue(probe.isNotEmpty())
        } finally {
            probe.fill(0)
        }

        val client = LicenseHttpTransportClient(
            LicenseHttpTransportConfig(
                endpoint = endpoint,
                connectTimeoutMillis = 5_000,
                readTimeoutMillis = 10_000
            )
        )

        val request = LicenseServiceTransportRequest(
            protocolVersion = LicenseServiceProtocolVersion(1),
            operation = LicenseServiceOperation.ISSUE,
            productId = LicenseProductId(PRODUCT),
            subjectReference = LicenseSubject(SUBJECT),
            requestId = LicenseServiceRequestId(REQUEST_ID)
        )

        val acquisition =
            ProductionAndroidLicenseEnvelopeAcquisition.acquire(
                client = client,
                request = request,
                authentication =
                    ProductionAndroidProductAuthCredentialAdapter.bearerFactory(store)
            )

        val signed =
            assertIs<ProductionAndroidLicenseEnvelopeAcquisitionResult.Signed>(
                acquisition
            )

        val publicKey = Base64.getDecoder().decode(
            requiredArgument(arguments.getString(ARG_ENTITLEMENT_KEY_BASE64))
        )

        val trusted = try {
            LicenseTrustedVerificationKey.of(
                keyId = signed.envelope.signingKeyId,
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
        ).verify(signed.envelope)

        val verified =
            assertIs<LicenseVerificationResult.Verified>(verification)

        assertEquals(
            LicenseProductId(PRODUCT),
            verified.entitlement.productId
        )

        assertEquals(
            LicenseSubject(SUBJECT),
            verified.entitlement.subject
        )

        assertEquals(
            0L,
            verified.entitlement.replaySequence?.value
        )

        println(
            "LILIYA_PHYSICAL_LIVE_LICENSE_EVIDENCE=" +
                "{\"https\":true," +
                "\"encryptedProductAuth\":true," +
                "\"productionBearerAdapter\":true," +
                "\"realLicensingService\":true," +
                "\"signedEnvelope\":true," +
                "\"frozenCoreSignatureVerified\":true," +
                "\"replaySequence\":0," +
                "\"authorityExecutionStarted\":false}"
        )

        println("LILIYA_PHYSICAL_LIVE_LICENSE_ACQUISITION=PASS")
    }

    private fun installAcceptanceTrust(certificateBytes: ByteArray) {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificateBytes))

        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null)
        store.setCertificateEntry("liliya-local-acceptance-ca", certificate)

        val trustManagerFactory =
            TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            )

        trustManagerFactory.init(store)

        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, trustManagerFactory.trustManagers, null)

        HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
    }

    private fun requiredArgument(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: error("missing physical live-license acceptance argument")

    private companion object {
        const val ARG_ENDPOINT = "liveLicenseEndpoint"
        const val ARG_CA_BASE64 = "liveCaBase64"
        const val ARG_ENTITLEMENT_KEY_BASE64 = "liveEntitlementKeyBase64"

        const val PRODUCT = "liliya-pro"
        const val SUBJECT = "physical-android-product-auth-subject"
        const val REQUEST_ID = "physical-android-product-auth-issue-001"
        const val ALGORITHM = "ECDSA-P256-SHA256"
    }
}
