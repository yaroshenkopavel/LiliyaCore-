package pro.liliya.core.license

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class LicenseServiceS5CompatibilityContractTest {
    private val now = Instant.parse("2026-09-07T09:45:00Z")

    @Test
    fun production_profile_entitlement_flows_through_frozen_verifier_and_service_policy_context() {
        val algorithm = LicenseAlgorithm("ECDSA-P256-SHA256")
        val keyId = LicenseKeyId("prod-signing-key-v2")
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        val entitlement = LicenseEntitlement(
            id = LicenseId("license-s5-compat-001"),
            subject = LicenseSubject("private-s5-subject"),
            productId = LicenseProductId("liliya-pro"),
            features = setOf(LicenseFeature("model.local")),
            version = LicenseVersion(1),
            signingKeyId = keyId,
            issuedAt = now.minusSeconds(60),
            notBefore = now.minusSeconds(30),
            expiresAt = now.plusSeconds(3600),
            offlineLeaseUntil = now.plusSeconds(1800),
            revocationEpoch = LicenseRevocationEpoch(9),
            replaySequence = LicenseReplaySequence(41)
        )

        val payload = LicenseEntitlementCanonicalCodec.encode(entitlement)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(payload.copyBytes())

        val envelope = LicenseSignedEnvelope(
            schemaVersion = LicenseVersion(1),
            algorithm = algorithm,
            signingKeyId = keyId,
            payload = payload,
            signature = LicenseSignature.of(signer.sign())
        )

        val trustedKey = LicenseTrustedVerificationKey.of(
            keyId = keyId,
            algorithm = algorithm,
            material = pair.public.encoded
        )

        val verified = assertIs<LicenseVerificationResult.Verified>(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(algorithm),
                trustedKeys = LicenseTrustedKeyResolver { requested ->
                    trustedKey.takeIf { requested == keyId }
                },
                signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
            ).verify(envelope)
        )

        assertEquals(entitlement, verified.entitlement)

        val serviceState = LicenseServiceSecurityState(
            scope = LicenseServiceSecurityScope(
                productId = entitlement.productId,
                subject = entitlement.subject
            ),
            revocationEpoch = entitlement.revocationEpoch,
            replaySequence = entitlement.replaySequence,
            serverTime = now.minusSeconds(1)
        )

        val serviceKey = LicenseServiceTrustedVerificationKey.of(
            keyId = LicenseKeyId("service-state-test-key"),
            profile = LicenseServiceEvidenceProfile("TEST-SERVICE-SHA256"),
            material = "SERVICE-STATE-TEST-MATERIAL".encodeToByteArray()
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

        val context = assertIs<LicenseServicePolicyContextResult.Available>(
            acceptance.policyContext(
                scope = serviceState.scope,
                now = now,
                suspiciousTimeOrReplayState = false
            )
        ).context

        assertEquals(entitlement.revocationEpoch, context.minimumRevocationEpoch)
        assertEquals(entitlement.replaySequence, context.minimumReplaySequence)

        val decision = assertIs<LicenseDecision.Entitled>(
            LicensePolicy().evaluate(
                verified = verified,
                request = LicensePolicyRequest(
                    productId = entitlement.productId,
                    feature = LicenseFeature("model.local"),
                    subject = entitlement.subject
                ),
                context = context
            )
        )

        assertEquals(entitlement.id, decision.receipt.licenseId)
        assertEquals(entitlement.replaySequence, decision.receipt.replaySequence)
    }

    @Test
    fun production_profile_signed_entitlement_tamper_fails_before_policy() {
        val algorithm = LicenseAlgorithm("ECDSA-P256-SHA256")
        val keyId = LicenseKeyId("prod-signing-key-v2")
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        val entitlement = LicenseEntitlement(
            id = LicenseId("license-s5-tamper"),
            subject = LicenseSubject("private-s5-subject"),
            productId = LicenseProductId("liliya-pro"),
            features = setOf(LicenseFeature("model.local")),
            version = LicenseVersion(1),
            signingKeyId = keyId,
            issuedAt = now.minusSeconds(60),
            notBefore = now.minusSeconds(30),
            expiresAt = now.plusSeconds(3600),
            offlineLeaseUntil = now.plusSeconds(1800),
            revocationEpoch = LicenseRevocationEpoch(9),
            replaySequence = LicenseReplaySequence(41)
        )
        val original = LicenseEntitlementCanonicalCodec.encode(entitlement)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(original.copyBytes())
        val signature = LicenseSignature.of(signer.sign())

        val tamperedBytes = original.copyBytes().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        val tampered = LicenseCanonicalPayload.of(tamperedBytes)

        val trustedKey = LicenseTrustedVerificationKey.of(
            keyId = keyId,
            algorithm = algorithm,
            material = pair.public.encoded
        )
        val result = LicenseVerifier(
            supportedSchemaVersion = LicenseVersion(1),
            supportedAlgorithms = setOf(algorithm),
            trustedKeys = LicenseTrustedKeyResolver { trustedKey },
            signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
        ).verify(
            LicenseSignedEnvelope(
                schemaVersion = LicenseVersion(1),
                algorithm = algorithm,
                signingKeyId = keyId,
                payload = tampered,
                signature = signature
            )
        )

        val rejected = assertIs<LicenseVerificationResult.Rejected>(result)
        assertEquals(LicenseVerificationRejection.INVALID_SIGNATURE, rejected.reason)
    }

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            correlationIds = CorrelationIdGenerator {
                "license-s5-compat-${sequence.incrementAndGet()}"
            }
        )
    }
}
