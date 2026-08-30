package pro.liliya.core.license

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.authority.AuthorityDecision
import pro.liliya.core.authority.AuthorityManager
import pro.liliya.core.authority.AuthorityPolicy
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class LicenseCoreReadinessContractTest {
    private val now = Instant.parse("2026-08-30T18:40:00Z")
    private val algorithm = LicenseAlgorithm("TEST-SHA256")
    private val trustedKey = LicenseTrustedVerificationKey.of(
        keyId = LicenseKeyId("readiness-key"),
        algorithm = algorithm,
        material = "PRIVATE-READINESS-KEY-MATERIAL".toByteArray(StandardCharsets.UTF_8)
    )

    private data class ObservedFoundation(
        val foundation: FoundationComposition,
        val diagnostics: InMemoryDiagnosticSink,
        val logs: InMemoryLogWriter
    )

    private fun observedFoundation(): ObservedFoundation {
        val diagnostics = InMemoryDiagnosticSink()
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        return ObservedFoundation(
            foundation = FoundationComposition(
                diagnostics = DiagnosticRecorder(diagnostics),
                loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
                correlationIds = CorrelationIdGenerator {
                    "license-readiness-${sequence.incrementAndGet()}"
                }
            ),
            diagnostics = diagnostics,
            logs = logs
        )
    }

    private fun entitlement(
        id: String = "readiness-license",
        subject: String = "PRIVATE-LICENSE-SUBJECT",
        expiresAt: Instant? = now.plusSeconds(3_600),
        offlineLeaseUntil: Instant? = now.plusSeconds(1_800),
        replaySequence: LicenseReplaySequence? = LicenseReplaySequence(22),
        revocationEpoch: LicenseRevocationEpoch = LicenseRevocationEpoch(7)
    ) = LicenseEntitlement(
        id = LicenseId(id),
        subject = LicenseSubject(subject),
        productId = LicenseProductId("liliya-pro"),
        features = setOf(LicenseFeature("model.local")),
        version = LicenseVersion(4),
        signingKeyId = trustedKey.keyId,
        issuedAt = now.minusSeconds(120),
        notBefore = now.minusSeconds(60),
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
            signingKeyId = trustedKey.keyId,
            payload = payload,
            signature = LicenseDigestTestVerifier.signForTest(trustedKey, payload)
        )
        return assertIs<LicenseVerificationResult.Verified>(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(algorithm),
                trustedKeys = LicenseTrustedKeyResolver { requested ->
                    if (requested == trustedKey.keyId) trustedKey else null
                },
                signatureVerifier = LicenseDigestTestVerifier
            ).verify(envelope)
        )
    }

    @Test
    fun malformed_signed_evidence_never_reaches_policy_as_verified_entitlement() {
        val malformed = "PRIVATE-MALFORMED-LICENSE-PAYLOAD".licenseCanonicalPayload()
        val envelope = LicenseSignedEnvelope(
            schemaVersion = LicenseVersion(1),
            algorithm = algorithm,
            signingKeyId = trustedKey.keyId,
            payload = malformed,
            signature = LicenseDigestTestVerifier.signForTest(trustedKey, malformed)
        )
        val result = LicenseVerifier(
            supportedSchemaVersion = LicenseVersion(1),
            supportedAlgorithms = setOf(algorithm),
            trustedKeys = LicenseTrustedKeyResolver { trustedKey },
            signatureVerifier = LicenseDigestTestVerifier
        ).verify(envelope)

        assertEquals(
            LicenseVerificationRejection.INVALID_CANONICAL_PAYLOAD,
            assertIs<LicenseVerificationResult.Rejected>(result).reason
        )
    }

    @Test
    fun offline_lease_revocation_and_replay_boundaries_fail_closed_exactly() {
        val policy = LicensePolicy()
        val leaseEnd = now.plusSeconds(10)
        val evidence = verified(entitlement(offlineLeaseUntil = leaseEnd))
        val request = LicensePolicyRequest(
            productId = LicenseProductId("liliya-pro"),
            feature = LicenseFeature("model.local")
        )

        assertIs<LicenseDecision.Entitled>(
            policy.evaluate(
                evidence,
                request,
                LicensePolicyContext(
                    now = leaseEnd.minusNanos(1),
                    minimumRevocationEpoch = LicenseRevocationEpoch(7),
                    minimumReplaySequence = LicenseReplaySequence(22)
                )
            )
        )
        assertEquals(
            LicenseDenialReason.OFFLINE_LEASE_EXPIRED,
            assertIs<LicenseDecision.Denied>(
                policy.evaluate(
                    evidence,
                    request,
                    LicensePolicyContext(
                        now = leaseEnd,
                        minimumRevocationEpoch = LicenseRevocationEpoch(7),
                        minimumReplaySequence = LicenseReplaySequence(22)
                    )
                )
            ).reason
        )
        assertEquals(
            LicenseDenialReason.STALE_REVOCATION_EPOCH,
            assertIs<LicenseDecision.Denied>(
                policy.evaluate(
                    verified(entitlement(revocationEpoch = LicenseRevocationEpoch(6))),
                    request,
                    LicensePolicyContext(
                        now = now,
                        minimumRevocationEpoch = LicenseRevocationEpoch(7),
                        minimumReplaySequence = LicenseReplaySequence(22)
                    )
                )
            ).reason
        )
        assertEquals(
            LicenseDenialReason.STALE_REPLAY_SEQUENCE,
            assertIs<LicenseDecision.Denied>(
                policy.evaluate(
                    verified(entitlement(replaySequence = LicenseReplaySequence(21))),
                    request,
                    LicensePolicyContext(
                        now = now,
                        minimumRevocationEpoch = LicenseRevocationEpoch(7),
                        minimumReplaySequence = LicenseReplaySequence(22)
                    )
                )
            ).reason
        )
    }

    @Test
    fun license_denial_has_zero_authority_side_effects_and_cannot_be_reused_as_permission() {
        val observed = observedFoundation()
        var authorityCalls = 0
        val authority = AuthorityManager(
            policy = AuthorityPolicy {
                authorityCalls++
                AuthorityDecision.Granted
            },
            observability = observed.foundation.observability
        )
        val gate = LicenseAuthorityComposition(observed.foundation, authority)
        val evidence = verified(entitlement(expiresAt = now.plusSeconds(1), offlineLeaseUntil = null))
        val request = LicensePolicyRequest(
            productId = LicenseProductId("liliya-pro"),
            feature = LicenseFeature("model.local")
        )
        val authorityRequest = LicenseAuthorityRequest(
            principal = AuthorityPrincipal("assistant-core"),
            capability = CapabilityId("model.execute"),
            scope = AuthorityScope("local-model")
        )

        assertIs<LicenseAuthorityDecision.Authorized>(
            gate.authorize(
                evidence,
                request,
                LicensePolicyContext(now = now),
                authorityRequest
            )
        )
        assertEquals(1, authorityCalls)

        val expired = gate.authorize(
            evidence,
            request,
            LicensePolicyContext(now = now.plusSeconds(1)),
            authorityRequest
        )
        assertEquals(
            LicenseDenialReason.EXPIRED,
            assertIs<LicenseAuthorityDecision.LicenseDenied>(expired).reason
        )
        assertEquals(1, authorityCalls)
    }

    @Test
    fun concurrent_registration_is_exact_isolated_and_snapshot_deterministic() {
        val first = LicenseComposition(observedFoundation().foundation)
        val second = LicenseComposition(observedFoundation().foundation)
        val attempts = 12
        val executor = Executors.newFixedThreadPool(attempts)
        val ready = CountDownLatch(attempts)
        val start = CountDownLatch(1)
        val done = CountDownLatch(attempts)
        val registered = AtomicInteger(0)

        try {
            repeat(attempts) { index ->
                executor.submit {
                    try {
                        ready.countDown()
                        start.await()
                        val result = first.register(
                            entitlement(
                                id = "license-${attempts - index}",
                                subject = "private-subject-$index"
                            )
                        )
                        if (result is LicenseRegisterResult.Registered) registered.incrementAndGet()
                    } finally {
                        done.countDown()
                    }
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(attempts, registered.get())
            assertEquals(attempts, first.snapshotEntries().size)
            assertEquals(
                (1L..attempts.toLong()).toSet(),
                first.snapshotEntries().map { it.generation.value }.toSet()
            )
            assertEquals(
                (1..attempts).map { "license-$it" }.sorted(),
                first.snapshot().map { it.id.value }
            )
            assertTrue(second.snapshot().isEmpty())
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun normal_license_observability_never_emits_private_subject_payload_signature_or_key_material() {
        val observed = observedFoundation()
        val secretSubject = "PRIVATE-SUBJECT-DO-NOT-LOG"
        val evidence = verified(entitlement(subject = secretSubject))
        val verification = LicenseVerificationComposition(
            foundation = observed.foundation,
            supportedSchemaVersion = LicenseVersion(1),
            supportedAlgorithms = setOf(algorithm),
            trustedKeys = LicenseTrustedKeyResolver { trustedKey },
            signatureVerifier = LicenseDigestTestVerifier
        )
        verification.verify(evidence.envelope)

        LicensePolicyComposition(observed.foundation).evaluate(
            verified = evidence,
            request = LicensePolicyRequest(
                productId = LicenseProductId("liliya-pro"),
                feature = LicenseFeature("model.local")
            ),
            context = LicensePolicyContext(now = now)
        )

        val rendered = buildString {
            observed.logs.snapshot().forEach { append(it.toString()) }
            observed.diagnostics.snapshot().forEach { append(it.toString()) }
        }

        assertFalse(secretSubject in rendered)
        assertFalse("PRIVATE-READINESS-KEY-MATERIAL" in rendered)
        assertFalse("PRIVATE-MALFORMED-LICENSE-PAYLOAD" in rendered)
        assertFalse("payload=[" in rendered)
        assertFalse("signature=[" in rendered)
    }
}
