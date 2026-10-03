package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URL
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingProvider
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingResult
import pro.liliya.core.license.JcaEcdsaP256LicenseServiceProofVerifier
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseCanonicalPayload
import pro.liliya.core.license.LicenseDecision
import pro.liliya.core.license.LicenseDenialReason
import pro.liliya.core.license.LicenseDeviceBindingReferenceFactory
import pro.liliya.core.license.LicenseFeature
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
import pro.liliya.core.licensetransport.LicenseHttpTlsTrust
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig
import pro.liliya.core.licensetransport.ServiceStateHttpClient
import pro.liliya.core.licensetransport.ServiceStateHttpRequest
import pro.liliya.core.licensetransport.ServiceStateHttpResult

@RunWith(AndroidJUnit4::class)
class PhysicalLaptopPostRevokeLicenseInstrumentedTest {
    @Test
    fun next_successful_online_contact_revokes_previous_unlimited_offline_license() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val application = instrumentation.targetContext.applicationContext as LiliyaApplication

        val endpoint = URL(required(args.getString(ARG_ENDPOINT)))
        assertEquals("https", endpoint.protocol)

        val caBytes = Base64.getDecoder().decode(required(args.getString(ARG_CA_BASE64)))
        val tlsTrust = try { LicenseHttpTlsTrust.ofCertificate(caBytes) }
        finally { caBytes.fill(0) }

        val stateFile = application.filesDir.resolve(ENVELOPE_STATE_FILE)
        assertTrue(stateFile.isFile)
        val parts = stateFile.readLines(Charsets.UTF_8)
        assertEquals(5, parts.size)
        val oldEnvelope = LicenseSignedEnvelope(
            schemaVersion = LicenseVersion(parts[0].toLong()),
            algorithm = LicenseAlgorithm(parts[1]),
            signingKeyId = LicenseKeyId(parts[2]),
            payload = LicenseCanonicalPayload.of(Base64.getDecoder().decode(parts[3])),
            signature = LicenseSignature.of(Base64.getDecoder().decode(parts[4]))
        )

        val licenseKey = Base64.getDecoder().decode(required(args.getString(ARG_LICENSE_KEY_BASE64)))
        val trustedLicenseKey = try {
            LicenseTrustedVerificationKey.of(
                keyId = oldEnvelope.signingKeyId,
                algorithm = LicenseAlgorithm(LICENSE_ALGORITHM),
                material = licenseKey
            )
        } finally { licenseKey.fill(0) }

        val oldVerified = assertIs<LicenseVerificationResult.Verified>(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(LicenseAlgorithm(LICENSE_ALGORITHM)),
                trustedKeys = LicenseTrustedKeyResolver { requested: LicenseKeyId ->
                    trustedLicenseKey.takeIf { it.keyId == requested }
                },
                signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
            ).verify(oldEnvelope)
        )

        val binding = assertIs<AndroidActivationDeviceBindingResult.Ready>(
            AndroidActivationDeviceBindingProvider(application).loadOrCreate()
        ).binding
        val expectedBinding = LicenseDeviceBindingReferenceFactory.create(
            installationId = binding.installationId,
            deviceKeyFingerprint = binding.deviceKeyFingerprint
        )
        assertEquals(expectedBinding, assertNotNull(oldVerified.entitlement.deviceBindingReference))

        val productAuthStore = ProductionAndroidProductAuthEncryptedStore.create(application)
        val credentialFactory = ProductionAndroidProductAuthCredentialAdapter.bearerFactory(
            productAuthStore
        )
        val credential = credentialFactory.create()
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
                        productId = oldVerified.entitlement.productId,
                        subject = oldVerified.entitlement.subject
                    ),
                    requestId = LicenseServiceRequestId(
                        "post-revoke-" + UUID.randomUUID().toString()
                    )
                ),
                authentication = credential
            )
        } finally {
            credential.close()
        }

        val evidence = assertIs<ServiceStateHttpResult.Evidence>(serviceResult)
        val serviceKeyBytes = Base64.getDecoder().decode(
            required(args.getString(ARG_SERVICE_STATE_KEY_BASE64))
        )
        val serviceProfile = LicenseServiceEvidenceProfile(SERVICE_PROFILE)
        val trustedServiceKey = try {
            LicenseServiceTrustedVerificationKey.of(
                keyId = evidence.envelope.signingKeyId,
                profile = serviceProfile,
                material = serviceKeyBytes
            )
        } finally { serviceKeyBytes.fill(0) }

        val verifiedState = assertIs<LicenseServiceStateVerificationResult.Verified>(
            LicenseServiceStateVerifier(
                supportedProtocolVersion = LicenseServiceProtocolVersion(1),
                supportedPurposes = setOf(LicenseServiceEvidencePurpose.SECURITY_STATE),
                supportedProfiles = setOf(serviceProfile),
                trustedKeys = LicenseServiceTrustedKeyResolver { keyId, profile ->
                    trustedServiceKey.takeIf {
                        it.keyId == keyId && it.profile == profile
                    }
                },
                proofVerifier = JcaEcdsaP256LicenseServiceProofVerifier
            ).verify(evidence.envelope)
        )

        assertEquals(oldVerified.entitlement.productId, verifiedState.state.scope.productId)
        assertEquals(oldVerified.entitlement.subject, verifiedState.state.scope.subject)
        val currentRevocation = assertNotNull(verifiedState.state.revocationEpoch)
        assertTrue(currentRevocation.value > oldVerified.entitlement.revocationEpoch.value)

        val feature = oldVerified.entitlement.features.first()
        val decision = LicensePolicy().evaluate(
            verified = oldVerified,
            request = LicensePolicyRequest(
                productId = oldVerified.entitlement.productId,
                feature = LicenseFeature(feature.value),
                subject = oldVerified.entitlement.subject
            ),
            context = LicensePolicyContext(
                now = Instant.now(),
                minimumRevocationEpoch = currentRevocation,
                requiredDeviceBindingReference = expectedBinding
            )
        )
        val denied = assertIs<LicenseDecision.Denied>(decision)
        assertEquals(LicenseDenialReason.STALE_REVOCATION_EPOCH, denied.reason)

        assertTrue(stateFile.delete())
        println(
            "LILIYA_POST_REVOKE_LICENSE=" +
                "{\"serviceStateContact\":true,\"serviceStateVerified\":true," +
                "\"revocationEpochAdvanced\":true," +
                "\"oldLicenseDenied\":true," +
                "\"denialReason\":\"STALE_REVOCATION_EPOCH\"," +
                "\"authorityExecutionStarted\":false}"
        )
    }

    private fun required(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: error("missing post-revoke acceptance argument")

    private companion object {
        const val ARG_ENDPOINT = "postRevokeEndpoint"
        const val ARG_CA_BASE64 = "postRevokeCaBase64"
        const val ARG_LICENSE_KEY_BASE64 = "postRevokeLicenseKeyBase64"
        const val ARG_SERVICE_STATE_KEY_BASE64 = "postRevokeServiceStateKeyBase64"
        const val ENVELOPE_STATE_FILE = "physical-pre-revoke-license-envelope.once"
        const val LICENSE_ALGORITHM = "ECDSA-P256-SHA256"
        const val SERVICE_PROFILE = "ECDSA-P256-SHA256-SERVICE-STATE-V1"
    }
}
