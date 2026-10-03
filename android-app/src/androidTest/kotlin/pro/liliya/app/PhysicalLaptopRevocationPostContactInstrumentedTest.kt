package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.license.JcaEcdsaP256LicenseServiceProofVerifier
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseCanonicalPayload
import pro.liliya.core.license.LicenseDecision
import pro.liliya.core.license.LicenseDenialReason
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicensePolicy
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicensePolicyRequest
import pro.liliya.core.license.LicenseServiceEvidenceProfile
import pro.liliya.core.license.LicenseServiceEvidencePurpose
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseServiceSecurityScope
import pro.liliya.core.license.LicenseServiceStateVerificationResult
import pro.liliya.core.license.LicenseServiceStateVerifier
import pro.liliya.core.license.LicenseServiceTrustedKeyResolver
import pro.liliya.core.license.LicenseServiceTrustedVerificationKey
import pro.liliya.core.license.LicenseSignature
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseTrustedVerificationKey
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.license.LicenseVerifier
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.licensetransport.LicenseHttpBearerCredential
import pro.liliya.core.licensetransport.LicenseHttpTlsTrust
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig
import pro.liliya.core.licensetransport.ServiceStateHttpClient
import pro.liliya.core.licensetransport.ServiceStateHttpRequest
import pro.liliya.core.licensetransport.ServiceStateHttpResult

@RunWith(AndroidJUnit4::class)
class PhysicalLaptopRevocationPostContactInstrumentedTest {
    @Test
    fun successful_online_contact_advances_revocation_and_denies_old_license() {
        val args = InstrumentationRegistry.getArguments()
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as LiliyaApplication
        val endpoint = URL(required(args.getString(ARG_ENDPOINT)))
        assertEquals("https", endpoint.protocol)

        val baselineFile = File(app.filesDir, BASELINE_FILE)
        val oldEnvelope = readEnvelope(baselineFile)
        val oldVerified = verifyLicense(oldEnvelope, File(app.filesDir, LICENSE_KEY_FILE))
        val oldEntitlement = oldVerified.entitlement

        val caBytes = File(app.filesDir, CA_FILE).readBytes()
        val tlsTrust = try { LicenseHttpTlsTrust.ofCertificate(caBytes) }
        finally { caBytes.fill(0) }

        val authStore = ProductionAndroidProductAuthEncryptedStore.create(app)
        val authBytes = authStore.openSecret()
        val credential = try { LicenseHttpBearerCredential.of(authBytes) }
        finally { authBytes.fill(0) }

        val serviceResult = try {
            ServiceStateHttpClient(
                LicenseHttpTransportConfig(
                    endpoint = endpoint,
                    connectTimeoutMillis = 10_000,
                    readTimeoutMillis = 60_000,
                    developmentAllowInsecureHttp = false,
                    tlsTrust = tlsTrust
                )
            ).execute(
                request = ServiceStateHttpRequest(
                    protocolVersion = LicenseServiceProtocolVersion(1),
                    scope = LicenseServiceSecurityScope(
                        productId = oldEntitlement.productId,
                        subject = oldEntitlement.subject
                    ),
                    requestId = LicenseServiceRequestId(
                        "physical-revocation-post-" + System.nanoTime()
                    )
                ),
                authentication = credential
            )
        } finally {
            credential.close()
        }

        val evidence = assertIs<ServiceStateHttpResult.Evidence>(serviceResult)
        val serviceKeyBytes = File(app.filesDir, SERVICE_STATE_KEY_FILE).readBytes()
        val trustedServiceKey = try {
            LicenseServiceTrustedVerificationKey.of(
                keyId = evidence.envelope.signingKeyId,
                profile = LicenseServiceEvidenceProfile(SERVICE_PROFILE),
                material = serviceKeyBytes
            )
        } finally { serviceKeyBytes.fill(0) }

        val verifiedState = assertIs<LicenseServiceStateVerificationResult.Verified>(
            LicenseServiceStateVerifier(
                supportedProtocolVersion = LicenseServiceProtocolVersion(1),
                supportedPurposes = setOf(LicenseServiceEvidencePurpose.SECURITY_STATE),
                supportedProfiles = setOf(LicenseServiceEvidenceProfile(SERVICE_PROFILE)),
                trustedKeys = LicenseServiceTrustedKeyResolver { keyId, profile ->
                    trustedServiceKey.takeIf {
                        it.keyId == keyId && it.profile == profile
                    }
                },
                proofVerifier = JcaEcdsaP256LicenseServiceProofVerifier
            ).verify(evidence.envelope)
        )

        assertEquals(oldEntitlement.productId, verifiedState.state.scope.productId)
        assertEquals(oldEntitlement.subject, verifiedState.state.scope.subject)
        val minimumRevocation = assertNotNull(verifiedState.state.revocationEpoch)
        assertTrue(minimumRevocation.value > oldEntitlement.revocationEpoch.value)

        val decision = LicensePolicy().evaluate(
            verified = oldVerified,
            request = LicensePolicyRequest(
                productId = oldEntitlement.productId,
                feature = oldEntitlement.features.first(),
                subject = oldEntitlement.subject
            ),
            context = LicensePolicyContext(
                now = Instant.now(),
                minimumRevocationEpoch = minimumRevocation,
                requiredDeviceBindingReference = oldEntitlement.deviceBindingReference
            )
        )
        val denied = assertIs<LicenseDecision.Denied>(decision)
        assertEquals(LicenseDenialReason.STALE_REVOCATION_EPOCH, denied.reason)
        assertTrue(baselineFile.delete())
        assertTrue(!baselineFile.exists())

        println(
            "LILIYA_REVOCATION_POST_CONTACT=" +
                "{\"serviceStateAuthenticated\":true,\"revocationAdvanced\":true," +
                "\"oldLicenseDenied\":true,\"denial\":\"STALE_REVOCATION_EPOCH\"}"
        )
    }

