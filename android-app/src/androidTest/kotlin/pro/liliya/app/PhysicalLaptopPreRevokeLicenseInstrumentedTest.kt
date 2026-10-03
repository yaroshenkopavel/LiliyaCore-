package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URL
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseDecision
import pro.liliya.core.license.LicenseFeature
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicensePolicy
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicensePolicyRequest
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

@RunWith(AndroidJUnit4::class)
class PhysicalLaptopPreRevokeLicenseInstrumentedTest {
    @Test
    fun current_license_is_locally_entitled_without_service_state_contact() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val application = instrumentation.targetContext.applicationContext as LiliyaApplication

        val endpoint = URL(required(args.getString(ARG_ENDPOINT)))
        assertEquals("https", endpoint.protocol)

        val caBytes = Base64.getDecoder().decode(required(args.getString(ARG_CA_BASE64)))
        val tlsTrust = try { LicenseHttpTlsTrust.ofCertificate(caBytes) }
        finally { caBytes.fill(0) }

        val product = LicenseProductId(required(args.getString(ARG_PRODUCT)))
        val subject = LicenseSubject(required(args.getString(ARG_SUBJECT)))
        val requestId = LicenseServiceRequestId(required(args.getString(ARG_REQUEST_ID)))

        val store = ProductionAndroidProductAuthEncryptedStore.create(application)
        val probe = store.openSecret()
        try { assertTrue(probe.isNotEmpty()) } finally { probe.fill(0) }

        val acquisition = ProductionAndroidLicenseEnvelopeAcquisition.acquire(
            client = LicenseHttpTransportClient(
                LicenseHttpTransportConfig(
                    endpoint = endpoint,
                    connectTimeoutMillis = 10_000,
                    readTimeoutMillis = 60_000,
                    developmentAllowInsecureHttp = false,
                    tlsTrust = tlsTrust
                )
            ),
            request = LicenseServiceTransportRequest(
                protocolVersion = LicenseServiceProtocolVersion(1),
                operation = LicenseServiceOperation.ISSUE,
                productId = product,
                subjectReference = subject,
                requestId = requestId
            ),
            authentication = ProductionAndroidProductAuthCredentialAdapter.bearerFactory(store)
        )
        val signed = assertIs<ProductionAndroidLicenseEnvelopeAcquisitionResult.Signed>(acquisition)

        val publicKey = Base64.getDecoder().decode(required(args.getString(ARG_LICENSE_KEY_BASE64)))
        val trusted = try {
            LicenseTrustedVerificationKey.of(
                keyId = signed.envelope.signingKeyId,
                algorithm = LicenseAlgorithm(ALGORITHM),
                material = publicKey
            )
        } finally { publicKey.fill(0) }

        val verified = assertIs<LicenseVerificationResult.Verified>(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(LicenseAlgorithm(ALGORITHM)),
                trustedKeys = LicenseTrustedKeyResolver { requested: LicenseKeyId ->
                    trusted.takeIf { it.keyId == requested }
                },
                signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
            ).verify(signed.envelope)
        )

        assertEquals(product, verified.entitlement.productId)
        assertEquals(subject, verified.entitlement.subject)
        assertNull(verified.entitlement.expiresAt)
        assertNull(verified.entitlement.offlineLeaseUntil)

        val feature = verified.entitlement.features.first()
        val decision = LicensePolicy().evaluate(
            verified = verified,
            request = LicensePolicyRequest(
                productId = product,
                feature = LicenseFeature(feature.value),
                subject = subject
            ),
            context = LicensePolicyContext(
                now = Instant.now(),
                requiredDeviceBindingReference = verified.entitlement.deviceBindingReference
            )
        )
        assertIs<LicenseDecision.Entitled>(decision)

        val envelopeState = application.filesDir.resolve(ENVELOPE_STATE_FILE)
        val payloadBytes = signed.envelope.payload.copyBytes()
        val signatureBytes = signed.envelope.signature.copyBytes()
        try {
            envelopeState.writeText(
                listOf(
                    signed.envelope.schemaVersion.value.toString(),
                    signed.envelope.algorithm.value,
                    signed.envelope.signingKeyId.value,
                    Base64.getEncoder().encodeToString(payloadBytes),
                    Base64.getEncoder().encodeToString(signatureBytes)
                ).joinToString("\n"),
                Charsets.UTF_8
            )
        } finally {
            payloadBytes.fill(0)
            signatureBytes.fill(0)
        }
        assertTrue(envelopeState.isFile && envelopeState.length() > 0L)

        println(
            "LILIYA_PRE_REVOKE_LICENSE=" +
                "{\"signed\":true,\"signatureVerified\":true," +
                "\"expiresAtNull\":true,\"offlineLeaseNull\":true," +
                "\"localPolicyEntitled\":true,\"serviceStateContact\":false}"
        )
    }

    private fun required(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: error("missing pre-revoke acceptance argument")

    private companion object {
        const val ARG_ENDPOINT = "preRevokeEndpoint"
        const val ARG_CA_BASE64 = "preRevokeCaBase64"
        const val ARG_LICENSE_KEY_BASE64 = "preRevokeLicenseKeyBase64"
        const val ARG_PRODUCT = "preRevokeProductId"
        const val ARG_SUBJECT = "preRevokeSubject"
        const val ARG_REQUEST_ID = "preRevokeRequestId"
        const val ENVELOPE_STATE_FILE = "physical-pre-revoke-license-envelope.once"
        const val ALGORITHM = "ECDSA-P256-SHA256"
    }
}
