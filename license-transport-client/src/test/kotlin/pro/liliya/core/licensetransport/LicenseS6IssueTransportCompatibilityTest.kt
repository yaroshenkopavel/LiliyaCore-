package pro.liliya.core.licensetransport

import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Assumptions.assumeTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseDecision
import pro.liliya.core.license.LicenseFeature
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicensePolicy
import pro.liliya.core.license.LicensePolicyRequest
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceAuthenticationProof
import pro.liliya.core.license.LicenseServiceDigestTestProofVerifier
import pro.liliya.core.license.LicenseServiceEvidenceProfile
import pro.liliya.core.license.LicenseServiceEvidencePurpose
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServicePolicyContextResult
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseServiceSecurityScope
import pro.liliya.core.license.LicenseServiceSecurityState
import pro.liliya.core.license.LicenseServiceSecurityStateCanonicalCodec
import pro.liliya.core.license.LicenseServiceStateAcceptanceComposition
import pro.liliya.core.license.LicenseServiceStateAcceptanceResult
import pro.liliya.core.license.LicenseServiceStateEnvelope
import pro.liliya.core.license.LicenseServiceTrustedKeyResolver
import pro.liliya.core.license.LicenseServiceTrustedVerificationKey
import pro.liliya.core.license.LicenseSubject
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseTrustedVerificationKey
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.license.LicenseVerifier
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class LicenseS6IssueTransportCompatibilityTest {
    @Test
    fun real_http_issue_reaches_frozen_verifier_and_policy_and_stops_before_authority() {
        val endpoint = System.getenv("LIVE_S6_HTTP_ENDPOINT")
        val publicKeyPath = System.getenv("LIVE_S6_PUBLIC_KEY_DER_PATH")

        assumeTrue(!endpoint.isNullOrBlank(), "LIVE_S6_HTTP_ENDPOINT is not configured")
        assumeTrue(!publicKeyPath.isNullOrBlank(), "LIVE_S6_PUBLIC_KEY_DER_PATH is not configured")
        assumeTrue(Files.isRegularFile(Path.of(publicKeyPath!!)), "S6 public key file is missing")

        val request = LicenseServiceTransportRequest(
            protocolVersion = LicenseServiceProtocolVersion(1),
            operation = LicenseServiceOperation.ISSUE,
            productId = LicenseProductId("liliya-pro"),
            subjectReference = LicenseSubject("s6-live-subject"),
            requestId = LicenseServiceRequestId("s6-live-issue-001")
        )

        val transport = LicenseHttpTransportClient(
            config = LicenseHttpTransportConfig(
                endpoint = URL(endpoint!!),
                connectTimeoutMillis = 2_000,
                readTimeoutMillis = 5_000,
                developmentAllowInsecureHttp = true
            )
        )

        val signed = assertIs<LicenseClientTransportResult.Signed>(
            transport.execute(request)
        )

        val keyId = signed.envelope.signingKeyId
        val trustedKey = LicenseTrustedVerificationKey.of(
            keyId = keyId,
            algorithm = LicenseAlgorithm("ECDSA-P256-SHA256"),
            material = Files.readAllBytes(Path.of(publicKeyPath))
        )

        val verified = assertIs<LicenseVerificationResult.Verified>(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(LicenseAlgorithm("ECDSA-P256-SHA256")),
                trustedKeys = LicenseTrustedKeyResolver { requested ->
                    trustedKey.takeIf { it.keyId == requested }
                },
                signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
            ).verify(signed.envelope)
        )

        assertEquals(LicenseProductId("liliya-pro"), verified.entitlement.productId)
        assertEquals(LicenseSubject("s6-live-subject"), verified.entitlement.subject)
        assertEquals(LicenseKeyId("s6-openbao-v2"), verified.entitlement.signingKeyId)

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
            keyId = LicenseKeyId("s6-service-state-test-key"),
            profile = LicenseServiceEvidenceProfile("TEST-SERVICE-SHA256"),
            material = "S6-SERVICE-STATE-TEST-MATERIAL".encodeToByteArray()
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
                now = verified.entitlement.notBefore.plusSeconds(1),
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

        println(
            "LICENSING_S6_4_ISSUE_EVIDENCE=" +
                "{\"realHttp\":true," +
                "\"backendIssuedEnvelope\":true," +
                "\"clientTransport\":true," +
                "\"frozenVerifier\":true," +
                "\"serviceState\":true," +
                "\"policyContext\":true," +
                "\"licensePolicy\":true," +
                "\"stoppedBeforeAuthority\":true}"
        )
    }

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            correlationIds = CorrelationIdGenerator {
                "license-s6-issue-" + sequence.incrementAndGet()
            }
        )
    }
}
