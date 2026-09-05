package pro.liliya.core.license

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

// Compatibility helpers for the focused Slice 4B test package. Production sinks expose snapshots.
internal fun InMemoryDiagnosticSink.entries() = snapshot()
internal fun InMemoryLogWriter.events() = snapshot()

class LicenseServiceDurableStateCoordinatorInitializationContractTest {
    private val protocol = LicenseServiceProtocolVersion(1)
    private val purpose = LicenseServiceEvidencePurpose.SECURITY_STATE
    private val profile = LicenseServiceEvidenceProfile("TEST-SERVICE-SHA256")
    private val key = LicenseServiceTrustedVerificationKey.of(
        keyId = LicenseKeyId("service-state-key"),
        profile = profile,
        material = "PRIVATE-DURABLE-INIT-KEY".encodeToByteArray()
    )
    private val storeId = LicenseServiceDurableStoreId("license-security-state")
    private val protectorReference = LicenseServiceDurableStateProtectorReference(
        id = LicenseServiceDurableStateProtectorId("license-state-key"),
        generation = LicenseServiceDurableStateProtectorGeneration(1)
    )
    private val baseTime = Instant.parse("2026-09-01T10:00:00Z")

    @Test
    fun fresh_protector_then_seal_failure_blocks_repeat_initialization() {
        val backend = FakeBackend()
        val protector = FakeProtector(protectorReference).apply {
            sealFailure = LicenseServiceDurableProtectorFailure.FAILED
        }
        val coordinator = coordinator(backend, protector)
        val envelope = signedEnvelope(replay = 11)

        val first = assertIs<LicenseServiceDurableStateAcceptanceResult.DurableRejected>(
            coordinator.verifyAndAccept(envelope)
        )
        assertEquals(LicenseServiceDurableStateFailure.PERSISTENCE_FAILED, first.reason)
        assertEquals(1, backend.loadCalls)
        assertEquals(1, protector.prepareCalls)
        assertEquals(1, protector.sealCalls)
        assertEquals(0, backend.commitCalls)
        assertNull(coordinator.inspectPublished())

        protector.sealFailure = null
        val second = assertIs<LicenseServiceDurableStateAcceptanceResult.DurableRejected>(
            coordinator.verifyAndAccept(envelope)
        )
        assertEquals(LicenseServiceDurableStateFailure.INITIALIZATION_UNCERTAIN, second.reason)
        assertEquals(1, backend.loadCalls)
        assertEquals(1, protector.prepareCalls)
        assertEquals(1, protector.sealCalls)
        assertEquals(0, backend.commitCalls)
        assertNull(coordinator.inspectPublished())
    }

    @Test
    fun fresh_protector_then_uncertain_commit_blocks_any_automatic_retry() {
        val backend = FakeBackend().apply {
            nextCommitResult = LicenseServiceDurableBackendCommitResult.Uncertain
        }
        val protector = FakeProtector(protectorReference)
        val coordinator = coordinator(backend, protector)
        val envelope = signedEnvelope(replay = 11)

        val first = assertIs<LicenseServiceDurableStateAcceptanceResult.DurableRejected>(
            coordinator.verifyAndAccept(envelope)
        )
        assertEquals(LicenseServiceDurableStateFailure.PERSISTENCE_UNCERTAIN, first.reason)
        assertEquals(1, backend.commitCalls)
        assertNull(coordinator.inspectPublished())

        backend.nextCommitResult = null
        val second = assertIs<LicenseServiceDurableStateAcceptanceResult.DurableRejected>(
            coordinator.verifyAndAccept(envelope)
        )
        assertEquals(LicenseServiceDurableStateFailure.INITIALIZATION_UNCERTAIN, second.reason)
        assertEquals(1, backend.loadCalls)
        assertEquals(1, protector.prepareCalls)
        assertEquals(1, protector.sealCalls)
        assertEquals(1, backend.commitCalls)
        assertNull(coordinator.inspectPublished())
    }

    @Test
    fun successful_first_commit_clears_local_initialization_barrier() {
        val backend = FakeBackend()
        val protector = FakeProtector(protectorReference)
        val coordinator = coordinator(backend, protector)

        val first = assertIs<LicenseServiceDurableStateAcceptanceResult.Advanced>(
            coordinator.verifyAndAccept(signedEnvelope(replay = 11))
        )
        assertEquals(1L, first.snapshot.generation.value)
        assertEquals(1L, first.snapshot.backendRevision.value)

        val second = assertIs<LicenseServiceDurableStateAcceptanceResult.Advanced>(
            coordinator.verifyAndAccept(signedEnvelope(replay = 12))
        )
        assertEquals(2L, second.snapshot.generation.value)
        assertEquals(2L, second.snapshot.backendRevision.value)
        assertEquals(1, protector.prepareCalls)
        assertEquals(2, protector.sealCalls)
        assertEquals(2, backend.commitCalls)
        assertEquals(second.snapshot, coordinator.inspectPublished())
    }

