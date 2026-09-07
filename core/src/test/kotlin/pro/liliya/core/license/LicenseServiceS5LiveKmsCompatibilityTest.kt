package pro.liliya.core.license

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Assumptions.assumeTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class LicenseServiceS5LiveKmsCompatibilityTest {
    @Test
    fun live_backend_kms_envelope_reaches_frozen_policy_path_and_stops_before_authority() {
        val evidencePath = System.getenv("LIVE_S5_KMS_EVIDENCE_PATH")
        assumeTrue(!evidencePath.isNullOrBlank(), "LIVE_S5_KMS_EVIDENCE_PATH is not configured")

        val properties = Properties().apply {
            Files.newInputStream(Path.of(evidencePath!!)).use(::load)
        }

        val schemaVersion = LicenseVersion(
            properties.requireLong("schemaVersion")
        )
        val algorithm = LicenseAlgorithm(
            properties.requireString("algorithm")
        )
        val keyId = LicenseKeyId(
            properties.requireString("keyReference")
        )
        val payload = LicenseCanonicalPayload.of(
            Base64.getDecoder().decode(properties.requireString("payloadBase64"))
        )
        val signature = LicenseSignature.of(
            Base64.getDecoder().decode(properties.requireString("signatureBase64"))
        )
        val publicKey = LicenseTrustedVerificationKey.of(
            keyId = keyId,
            algorithm = algorithm,
            material = Base64.getDecoder().decode(properties.requireString("publicKeyDerBase64"))
        )

        val verified = assertIs<LicenseVerificationResult.Verified>(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(LicenseAlgorithm("ECDSA-P256-SHA256")),
                trustedKeys = LicenseTrustedKeyResolver { requested ->
                    publicKey.takeIf { requested == keyId }
                },
                signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
            ).verify(
                LicenseSignedEnvelope(
                    schemaVersion = schemaVersion,
                    algorithm = algorithm,
                    signingKeyId = keyId,
                    payload = payload,
                    signature = signature
                )
            )
        )

        assertEquals(keyId, verified.entitlement.signingKeyId)
        assertEquals(LicenseProductId("liliya-pro"), verified.entitlement.productId)

        val serviceState = LicenseServiceSecurityState(
            scope = LicenseServiceSecurityScope(
                productId = verified.entitlement.productId,
                subject = verified.entitlement.subject
            ),
            revocationEpoch = verified.entitlement.revocationEpoch,
            replaySequence = verified.entitlement.replaySequence,
            serverTime = verified.entitlement.notBefore
        )

        val serviceKey = LicenseServiceTrustedVerificationKey.of(
            keyId = LicenseKeyId("live-s5-service-state-test-key"),
            profile = LicenseServiceEvidenceProfile("TEST-SERVICE-SHA256"),
            material = "LIVE-S5-SERVICE-STATE-TEST-MATERIAL".encodeToByteArray()
        )
        val unsignedState = LicenseServiceStateEnvelope(
            protocolVersion = LicenseServiceProtocolVersion(1),
            purpose = LicenseServiceEvidencePurpose.SECURITY_STATE,
            profile = serviceKey.profile,
            signingKeyId = serviceKey.keyId,
            payload = LicenseServiceSecurityStateCanonicalCodec.encode(serviceState),
            proof = LicenseServiceAuthenticationProof.of(byteArrayOf(1))
        )
        val signedState = LicenseServiceStateEnvelope(
            protocolVersion = unsignedState.protocolVersion,
            purpose = unsignedState.purpose,
            profile = unsignedState.profile,
            signingKeyId = unsignedState.signingKeyId,
            payload = unsignedState.payload,
            proof = LicenseServiceDigestTestProofVerifier.signForTest(serviceKey, unsignedState)
        )

        val acceptance = LicenseServiceStateAcceptanceComposition(
            foundation = foundation(),
            supportedProtocolVersion = LicenseServiceProtocolVersion(1),
            supportedPurposes = setOf(LicenseServiceEvidencePurpose.SECURITY_STATE),
            supportedProfiles = setOf(serviceKey.profile),
            trustedKeys = LicenseServiceTrustedKeyResolver { requestedId, requestedProfile ->
                serviceKey.takeIf {
                    it.keyId == requestedId && it.profile == requestedProfile
                }
            },
            proofVerifier = LicenseServiceDigestTestProofVerifier
        )

        assertIs<LicenseServiceStateAcceptanceResult.Advanced>(
            acceptance.verifyAndAccept(signedState)
        )

        val evaluationTime = verified.entitlement.notBefore.plusSeconds(1)
        val context = assertIs<LicenseServicePolicyContextResult.Available>(
            acceptance.policyContext(
                scope = serviceState.scope,
                now = evaluationTime,
                suspiciousTimeOrReplayState = false
            )
        ).context

        val decision = assertIs<LicenseDecision.Entitled>(
            LicensePolicy().evaluate(
                verified = verified,
                request = LicensePolicyRequest(
                    productId = verified.entitlement.productId,
                    feature = LicenseFeature("model.local"),
                    subject = verified.entitlement.subject
                ),
                context = context
            )
        )

        assertEquals(verified.entitlement.id, decision.receipt.licenseId)
        assertEquals(verified.entitlement.replaySequence, decision.receipt.replaySequence)
    }

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            correlationIds = CorrelationIdGenerator {
                "license-s5-live-kms-" + sequence.incrementAndGet()
            }
        )
    }

    private fun Properties.requireString(key: String): String =
        getProperty(key)?.takeIf { it.isNotBlank() }
            ?: error("missing evidence property: $key")

    private fun Properties.requireLong(key: String): Long =
        requireString(key).toLong()
}
