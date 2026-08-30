package pro.liliya.core.license

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class LicensePolicyContractTest {
    private val now = Instant.parse("2026-08-30T18:00:00Z")
    private val algorithm = LicenseAlgorithm("TEST-SHA256")
    private val key = LicenseTrustedVerificationKey.of(
        keyId = LicenseKeyId("trusted-policy-key"),
        algorithm = algorithm,
        material = "policy-test-key".toByteArray(StandardCharsets.UTF_8)
    )

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
            correlationIds = CorrelationIdGenerator { "license-policy-${sequence.incrementAndGet()}" }
        )
    }

    private fun entitlement(
        productId: LicenseProductId = LicenseProductId("liliya-pro"),
        features: Set<LicenseFeature> = setOf(LicenseFeature("model.local")),
        subject: LicenseSubject = LicenseSubject("private-policy-subject"),
        notBefore: Instant = now.minusSeconds(60),
        expiresAt: Instant? = now.plusSeconds(3_600),
        offlineLeaseUntil: Instant? = now.plusSeconds(1_800),
        revocationEpoch: LicenseRevocationEpoch = LicenseRevocationEpoch(5),
        replaySequence: LicenseReplaySequence? = LicenseReplaySequence(20)
    ) = LicenseEntitlement(
        id = LicenseId("license-policy-1"),
        subject = subject,
        productId = productId,
        features = features,
        version = LicenseVersion(3),
        signingKeyId = key.keyId,
        issuedAt = now.minusSeconds(120),
        notBefore = notBefore,
        expiresAt = expiresAt,
        offlineLeaseUntil = offlineLeaseUntil,
        revocationEpoch = revocationEpoch,
        replaySequence = replaySequence
    )

    private fun verified(entitlement: LicenseEntitlement): LicenseVerificationResult.Verified {
        val payload = LicenseEntitlementCanonicalCodec.encode(entitlement)
        val envelope = LicenseSignedEnvelope(
            schemaVersion = LicenseVersion(1),
            algorithm = algorithm,
            signingKeyId = key.keyId,
            payload = payload,
            signature = LicenseDigestTestVerifier.signForTest(key, payload)
        )
        val verifier = LicenseVerifier(
            supportedSchemaVersion = LicenseVersion(1),
            supportedAlgorithms = setOf(algorithm),
            trustedKeys = LicenseTrustedKeyResolver { requested -> if (requested == key.keyId) key else null },
            signatureVerifier = LicenseDigestTestVerifier
        )
        return assertIs<LicenseVerificationResult.Verified>(verifier.verify(envelope))
    }

    private fun request(
        productId: LicenseProductId = LicenseProductId("liliya-pro"),
        feature: LicenseFeature = LicenseFeature("model.local"),
        subject: LicenseSubject? = null
    ) = LicensePolicyRequest(productId, feature, subject)

    private fun context(
        current: Instant = now,
        minimumRevocationEpoch: LicenseRevocationEpoch = LicenseRevocationEpoch(5),
        minimumReplaySequence: LicenseReplaySequence? = LicenseReplaySequence(20),
        suspicious: Boolean = false
    ) = LicensePolicyContext(
        now = current,
        minimumRevocationEpoch = minimumRevocationEpoch,
        minimumReplaySequence = minimumReplaySequence,
        suspiciousTimeOrReplayState = suspicious
    )

    @Test
    fun verified_exact_request_at_valid_time_is_entitled_but_receipt_is_historical_evidence_only() {
        val policy = LicensePolicyComposition(foundation())
        val decision = assertIs<LicenseDecision.Entitled>(
            policy.evaluate(verified(entitlement()), request(), context())
        )

        assertEquals(LicenseId("license-policy-1"), decision.receipt.licenseId)
        assertEquals(LicenseFeature("model.local"), decision.receipt.feature)
        assertEquals(now, decision.receipt.evaluatedAt)
        assertFalse(decision.receipt.toString().contains("private-policy-subject"))
    }

    @Test
    fun product_feature_and_explicit_subject_mismatch_fail_closed() {
        val policy = LicensePolicy()
        val evidence = verified(entitlement())

        assertEquals(
            LicenseDenialReason.PRODUCT_MISMATCH,
            assertIs<LicenseDecision.Denied>(
                policy.evaluate(evidence, request(productId = LicenseProductId("other-product")), context())
            ).reason
        )
        assertEquals(
            LicenseDenialReason.FEATURE_NOT_ENTITLED,
            assertIs<LicenseDecision.Denied>(
                policy.evaluate(evidence, request(feature = LicenseFeature("cloud.premium")), context())
            ).reason
        )
        assertEquals(
            LicenseDenialReason.SUBJECT_MISMATCH,
            assertIs<LicenseDecision.Denied>(
                policy.evaluate(
                    evidence,
                    request(subject = LicenseSubject("different-private-subject")),
                    context()
                )
            ).reason
        )
    }

    @Test
    fun not_before_is_inclusive_and_expiry_is_exclusive() {
        val policy = LicensePolicy()
        val notBefore = now
        val expiry = now.plusSeconds(60)
        val evidence = verified(
            entitlement(
                notBefore = notBefore,
                expiresAt = expiry,
                offlineLeaseUntil = null
            )
        )

        assertIs<LicenseDecision.Entitled>(
            policy.evaluate(evidence, request(), context(current = notBefore))
        )
        assertEquals(
            LicenseDenialReason.NOT_YET_VALID,
            assertIs<LicenseDecision.Denied>(
                policy.evaluate(evidence, request(), context(current = notBefore.minusNanos(1)))
            ).reason
        )
        assertEquals(
            LicenseDenialReason.EXPIRED,
            assertIs<LicenseDecision.Denied>(
                policy.evaluate(evidence, request(), context(current = expiry))
            ).reason
        )
    }

    @Test
    fun offline_lease_deadline_is_exclusive_when_present() {
        val policy = LicensePolicy()
        val leaseEnd = now.plusSeconds(10)
        val evidence = verified(entitlement(offlineLeaseUntil = leaseEnd))

        assertIs<LicenseDecision.Entitled>(
            policy.evaluate(evidence, request(), context(current = leaseEnd.minusNanos(1)))
        )
        assertEquals(
            LicenseDenialReason.OFFLINE_LEASE_EXPIRED,
            assertIs<LicenseDecision.Denied>(
                policy.evaluate(evidence, request(), context(current = leaseEnd))
            ).reason
        )
    }

    @Test
    fun stale_revocation_epoch_and_replay_sequence_fail_closed() {
        val policy = LicensePolicy()

        assertEquals(
            LicenseDenialReason.STALE_REVOCATION_EPOCH,
            assertIs<LicenseDecision.Denied>(
                policy.evaluate(
                    verified(entitlement(revocationEpoch = LicenseRevocationEpoch(4))),
                    request(),
                    context(minimumRevocationEpoch = LicenseRevocationEpoch(5))
                )
            ).reason
        )
        assertEquals(
            LicenseDenialReason.REPLAY_SEQUENCE_MISSING,
            assertIs<LicenseDecision.Denied>(
                policy.evaluate(
                    verified(entitlement(replaySequence = null)),
                    request(),
                    context(minimumReplaySequence = LicenseReplaySequence(20))
                )
            ).reason
        )
        assertEquals(
            LicenseDenialReason.STALE_REPLAY_SEQUENCE,
            assertIs<LicenseDecision.Denied>(
                policy.evaluate(
                    verified(entitlement(replaySequence = LicenseReplaySequence(19))),
                    request(),
                    context(minimumReplaySequence = LicenseReplaySequence(20))
                )
            ).reason
        )
    }

    @Test
    fun suspicious_time_or_replay_state_denies_before_other_positive_evidence() {
        val decision = LicensePolicy().evaluate(
            verified(entitlement()),
            request(),
            context(suspicious = true)
        )
        assertEquals(
            LicenseDenialReason.SUSPICIOUS_TIME_OR_REPLAY_STATE,
            assertIs<LicenseDecision.Denied>(decision).reason
        )
    }

    @Test
    fun normal_rendering_redacts_subject_and_verified_result_does_not_dump_payload() {
        val evidence = verified(entitlement())
        assertFalse(request(subject = LicenseSubject("private-policy-subject")).toString().contains("private-policy-subject"))
        assertFalse(evidence.toString().contains("private-policy-subject"))
        assertFalse(evidence.toString().contains("payload"))
    }
}