    private fun verifyLicense(envelope: LicenseSignedEnvelope, keyFile: File): LicenseVerificationResult.Verified {
        val publicKey = Base64.getDecoder().decode(
            keyFile.readLines()
                .filterNot { it.startsWith("-----") }
                .joinToString("")
                .trim()
        )
        val trusted = try {
            LicenseTrustedVerificationKey.of(
                keyId = envelope.signingKeyId,
                algorithm = LicenseAlgorithm(ALGORITHM),
                material = publicKey
            )
        } finally { publicKey.fill(0) }

        return assertIs(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(LicenseAlgorithm(ALGORITHM)),
                trustedKeys = LicenseTrustedKeyResolver { requested: LicenseKeyId ->
                    trusted.takeIf { it.keyId == requested }
                },
                signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
            ).verify(envelope)
        )
    }

    private fun readEnvelope(file: File): LicenseSignedEnvelope {
        assertTrue(file.isFile)
        return DataInputStream(FileInputStream(file)).use { input ->
            assertEquals(MAGIC, input.readInt())
            val schema = LicenseVersion(input.readLong())
            val algorithm = LicenseAlgorithm(input.readUTF())
            val keyId = LicenseKeyId(input.readUTF())
            val payload = ByteArray(input.readInt()).also(input::readFully)
            val signature = ByteArray(input.readInt()).also(input::readFully)
            LicenseSignedEnvelope(
                schemaVersion = schema,
                algorithm = algorithm,
                signingKeyId = keyId,
                payload = LicenseCanonicalPayload.of(payload),
                signature = LicenseSignature.of(signature)
            ).also {
                payload.fill(0)
                signature.fill(0)
            }
        }
    }

    private fun required(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: error("missing revocation post-contact argument")

    private companion object {
        const val ARG_ENDPOINT = "revocationEndpoint"
        const val CA_FILE = "licensing-ca.crt"
        const val LICENSE_KEY_FILE = "license-signing-v1-public.pem"
        const val SERVICE_STATE_KEY_FILE = "license-service-state-v1-public.der"
        const val BASELINE_FILE = "physical-revocation-baseline.bin"
        const val ALGORITHM = "ECDSA-P256-SHA256"
        const val SERVICE_PROFILE = "ECDSA-P256-SHA256-SERVICE-STATE-V1"
        const val MAGIC = 0x4C525631
    }
}