    private fun coordinator(
        backend: FakeBackend,
        protector: FakeProtector
    ): LicenseServiceDurableStateCoordinator {
        val diagnostics = InMemoryDiagnosticSink()
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator {
                "license-durable-init-${sequence.incrementAndGet()}"
            }
        )
        return LicenseServiceDurableStateCoordinator(
            foundation = foundation,
            storeId = storeId,
            supportedProtocolVersion = protocol,
            supportedPurposes = setOf(purpose),
            supportedProfiles = setOf(profile),
            trustedKeys = LicenseServiceTrustedKeyResolver { keyId, requestedProfile ->
                key.takeIf { it.keyId == keyId && it.profile == requestedProfile }
            },
            proofVerifier = LicenseServiceDigestTestProofVerifier,
            backend = backend,
            protector = protector
        )
    }

    private fun signedEnvelope(replay: Long): LicenseServiceStateEnvelope {
        val state = LicenseServiceSecurityState(
            scope = LicenseServiceSecurityScope(
                productId = LicenseProductId("liliya-pro"),
                subject = LicenseSubject("PRIVATE-DURABLE-INIT-SUBJECT")
            ),
            revocationEpoch = LicenseRevocationEpoch(7),
            replaySequence = LicenseReplaySequence(replay),
            serverTime = baseTime.plusSeconds(replay)
        )
        val unsigned = LicenseServiceStateEnvelope(
            protocolVersion = protocol,
            purpose = purpose,
            profile = profile,
            signingKeyId = key.keyId,
            payload = LicenseServiceSecurityStateCanonicalCodec.encode(state),
            proof = LicenseServiceAuthenticationProof.of(byteArrayOf(1))
        )
        return LicenseServiceStateEnvelope(
            protocolVersion = unsigned.protocolVersion,
            purpose = unsigned.purpose,
            profile = unsigned.profile,
            signingKeyId = unsigned.signingKeyId,
            payload = unsigned.payload,
            proof = LicenseServiceDigestTestProofVerifier.signForTest(key, unsigned)
        )
    }

    private class FakeBackend : LicenseServiceDurableBackend {
        private var revision: LicenseServiceDurableBackendRevision? = null
        private var envelope: LicenseServiceDurableStateEnvelopePayload? = null

        var loadCalls = 0
        var commitCalls = 0
        var nextCommitResult: LicenseServiceDurableBackendCommitResult? = null

        override fun load(): LicenseServiceDurableBackendLoadResult {
            loadCalls += 1
            val currentEnvelope = envelope ?: return LicenseServiceDurableBackendLoadResult.Missing
            return LicenseServiceDurableBackendLoadResult.Loaded(revision!!, currentEnvelope)
        }

        override fun commit(
            expectedRevision: LicenseServiceDurableExpectedRevision,
            envelope: LicenseServiceDurableStateEnvelopePayload
        ): LicenseServiceDurableBackendCommitResult {
            commitCalls += 1
            nextCommitResult?.let { return it }
            val currentRevision = revision?.value ?: 0L
            if (expectedRevision.value != currentRevision) {
                return LicenseServiceDurableBackendCommitResult.Conflict
            }
            val decoded = assertIs<LicenseServiceDurableStateEnvelopeDecodeResult.Decoded>(
                LicenseServiceDurableStateEnvelopeCanonicalCodec.decode(envelope)
            )
            val candidateRevision = decoded.envelope.binding.backendRevision
            revision = candidateRevision
            this.envelope = envelope
            return LicenseServiceDurableBackendCommitResult.Committed(candidateRevision)
        }
    }

    private class FakeProtector(
        private val reference: LicenseServiceDurableStateProtectorReference
    ) : LicenseServiceDurableStateProtector {
        var prepareCalls = 0
        var sealCalls = 0
        var openCalls = 0
        var sealFailure: LicenseServiceDurableProtectorFailure? = null

        private val plaintextByGeneration = mutableMapOf<Long, LicenseServiceDurableStatePayload>()

        override fun prepareInitialization(
            storeId: LicenseServiceDurableStoreId
        ): LicenseServiceDurableProtectorInitializationResult {
            prepareCalls += 1
            return LicenseServiceDurableProtectorInitializationResult.Fresh(reference)
        }

        override fun seal(
            binding: LicenseServiceDurableStateBinding,
            payload: LicenseServiceDurableStatePayload
        ): LicenseServiceDurableProtectorSealResult {
            sealCalls += 1
            sealFailure?.let {
                return LicenseServiceDurableProtectorSealResult.Rejected(it)
            }
            plaintextByGeneration[binding.generation.value] = payload
            return LicenseServiceDurableProtectorSealResult.Sealed(
                LicenseServiceDurableStateEnvelope(
                    binding = binding,
                    nonce = ByteArray(12) { (binding.generation.value + it).toByte() },
                    ciphertext = byteArrayOf(1, 2, 3, binding.generation.value.toByte()),
                    authenticationTag = ByteArray(16) { 7 }
                )
            )
        }

        override fun open(
            envelope: LicenseServiceDurableStateEnvelope
        ): LicenseServiceDurableProtectorOpenResult {
            openCalls += 1
            val payload = plaintextByGeneration[envelope.binding.generation.value]
                ?: return LicenseServiceDurableProtectorOpenResult.Rejected(
                    LicenseServiceDurableProtectorFailure.AUTHENTICATION_FAILED
                )
            return LicenseServiceDurableProtectorOpenResult.Opened(payload)
        }
    }
}
