package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingProvider
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingResult
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseDecision
import pro.liliya.core.license.LicenseDeviceBindingReferenceFactory
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicensePolicy
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicensePolicyRequest
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceEnrollmentId
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
class PhysicalLaptopRevocationPreContactInstrumentedTest {
    @Test
    fun unlimited_offline_license_is_locally_entitled_before_revocation_contact() {
        val args = InstrumentationRegistry.getArguments()
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as LiliyaApplication

        val endpoint = URL(required(args.getString(ARG_ENDPOINT)))
        assertEquals("https", endpoint.protocol)

        val subjectFile = File(app.filesDir, SUBJECT_FILE)
        assertTrue(subjectFile.isFile)
        val subjectText = subjectFile.readText(Charsets.UTF_8).trim()
        assertTrue(subjectText.isNotBlank())
        assertTrue(subjectFile.delete())

        val caBytes = File(app.filesDir, CA_FILE).readBytes()
        val tlsTrust = try { LicenseHttpTlsTrust.ofCertificate(caBytes) }
        finally { caBytes.fill(0) }

        val binding = assertIs<AndroidActivationDeviceBindingResult.Ready>(
            AndroidActivationDeviceBindingProvider(app).loadOrCreate()
        ).binding
        val expectedBinding = LicenseDeviceBindingReferenceFactory.create(
            installationId = binding.installationId,
            deviceKeyFingerprint = binding.deviceKeyFingerprint
        )

        val store = ProductionAndroidProductAuthEncryptedStore.create(app)
        val probe = store.openSecret()
        try { assertTrue(probe.isNotEmpty()) } finally { probe.fill(0) }

        val acquisition = ProductionAndroidLicenseEnvelopeAcquisition.acquire(
            client = LicenseHttpTransportClient(
                LicenseHttpTransportConfig(
                    endpoint = URL(
                        endpoint.protocol,
                        endpoint.host,
                        endpoint.port,
                        "/v1/license"
                    ),
                    connectTimeoutMillis = 10_000,
                    readTimeoutMillis = 60_000,
                    developmentAllowInsecureHttp = false,
                    tlsTrust = tlsTrust
                )
            ),
            request = LicenseServiceTransportRequest(
                protocolVersion = LicenseServiceProtocolVersion(1),
                operation = LicenseServiceOperation.ISSUE,
                productId = LicenseProductId(PRODUCT_ID),
                subjectReference = LicenseSubject(subjectText),
                requestId = LicenseServiceRequestId("physical-revocation-pre-" + System.nanoTime()),
                enrollmentId = LicenseServiceEnrollmentId(expectedBinding.value)
            ),
            authentication = ProductionAndroidProductAuthCredentialAdapter.bearerFactory(store)
        )
        when (acquisition) {
            is ProductionAndroidLicenseEnvelopeAcquisitionResult.Signed ->
                println("LILIYA_REVOCATION_PRE_ACQUISITION=SIGNED")
            is ProductionAndroidLicenseEnvelopeAcquisitionResult.ServiceRejected ->
                println("LILIYA_REVOCATION_PRE_ACQUISITION=SERVICE_REJECTED:" + acquisition.reason)
            is ProductionAndroidLicenseEnvelopeAcquisitionResult.Failed ->
                println("LILIYA_REVOCATION_PRE_ACQUISITION=FAILED:" + acquisition.reason)
        }
        check(acquisition is ProductionAndroidLicenseEnvelopeAcquisitionResult.Signed) {
            "revocation pre-contact acquisition failed: " + acquisition
        }
        val signed = acquisition

        val publicKey = decodePem(File(app.filesDir, LICENSE_KEY_FILE))
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

        assertNull(verified.entitlement.expiresAt)
        assertNull(verified.entitlement.offlineLeaseUntil)

        assertEquals(expectedBinding, verified.entitlement.deviceBindingReference)

        val decision = LicensePolicy().evaluate(
            verified = verified,
            request = LicensePolicyRequest(
                productId = verified.entitlement.productId,
                feature = verified.entitlement.features.first(),
                subject = verified.entitlement.subject
            ),
            context = LicensePolicyContext(
                now = Instant.now(),
                minimumRevocationEpoch = verified.entitlement.revocationEpoch,
                requiredDeviceBindingReference = expectedBinding
            )
        )
        assertIs<LicenseDecision.Entitled>(decision)

        persistEnvelope(File(app.filesDir, BASELINE_FILE), signed.envelope)

        println(
            "LILIYA_REVOCATION_PRE_CONTACT=" +
                "{\"signedLicense\":true,\"signatureVerified\":true," +
                "\"expiresAtNull\":true,\"offlineLeaseNull\":true," +
                "\"localOfflinePolicyEntitled\":true,\"onlineContactRequiredForLocalUse\":false}"
        )
    }

    private fun persistEnvelope(file: File, envelope: pro.liliya.core.license.LicenseSignedEnvelope) {
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeInt(MAGIC)
            out.writeLong(envelope.schemaVersion.value)
            out.writeUTF(envelope.algorithm.value)
            out.writeUTF(envelope.signingKeyId.value)
            val payload = envelope.payload.copyBytes()
            val signature = envelope.signature.copyBytes()
            out.writeInt(payload.size)
            out.write(payload)
            out.writeInt(signature.size)
            out.write(signature)
            payload.fill(0)
            signature.fill(0)
        }
        assertTrue(file.isFile && file.length() > 0L)
    }

    private fun decodePem(file: File): ByteArray =
        Base64.getDecoder().decode(
            file.readLines()
                .filterNot { it.startsWith("-----") }
                .joinToString("")
                .trim()
        )

    private fun required(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: error("missing revocation pre-contact argument")

    private companion object {
        const val ARG_ENDPOINT = "revocationEndpoint"
        const val PRODUCT_ID = "liliya-pro"
        const val SUBJECT_FILE = "physical-revocation-subject.once"
        const val CA_FILE = "licensing-ca.crt"
        const val LICENSE_KEY_FILE = "license-signing-v1-public.pem"
        const val BASELINE_FILE = "physical-revocation-baseline.bin"
        const val ALGORITHM = "ECDSA-P256-SHA256"
        const val MAGIC = 0x4C525631
    }
}
