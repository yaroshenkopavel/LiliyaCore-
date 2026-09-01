package pro.liliya.core.license

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class LicenseServiceStateAcceptanceContractTest {
    private val protocol = LicenseServiceProtocolVersion(1)
    private val purpose = LicenseServiceEvidencePurpose.SECURITY_STATE
    private val profile = LicenseServiceEvidenceProfile("TEST-SERVICE-SHA256")
    private val key = LicenseServiceTrustedVerificationKey.of(
        keyId = LicenseKeyId("service-state-key"),
        profile = profile,
        material = "PRIVATE-SERVICE-ACCEPTANCE-KEY".encodeToByteArray()
    )
    private val baseTime = Instant.parse("2026-09-01T08:00:00Z")

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
                    "license-service-acceptance-${sequence.incrementAndGet()}"
                }
            ),
            diagnostics = diagnostics,
            logs = logs
        )
    }

    private fun scope(subject: String = "PRIVATE-SERVICE-SUBJECT") =
        LicenseServiceSecurityScope(
            productId = LicenseProductId("liliya-pro"),
            subject = LicenseSubject(subject)
        )

    private fun state(
        scope: LicenseServiceSecurityScope = scope(),
        revocation: Long? = 7,
        replay: Long? = 11,
        serverTime: Instant? = baseTime
    ) = LicenseServiceSecurityState(
        scope = scope,
        revocationEpoch = revocation?.let(::LicenseRevocationEpoch),
        replaySequence = replay?.let(::LicenseReplaySequence),
        serverTime = serverTime
    )

    private fun signedEnvelope(
        state: LicenseServiceSecurityState,
        signingKey: LicenseServiceTrustedVerificationKey = key
    ): LicenseServiceStateEnvelope {
        val unsigned = LicenseServiceStateEnvelope(
            protocolVersion = protocol,
            purpose = purpose,
            profile = signingKey.profile,
            signingKeyId = signingKey.keyId,
            payload = LicenseServiceSecurityStateCanonicalCodec.encode(state),
            proof = LicenseServiceAuthenticationProof.of(byteArrayOf(1))
        )
        return LicenseServiceStateEnvelope(
            protocolVersion = unsigned.protocolVersion,
            purpose = unsigned.purpose,
            profile = unsigned.profile,
            signingKeyId = unsigned.signingKeyId,
            payload = unsigned.payload,
            proof = LicenseServiceDigestTestProofVerifier.signForTest(signingKey, unsigned)
        )
    }

    private fun composition(foundation: FoundationComposition) =
        LicenseServiceStateAcceptanceComposition(
            foundation = foundation,
            supportedProtocolVersion = protocol,
            supportedPurposes = setOf(purpose),
            supportedProfiles = setOf(profile),
            trustedKeys = LicenseServiceTrustedKeyResolver { keyId, requestedProfile ->
                key.takeIf { it.keyId == keyId && it.profile == requestedProfile }
            },
            proofVerifier = LicenseServiceDigestTestProofVerifier
        )

    @Test
    fun failed_verification_never_creates_or_advances_retained_state() {
        val observed = observedFoundation()
        val composition = composition(observed.foundation)
        val expectedScope = scope()
        val valid = signedEnvelope(state(scope = expectedScope))
        val invalid = LicenseServiceStateEnvelope(
            protocolVersion = valid.protocolVersion,
            purpose = valid.purpose,
            profile = valid.profile,
            signingKeyId = valid.signingKeyId,
            payload = valid.payload,
            proof = LicenseServiceAuthenticationProof.of(byteArrayOf(9, 9, 9))
        )

        val rejected = composition.verifyAndAccept(invalid)

        assertEquals(
            LicenseServiceStateVerificationRejection.INVALID_PROOF,
            assertIs<LicenseServiceStateAcceptanceResult.VerificationRejected>(rejected).reason
        )
        assertNull(composition.inspect(expectedScope))

        val accepted = assertIs<LicenseServiceStateAcceptanceResult.Advanced>(
            composition.verifyAndAccept(valid)
        )
        assertEquals(1L, accepted.snapshot.generation.value)
    }

    @Test
    fun duplicate_is_idempotent_and_missing_signals_never_erase_retained_minima() {
        val composition = composition(observedFoundation().foundation)
        val expectedScope = scope()
        val initialState = state(scope = expectedScope)

        val first = assertIs<LicenseServiceStateAcceptanceResult.Advanced>(
            composition.verifyAndAccept(signedEnvelope(initialState))
        )
        val duplicate = assertIs<LicenseServiceStateAcceptanceResult.Unchanged>(
            composition.verifyAndAccept(signedEnvelope(initialState))
        )
        val partialAdvance = assertIs<LicenseServiceStateAcceptanceResult.Advanced>(
            composition.verifyAndAccept(
                signedEnvelope(
                    state(
                        scope = expectedScope,
                        revocation = 8,
                        replay = null,
                        serverTime = null
                    )
                )
            )
        )

        assertEquals(1L, first.snapshot.generation.value)
        assertEquals(1L, duplicate.snapshot.generation.value)
        assertEquals(2L, partialAdvance.snapshot.generation.value)
        assertEquals(LicenseRevocationEpoch(8), partialAdvance.snapshot.state.revocationEpoch)
        assertEquals(LicenseReplaySequence(11), partialAdvance.snapshot.state.replaySequence)
        assertEquals(baseTime, partialAdvance.snapshot.state.serverTime)
    }

    @Test
    fun any_regressing_signal_rejects_the_entire_update_atomically() {
        val composition = composition(observedFoundation().foundation)
        val expectedScope = scope()
        val initial = assertIs<LicenseServiceStateAcceptanceResult.Advanced>(
            composition.verifyAndAccept(
                signedEnvelope(
                    state(
                        scope = expectedScope,
                        revocation = 7,
                        replay = 11,
                        serverTime = baseTime
                    )
                )
            )
        )

        val rejected = composition.verifyAndAccept(
            signedEnvelope(
                state(
                    scope = expectedScope,
                    revocation = 8,
                    replay = 10,
                    serverTime = baseTime.plusSeconds(30)
                )
            )
        )

        assertEquals(
            LicenseServiceStateAcceptanceRejection.STALE_REPLAY_SEQUENCE,
            assertIs<LicenseServiceStateAcceptanceResult.StateRejected>(rejected).reason
        )
        val retained = composition.inspect(expectedScope)!!
        assertEquals(initial.snapshot.generation, retained.generation)
        assertEquals(LicenseRevocationEpoch(7), retained.state.revocationEpoch)
        assertEquals(LicenseReplaySequence(11), retained.state.replaySequence)
        assertEquals(baseTime, retained.state.serverTime)
    }

    @Test
    fun independent_subject_scopes_never_advance_each_other() {
        val composition = composition(observedFoundation().foundation)
        val firstScope = scope("PRIVATE-SUBJECT-A")
        val secondScope = scope("PRIVATE-SUBJECT-B")

        composition.verifyAndAccept(
            signedEnvelope(state(scope = firstScope, revocation = 9, replay = 20))
        )
        composition.verifyAndAccept(
            signedEnvelope(state(scope = secondScope, revocation = 3, replay = 4))
        )

        assertEquals(LicenseRevocationEpoch(9), composition.inspect(firstScope)?.state?.revocationEpoch)
        assertEquals(LicenseReplaySequence(20), composition.inspect(firstScope)?.state?.replaySequence)
        assertEquals(LicenseRevocationEpoch(3), composition.inspect(secondScope)?.state?.revocationEpoch)
        assertEquals(LicenseReplaySequence(4), composition.inspect(secondScope)?.state?.replaySequence)
    }

    @Test
    fun policy_context_uses_retained_minima_but_keeps_time_suspicion_explicit() {
        val composition = composition(observedFoundation().foundation)
        val expectedScope = scope()
        val serverTime = baseTime.plusSeconds(60)
        composition.verifyAndAccept(
            signedEnvelope(
                state(
                    scope = expectedScope,
                    revocation = 12,
                    replay = 31,
                    serverTime = serverTime
                )
            )
        )

        assertIs<LicenseServicePolicyContextResult.Missing>(
            composition.policyContext(
                scope = scope("PRIVATE-OTHER-SUBJECT"),
                now = baseTime,
                suspiciousTimeOrReplayState = false
            )
        )

        val available = assertIs<LicenseServicePolicyContextResult.Available>(
            composition.policyContext(
                scope = expectedScope,
                now = baseTime,
                suspiciousTimeOrReplayState = true
            )
        )
        assertEquals(baseTime, available.context.now)
        assertEquals(LicenseRevocationEpoch(12), available.context.minimumRevocationEpoch)
        assertEquals(LicenseReplaySequence(31), available.context.minimumReplaySequence)
        assertTrue(available.context.suspiciousTimeOrReplayState)
        assertEquals(serverTime, available.latestServerTime)
    }

    @Test
    fun concurrent_comparable_updates_linearize_to_highest_authenticated_state() {
        val composition = composition(observedFoundation().foundation)
        val expectedScope = scope()
        val attempts = 8
        val executor = Executors.newFixedThreadPool(attempts)
        val ready = CountDownLatch(attempts)
        val start = CountDownLatch(1)
        val done = CountDownLatch(attempts)

        try {
            repeat(attempts) { index ->
                executor.submit {
                    try {
                        ready.countDown()
                        start.await()
                        val value = (index + 1).toLong()
                        composition.verifyAndAccept(
                            signedEnvelope(
                                state(
                                    scope = expectedScope,
                                    revocation = value,
                                    replay = value,
                                    serverTime = baseTime.plusSeconds(value)
                                )
                            )
                        )
                    } finally {
                        done.countDown()
                    }
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))

            val retained = composition.inspect(expectedScope)!!
            assertEquals(LicenseRevocationEpoch(attempts.toLong()), retained.state.revocationEpoch)
            assertEquals(LicenseReplaySequence(attempts.toLong()), retained.state.replaySequence)
            assertEquals(baseTime.plusSeconds(attempts.toLong()), retained.state.serverTime)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun normal_observability_never_exposes_private_subject_key_payload_or_proof() {
        val observed = observedFoundation()
        val composition = composition(observed.foundation)
        val secretSubject = "PRIVATE-SUBJECT-DO-NOT-LOG"
        val envelope = signedEnvelope(state(scope = scope(secretSubject)))

        assertIs<LicenseServiceStateAcceptanceResult.Advanced>(
            composition.verifyAndAccept(envelope)
        )

        val rendered = buildString {
            observed.logs.snapshot().forEach { append(it.toString()) }
            observed.diagnostics.snapshot().forEach { append(it.toString()) }
            append(composition.inspect(scope(secretSubject)))
        }

        assertFalse(secretSubject in rendered)
        assertFalse("PRIVATE-SERVICE-ACCEPTANCE-KEY" in rendered)
        assertFalse(envelope.payload.copyBytes().decodeToString() in rendered)
        assertFalse(envelope.proof.copyBytes().decodeToString() in rendered)
    }
}
