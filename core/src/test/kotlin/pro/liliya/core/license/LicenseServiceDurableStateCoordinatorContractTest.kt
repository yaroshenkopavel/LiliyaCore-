package pro.liliya.core.license

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
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

class LicenseServiceDurableStateCoordinatorContractTest {
    private val protocol = LicenseServiceProtocolVersion(1)
    private val purpose = LicenseServiceEvidencePurpose.SECURITY_STATE
    private val profile = LicenseServiceEvidenceProfile("TEST-SERVICE-SHA256")
    private val key = LicenseServiceTrustedVerificationKey.of(
        keyId = LicenseKeyId("service-state-key"),
        profile = profile,
        material = "PRIVATE-DURABLE-SERVICE-KEY".encodeToByteArray()
    )
    private val storeId = LicenseServiceDurableStoreId("license-security-state")
    private val protectorReference = LicenseServiceDurableStateProtectorReference(
        id = LicenseServiceDurableStateProtectorId("license-state-key"),
        generation = LicenseServiceDurableStateProtectorGeneration(1)
    )
    private val baseTime = Instant.parse("2026-09-01T10:00:00Z")

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
                    "license-durable-${sequence.incrementAndGet()}"
                }
            ),
            diagnostics = diagnostics,
            logs = logs
        )
    }

    private fun scope(subject: String = "PRIVATE-DURABLE-SUBJECT") =
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
        proofOverride: ByteArray? = null
    ): LicenseServiceStateEnvelope {
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
            proof = proofOverride?.let(LicenseServiceAuthenticationProof::of)
                ?: LicenseServiceDigestTestProofVerifier.signForTest(key, unsigned)
        )
    }

    private fun coordinator(
        observed: ObservedFoundation,
        backend: FakeBackend,
        protector: FakeProtector
    ) = LicenseServiceDurableStateCoordinator(
        foundation = observed.foundation,
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

    @Test
    fun failed_verification_performs_zero_durable_side_effects() {
        val observed = observedFoundation()
        val backend = FakeBackend()
        val protector = FakeProtector(protectorReference)
        val coordinator = coordinator(observed, backend, protector)

        val result = coordinator.verifyAndAccept(
            signedEnvelope(state(), proofOverride = byteArrayOf(9, 9, 9))
        )

        assertIs<LicenseServiceDurableStateAcceptanceResult.VerificationRejected>(result)
        assertEquals(0, backend.loadCalls)
        assertEquals(0, backend.commitCalls)
        assertEquals(0, protector.prepareCalls)
        assertEquals(0, protector.sealCalls)
        assertNull(coordinator.inspectPublished())
    }

    @Test
    fun first_acceptance_commits_revision_one_before_publication() {
        val observed = observedFoundation()
        val backend = FakeBackend()
        val protector = FakeProtector(protectorReference)
        val coordinator = coordinator(observed, backend, protector)

        val result = assertIs<LicenseServiceDurableStateAcceptanceResult.Advanced>(
            coordinator.verifyAndAccept(signedEnvelope(state()))
        )

        assertEquals(1L, result.snapshot.generation.value)
        assertEquals(1L, result.snapshot.backendRevision.value)
        assertEquals(1, protector.prepareCalls)
        assertEquals(1, protector.sealCalls)
        assertEquals(1, backend.commitCalls)
        assertEquals(0L, backend.lastExpectedRevision?.value)
        assertEquals(result.snapshot, coordinator.inspectPublished())
        assertTrue(backend.load() is LicenseServiceDurableBackendLoadResult.Loaded)
    }

    @Test
    fun semantic_duplicate_performs_zero_new_seal_or_commit() {
        val observed = observedFoundation()
        val backend = FakeBackend()
        val protector = FakeProtector(protectorReference)
        val coordinator = coordinator(observed, backend, protector)
        val envelope = signedEnvelope(state())

        assertIs<LicenseServiceDurableStateAcceptanceResult.Advanced>(
            coordinator.verifyAndAccept(envelope)
        )
        val commits = backend.commitCalls
        val seals = protector.sealCalls

        val duplicate = assertIs<LicenseServiceDurableStateAcceptanceResult.Unchanged>(
            coordinator.verifyAndAccept(envelope)
        )

        assertEquals(1L, duplicate.snapshot.generation.value)
        assertEquals(1L, duplicate.snapshot.backendRevision.value)
        assertEquals(commits, backend.commitCalls)
        assertEquals(seals, protector.sealCalls)
    }

    @Test
    fun stale_state_is_rejected_before_seal_and_commit() {
        val observed = observedFoundation()
        val backend = FakeBackend()
        val protector = FakeProtector(protectorReference)
        val coordinator = coordinator(observed, backend, protector)

        assertIs<LicenseServiceDurableStateAcceptanceResult.Advanced>(
            coordinator.verifyAndAccept(signedEnvelope(state(replay = 20)))
        )
        val commits = backend.commitCalls
        val seals = protector.sealCalls

        val rejected = assertIs<LicenseServiceDurableStateAcceptanceResult.StateRejected>(
            coordinator.verifyAndAccept(signedEnvelope(state(replay = 19)))
        )

        assertEquals(LicenseServiceStateAcceptanceRejection.STALE_REPLAY_SEQUENCE, rejected.reason)
        assertEquals(commits, backend.commitCalls)
        assertEquals(seals, protector.sealCalls)
        assertEquals(20L, coordinator.inspectPublished()!!.states.single().replaySequence!!.value)
    }

    @Test
    fun commit_conflict_never_publishes_candidate_and_never_retries() {
        val observed = observedFoundation()
        val backend = FakeBackend().apply {
            nextCommitResult = LicenseServiceDurableBackendCommitResult.Conflict
        }
        val protector = FakeProtector(protectorReference)
        val coordinator = coordinator(observed, backend, protector)

        val result = assertIs<LicenseServiceDurableStateAcceptanceResult.DurableRejected>(
            coordinator.verifyAndAccept(signedEnvelope(state()))
        )

        assertEquals(LicenseServiceDurableStateFailure.REVISION_CONFLICT, result.reason)
        assertEquals(1, backend.commitCalls)
        assertNull(coordinator.inspectPublished())
    }

    @Test
    fun known_commit_failure_never_publishes_candidate() {
        val observed = observedFoundation()
        val backend = FakeBackend().apply {
            nextCommitResult = LicenseServiceDurableBackendCommitResult.Failed
        }
        val protector = FakeProtector(protectorReference)
        val coordinator = coordinator(observed, backend, protector)

        val result = assertIs<LicenseServiceDurableStateAcceptanceResult.DurableRejected>(
            coordinator.verifyAndAccept(signedEnvelope(state()))
        )

        assertEquals(LicenseServiceDurableStateFailure.PERSISTENCE_FAILED, result.reason)
        assertEquals(1, backend.commitCalls)
        assertNull(coordinator.inspectPublished())
    }

    @Test
    fun uncertain_commit_never_publishes_and_is_not_retried() {
        val observed = observedFoundation()
        val backend = FakeBackend().apply {
            nextCommitResult = LicenseServiceDurableBackendCommitResult.Uncertain
        }
        val protector = FakeProtector(protectorReference)
        val coordinator = coordinator(observed, backend, protector)

        val result = assertIs<LicenseServiceDurableStateAcceptanceResult.DurableRejected>(
            coordinator.verifyAndAccept(signedEnvelope(state()))
        )

        assertEquals(LicenseServiceDurableStateFailure.PERSISTENCE_UNCERTAIN, result.reason)
        assertEquals(1, backend.commitCalls)
        assertNull(coordinator.inspectPublished())
    }

    @Test
    fun committed_revision_mismatch_never_publishes_candidate() {
        val observed = observedFoundation()
        val backend = FakeBackend().apply { commitRevisionOffset = 1L }
        val protector = FakeProtector(protectorReference)
        val coordinator = coordinator(observed, backend, protector)

        val result = assertIs<LicenseServiceDurableStateAcceptanceResult.DurableRejected>(
            coordinator.verifyAndAccept(signedEnvelope(state()))
        )

        assertEquals(LicenseServiceDurableStateFailure.REVISION_MISMATCH, result.reason)
        assertNull(coordinator.inspectPublished())
    }

    @Test
    fun existing_protector_with_missing_record_is_initialization_uncertain() {
        val observed = observedFoundation()
        val backend = FakeBackend()
        val protector = FakeProtector(protectorReference).apply {
            initializationResult = LicenseServiceDurableProtectorInitializationResult.Existing(
                protectorReference
            )
        }
        val coordinator = coordinator(observed, backend, protector)

        val result = assertIs<LicenseServiceDurableStateAcceptanceResult.DurableRejected>(
            coordinator.verifyAndAccept(signedEnvelope(state()))
        )

        assertEquals(LicenseServiceDurableStateFailure.INITIALIZATION_UNCERTAIN, result.reason)
        assertEquals(0, protector.sealCalls)
        assertEquals(0, backend.commitCalls)
        assertNull(coordinator.inspectPublished())
    }

    @Test
    fun loaded_revision_substitution_fails_closed_before_open() {
        val observed = observedFoundation()
        val backend = FakeBackend()
        val protector = FakeProtector(protectorReference)
        seed(
            backend = backend,
            protector = protector,
            snapshot = snapshot(state(), generation = 1, revision = 1)
        )
        backend.loadedRevisionOverride = LicenseServiceDurableBackendRevision(2)
        val coordinator = coordinator(observed, backend, protector)

        val result = assertIs<LicenseServiceDurableStateAcceptanceResult.DurableRejected>(
            coordinator.verifyAndAccept(signedEnvelope(state(replay = 12)))
        )

        assertEquals(LicenseServiceDurableStateFailure.REVISION_MISMATCH, result.reason)
        assertEquals(0, protector.openCalls)
        assertEquals(0, backend.commitCalls)
        assertNull(coordinator.inspectPublished())
    }

    @Test
    fun generation_binding_mismatch_is_recovery_rejected() {
        val observed = observedFoundation()
        val backend = FakeBackend()
        val protector = FakeProtector(protectorReference)
        val persisted = snapshot(state(), generation = 1, revision = 1)
        seed(
            backend = backend,
            protector = protector,
            snapshot = persisted,
            bindingGeneration = LicenseServiceDurableStateGeneration(2)
        )
        val coordinator = coordinator(observed, backend, protector)

        val result = assertIs<LicenseServiceDurableStateAcceptanceResult.DurableRejected>(
            coordinator.verifyAndAccept(signedEnvelope(state(replay = 12)))
        )

        assertEquals(LicenseServiceDurableStateFailure.RECOVERY_REJECTED, result.reason)
        assertEquals(0, backend.commitCalls)
        assertNull(coordinator.inspectPublished())
    }

    @Test
    fun successful_update_preserves_other_scopes_and_advances_once() {
        val observed = observedFoundation()
        val backend = FakeBackend()
        val protector = FakeProtector(protectorReference)
        val firstScope = scope("PRIVATE-SUBJECT-A")
        val secondScope = scope("PRIVATE-SUBJECT-B")
        val coordinator = coordinator(observed, backend, protector)

        assertIs<LicenseServiceDurableStateAcceptanceResult.Advanced>(
            coordinator.verifyAndAccept(signedEnvelope(state(firstScope, replay = 10)))
        )
        val second = assertIs<LicenseServiceDurableStateAcceptanceResult.Advanced>(
            coordinator.verifyAndAccept(signedEnvelope(state(secondScope, replay = 30)))
        )

        assertEquals(2L, second.snapshot.generation.value)
        assertEquals(2L, second.snapshot.backendRevision.value)
        assertEquals(2, second.snapshot.states.size)
        assertEquals(10L, second.snapshot.states.first { it.scope == firstScope }.replaySequence!!.value)
        assertEquals(30L, second.snapshot.states.first { it.scope == secondScope }.replaySequence!!.value)
        assertEquals(2, backend.commitCalls)
    }

    @Test
    fun generation_overflow_fails_before_seal_and_commit() {
        val observed = observedFoundation()
        val backend = FakeBackend()
        val protector = FakeProtector(protectorReference)
        seed(
            backend = backend,
            protector = protector,
            snapshot = snapshot(state(), generation = Long.MAX_VALUE, revision = 1)
        )
        val coordinator = coordinator(observed, backend, protector)
        val seals = protector.sealCalls

        val result = assertIs<LicenseServiceDurableStateAcceptanceResult.DurableRejected>(
            coordinator.verifyAndAccept(signedEnvelope(state(replay = 12)))
        )

        assertEquals(LicenseServiceDurableStateFailure.GENERATION_OVERFLOW, result.reason)
        assertEquals(seals, protector.sealCalls)
        assertEquals(0, backend.commitCalls)
    }

    @Test
    fun revision_overflow_fails_before_seal_and_commit() {
        val observed = observedFoundation()
        val backend = FakeBackend()
        val protector = FakeProtector(protectorReference)
        seed(
            backend = backend,
            protector = protector,
            snapshot = snapshot(state(), generation = 1, revision = Long.MAX_VALUE)
        )
        val coordinator = coordinator(observed, backend, protector)
        val seals = protector.sealCalls

        val result = assertIs<LicenseServiceDurableStateAcceptanceResult.DurableRejected>(
            coordinator.verifyAndAccept(signedEnvelope(state(replay = 12)))
        )

        assertEquals(LicenseServiceDurableStateFailure.REVISION_OVERFLOW, result.reason)
        assertEquals(seals, protector.sealCalls)
        assertEquals(0, backend.commitCalls)
    }

    @Test
    fun policy_context_is_available_only_after_successful_commit() {
        val observed = observedFoundation()
        val backend = FakeBackend()
        val protector = FakeProtector(protectorReference)
        val coordinator = coordinator(observed, backend, protector)
        val targetScope = scope()

        assertIs<LicenseServiceDurablePolicyContextResult.Missing>(
            coordinator.policyContext(targetScope, baseTime.plusSeconds(10), false)
        )

        assertIs<LicenseServiceDurableStateAcceptanceResult.Advanced>(
            coordinator.verifyAndAccept(signedEnvelope(state(targetScope)))
        )
        val available = assertIs<LicenseServiceDurablePolicyContextResult.Available>(
            coordinator.policyContext(targetScope, baseTime.plusSeconds(10), true)
        )
        assertEquals(7L, available.context.minimumRevocationEpoch.value)
        assertEquals(11L, available.context.minimumReplaySequence!!.value)
        assertTrue(available.context.suspiciousTimeOrReplayState)
    }

    @Test
    fun normal_observability_does_not_expose_private_durable_material() {
        val observed = observedFoundation()
        val backend = FakeBackend()
        val protector = FakeProtector(protectorReference)
        val privateSubject = "PRIVATE-DURABLE-SUBJECT-DO-NOT-LOG"
        val coordinator = coordinator(observed, backend, protector)

        assertIs<LicenseServiceDurableStateAcceptanceResult.Advanced>(
            coordinator.verifyAndAccept(signedEnvelope(state(scope(privateSubject))))
        )

        val rendered = observed.diagnostics.entries().joinToString("\n") + "\n" +
            observed.logs.events().joinToString("\n")
        assertTrue(privateSubject !in rendered)
        assertTrue(storeId.value !in rendered)
        assertTrue(protectorReference.id.value !in rendered)
        assertTrue("PRIVATE-DURABLE-SERVICE-KEY" !in rendered)
    }

    private fun snapshot(
        state: LicenseServiceSecurityState,
        generation: Long,
        revision: Long
    ) = LicenseServiceDurableStateSnapshot(
        states = listOf(state),
        generation = LicenseServiceDurableStateGeneration(generation),
        backendRevision = LicenseServiceDurableBackendRevision(revision),
        schemaVersion = LicenseServiceDurableStateSchemaVersion(1)
    )

    private fun seed(
        backend: FakeBackend,
        protector: FakeProtector,
        snapshot: LicenseServiceDurableStateSnapshot,
        bindingGeneration: LicenseServiceDurableStateGeneration = snapshot.generation
    ) {
        val payload = assertIs<LicenseServiceDurableStateEncodeResult.Encoded>(
            LicenseServiceDurableStateCanonicalCodec.encode(snapshot)
        ).payload
        val binding = LicenseServiceDurableStateBinding(
            version = LicenseServiceDurableStateEnvelopeVersion(1),
            purpose = LicenseServiceDurableStatePurpose.LICENSE_SERVICE_SECURITY_STATE,
            profile = LicenseServiceDurableStateEncryptionProfile.AES_256_GCM,
            storeId = storeId,
            generation = bindingGeneration,
            backendRevision = snapshot.backendRevision,
            protector = protectorReference,
            stateSchemaVersion = snapshot.schemaVersion
        )
        val envelope = protector.seedSeal(binding, payload)
        val encoded = assertIs<LicenseServiceDurableStateEnvelopeEncodeResult.Encoded>(
            LicenseServiceDurableStateEnvelopeCanonicalCodec.encode(envelope)
        ).payload
        backend.seed(snapshot.backendRevision, encoded)
        backend.resetCounters()
        protector.resetCounters()
    }

    private class FakeBackend : LicenseServiceDurableBackend {
        private var storedRevision: LicenseServiceDurableBackendRevision? = null
        private var storedEnvelope: LicenseServiceDurableStateEnvelopePayload? = null

        var loadCalls = 0
        var commitCalls = 0
        var lastExpectedRevision: LicenseServiceDurableExpectedRevision? = null
        var nextCommitResult: LicenseServiceDurableBackendCommitResult? = null
        var commitRevisionOffset: Long = 0L
        var loadedRevisionOverride: LicenseServiceDurableBackendRevision? = null

        override fun load(): LicenseServiceDurableBackendLoadResult {
            loadCalls += 1
            val envelope = storedEnvelope ?: return LicenseServiceDurableBackendLoadResult.Missing
            return LicenseServiceDurableBackendLoadResult.Loaded(
                revision = loadedRevisionOverride ?: storedRevision!!,
                envelope = envelope
            )
        }

        override fun commit(
            expectedRevision: LicenseServiceDurableExpectedRevision,
            envelope: LicenseServiceDurableStateEnvelopePayload
        ): LicenseServiceDurableBackendCommitResult {
            commitCalls += 1
            lastExpectedRevision = expectedRevision
            nextCommitResult?.let { return it }

            val current = storedRevision?.value ?: 0L
            if (expectedRevision.value != current) {
                return LicenseServiceDurableBackendCommitResult.Conflict
            }
            val decoded = assertIs<LicenseServiceDurableStateEnvelopeDecodeResult.Decoded>(
                LicenseServiceDurableStateEnvelopeCanonicalCodec.decode(envelope)
            )
            val candidate = decoded.envelope.binding.backendRevision
            storedRevision = candidate
            storedEnvelope = envelope
            return LicenseServiceDurableBackendCommitResult.Committed(
                LicenseServiceDurableBackendRevision(candidate.value + commitRevisionOffset)
            )
        }

        fun seed(
            revision: LicenseServiceDurableBackendRevision,
            envelope: LicenseServiceDurableStateEnvelopePayload
        ) {
            storedRevision = revision
            storedEnvelope = envelope
        }

        fun resetCounters() {
            loadCalls = 0
            commitCalls = 0
            lastExpectedRevision = null
        }
    }

    private class FakeProtector(
        private val reference: LicenseServiceDurableStateProtectorReference
    ) : LicenseServiceDurableStateProtector {
        var prepareCalls = 0
        var sealCalls = 0
        var openCalls = 0
        var initializationResult: LicenseServiceDurableProtectorInitializationResult =
            LicenseServiceDurableProtectorInitializationResult.Fresh(reference)

        private val plaintextByGeneration = mutableMapOf<Long, LicenseServiceDurableStatePayload>()

        override fun prepareInitialization(
            storeId: LicenseServiceDurableStoreId
        ): LicenseServiceDurableProtectorInitializationResult {
            prepareCalls += 1
            return initializationResult
        }

        override fun seal(
            binding: LicenseServiceDurableStateBinding,
            payload: LicenseServiceDurableStatePayload
        ): LicenseServiceDurableProtectorSealResult {
            sealCalls += 1
            return LicenseServiceDurableProtectorSealResult.Sealed(seedSeal(binding, payload))
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

        fun seedSeal(
            binding: LicenseServiceDurableStateBinding,
            payload: LicenseServiceDurableStatePayload
        ): LicenseServiceDurableStateEnvelope {
            plaintextByGeneration[binding.generation.value] = payload
            return LicenseServiceDurableStateEnvelope(
                binding = binding,
                nonce = ByteArray(12) { (binding.generation.value + it).toByte() },
                ciphertext = byteArrayOf(1, 2, 3, binding.generation.value.toByte()),
                authenticationTag = ByteArray(16) { 7 }
            )
        }

        fun resetCounters() {
            prepareCalls = 0
            sealCalls = 0
            openCalls = 0
        }
    }
}
