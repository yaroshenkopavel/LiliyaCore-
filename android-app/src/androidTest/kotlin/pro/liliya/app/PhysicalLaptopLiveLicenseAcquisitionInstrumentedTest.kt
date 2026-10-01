package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URL
import java.util.Base64
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
import pro.liliya.core.licensetransport.LicenseHttpTlsTrust
import pro.liliya.core.licensetransport.LicenseHttpTransportClient
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig
import pro.liliya.core.licensetransport.LicenseServiceTransportRequest

/**
 * Acceptance-only positive licensing slice for #259 against the laptop-hosted backend.
 *
 * Product Auth comes only from the production encrypted Android store. The bearer is never accepted
 * as an instrumentation argument. Endpoint, public CA, public License verification key and opaque
 * request identity values are explicit deployment inputs.
 *
 * License verification completes here; Authority/Execution are intentionally a separate next gate.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalLaptopLiveLicenseAcquisitionInstrumentedTest {

    @Test
    fun encrypted_product_auth_acquires_and_verifies_laptop_signed_license() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val application =
            instrumentation.targetContext.applicationContext as LiliyaApplication

        val endpoint = URL(required(args.getString(ARG_ENDPOINT)))
        assertEquals("https", endpoint.protocol)

        val caBytes = Base64.getDecoder().decode(required(args.getString(ARG_CA_BASE64)))
        val tlsTrust = try {
            LicenseHttpTlsTrust.ofCertificate(caBytes)
        } finally {
            caBytes.fill(0)
        }

        val product = LicenseProductId(required(args.getString(ARG_PRODUCT)))
        val subject = LicenseSubject(required(args.getString(ARG_SUBJECT)))
        val requestId = LicenseServiceRequestId(required(args.getString(ARG_REQUEST_ID)))

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
                connectTimeoutMillis = 10_000,
                readTimeoutMillis = 60_000,
                developmentAllowInsecureHttp = false,
                tlsTrust = tlsTrust
            )
        )

        val acquisition = ProductionAndroidLicenseEnvelopeAcquisition.acquire(
            client = client,
            request = LicenseServiceTransportRequest(
                protocolVersion = LicenseServiceProtocolVersion(1),
                operation = LicenseServiceOperation.ISSUE,
                productId = product,
                subjectReference = subject,
                requestId = requestId
            ),
            authentication =
                ProductionAndroidProductAuthCredentialAdapter.bearerFactory(store)
        )

        val signed =
            assertIs<ProductionAndroidLicenseEnvelopeAcquisitionResult.Signed>(acquisition)

        val publicKey = Base64.getDecoder().decode(
            required(args.getString(ARG_ENTITLEMENT_KEY_BASE64))
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

        val verified = assertIs<LicenseVerificationResult.Verified>(verification)
        assertEquals(product, verified.entitlement.productId)
        assertEquals(subject, verified.entitlement.subject)

        println(
            "LILIYA_LAPTOP_LIVE_LICENSE=" +
                "{\"https\":true," +
                "\"encryptedProductAuth\":true," +
                "\"signedLicense\":true," +
                "\"signatureVerified\":true," +
                "\"replaySequence\":" +
                (verified.entitlement.replaySequence?.value ?: -1L) +
                ",\"authorityExecutionStarted\":false}"
        )
    }

    private fun required(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: error("missing laptop live-license acceptance argument")

    private companion object {
        const val ARG_ENDPOINT = "liveLicenseEndpoint"
        const val ARG_CA_BASE64 = "liveCaBase64"
        const val ARG_ENTITLEMENT_KEY_BASE64 = "liveEntitlementKeyBase64"
        const val ARG_PRODUCT = "liveProductId"
        const val ARG_SUBJECT = "liveSubject"
        const val ARG_REQUEST_ID = "liveRequestId"
        const val ALGORITHM = "ECDSA-P256-SHA256"
    }
}
