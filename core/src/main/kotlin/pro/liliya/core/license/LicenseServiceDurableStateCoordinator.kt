package pro.liliya.core.license

import java.time.Instant
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

@JvmInline
value class LicenseServiceDurableExpectedRevision(val value: Long) {
    init {
        require(value >= 0L) { "license service durable expected revision must not be negative" }
    }

    override fun toString(): String = value.toString()
}

enum class LicenseServiceDurableStateFailure {
    CORRUPT,
    INCOMPATIBLE,
    AUTHENTICATION_FAILED,
    PROTECTOR_MISSING,
    PROTECTOR_INVALIDATED,
    STALE_PROTECTOR_OWNERSHIP,
    REQUIRED_SECURITY_LEVEL_UNAVAILABLE,
    INITIALIZATION_UNCERTAIN,
    REVISION_CONFLICT,
    REVISION_MISMATCH,
    REVISION_OVERFLOW,
    GENERATION_OVERFLOW,
    PERSISTENCE_FAILED,
    PERSISTENCE_UNCERTAIN,
    RECOVERY_REJECTED
}

sealed interface LicenseServiceDurableBackendLoadResult {
    data object Missing : LicenseServiceDurableBackendLoadResult

    class Loaded(
        val revision: LicenseServiceDurableBackendRevision,
        val envelope: LicenseServiceDurableStateEnvelopePayload
    ) : LicenseServiceDurableBackendLoadResult

    data object Corrupt : LicenseServiceDurableBackendLoadResult
    data object Incompatible : LicenseServiceDurableBackendLoadResult
    data object Failed : LicenseServiceDurableBackendLoadResult
}

sealed interface LicenseServiceDurableBackendCommitResult {
    data class Committed(val revision: LicenseServiceDurableBackendRevision) :
        LicenseServiceDurableBackendCommitResult

    data object Conflict : LicenseServiceDurableBackendCommitResult

    /** The backend guarantees that the candidate did not become the committed revision. */
    data object Failed : LicenseServiceDurableBackendCommitResult

    /** The backend cannot prove whether the candidate became durable. No automatic retry is allowed. */
    data object Uncertain : LicenseServiceDurableBackendCommitResult
}

interface LicenseServiceDurableBackend {
    fun load(): LicenseServiceDurableBackendLoadResult

    fun commit(
        expectedRevision: LicenseServiceDurableExpectedRevision,
        envelope: LicenseServiceDurableStateEnvelopePayload
    ): LicenseServiceDurableBackendCommitResult
}

enum class LicenseServiceDurableProtectorFailure {
    AUTHENTICATION_FAILED,
    PROTECTOR_MISSING,
    PROTECTOR_INVALIDATED,
    STALE_PROTECTOR_OWNERSHIP,
    REQUIRED_SECURITY_LEVEL_UNAVAILABLE,
    FAILED
}

sealed interface LicenseServiceDurableProtectorInitializationResult {
    /** A protector lineage created for this explicit same-process initialization attempt. */
    data class Fresh(val reference: LicenseServiceDurableStateProtectorReference) :
        LicenseServiceDurableProtectorInitializationResult

    /** A lineage already exists while the durable record is missing; initialization is uncertain. */
    data class Existing(val reference: LicenseServiceDurableStateProtectorReference) :
        LicenseServiceDurableProtectorInitializationResult

    data class Rejected(val reason: LicenseServiceDurableProtectorFailure) :
        LicenseServiceDurableProtectorInitializationResult
}

sealed interface LicenseServiceDurableProtectorSealResult {
    data class Sealed(val envelope: LicenseServiceDurableStateEnvelope) :
        LicenseServiceDurableProtectorSealResult

    data class Rejected(val reason: LicenseServiceDurableProtectorFailure) :
        LicenseServiceDurableProtectorSealResult
}

sealed interface LicenseServiceDurableProtectorOpenResult {
    data class Opened(val payload: LicenseServiceDurableStatePayload) :
        LicenseServiceDurableProtectorOpenResult

    data class Rejected(val reason: LicenseServiceDurableProtectorFailure) :
        LicenseServiceDurableProtectorOpenResult
}

/**
 * Platform-neutral protector port. A future Android implementation owns the dedicated Licensing
 * Keystore AES key; this Core port does not expose key material and does not extend Device Key.
 */
interface LicenseServiceDurableStateProtector {
    fun prepareInitialization(
        storeId: LicenseServiceDurableStoreId
    ): LicenseServiceDurableProtectorInitializationResult

    fun seal(
        binding: LicenseServiceDurableStateBinding,
        payload: LicenseServiceDurableStatePayload
    ): LicenseServiceDurableProtectorSealResult

    fun open(
        envelope: LicenseServiceDurableStateEnvelope
    ): LicenseServiceDurableProtectorOpenResult
}

sealed interface LicenseServiceDurableStateAcceptanceResult {
    class Advanced internal constructor(
        val snapshot: LicenseServiceDurableStateSnapshot
    ) : LicenseServiceDurableStateAcceptanceResult {
        override fun toString(): String =
            "LicenseServiceDurableStateAcceptanceResult.Advanced(snapshot=$snapshot)"
    }

    class Unchanged internal constructor(
        val snapshot: LicenseServiceDurableStateSnapshot
    ) : LicenseServiceDurableStateAcceptanceResult {
        override fun toString(): String =
            "LicenseServiceDurableStateAcceptanceResult.Unchanged(snapshot=$snapshot)"
    }

    data class VerificationRejected(
        val reason: LicenseServiceStateVerificationRejection
    ) : LicenseServiceDurableStateAcceptanceResult

    data class StateRejected(
        val reason: LicenseServiceStateAcceptanceRejection
    ) : LicenseServiceDurableStateAcceptanceResult

    data class DurableRejected(
        val reason: LicenseServiceDurableStateFailure
    ) : LicenseServiceDurableStateAcceptanceResult
}

sealed interface LicenseServiceDurablePolicyContextResult {
    class Available internal constructor(
        val context: LicensePolicyContext,
        val durableGeneration: LicenseServiceDurableStateGeneration,
        val backendRevision: LicenseServiceDurableBackendRevision,
        val latestServerTime: Instant?
    ) : LicenseServiceDurablePolicyContextResult {
        override fun toString(): String =
            "LicenseServiceDurablePolicyContextResult.Available(durableGeneration=$durableGeneration, " +
                "backendRevision=$backendRevision, latestServerTimePresent=${latestServerTime != null})"
    }

    data object Missing : LicenseServiceDurablePolicyContextResult
}

/**
 * Android-free durable acceptance transaction for authenticated Licensing Service security state.
 *
 * The coordinator verifies service evidence first, derives one monotonic candidate, seals and CAS
 * commits that exact candidate, and only then publishes it process-locally. It never retries a
 * conflict or uncertain commit and never treats durable state as License, Authority or Execution
 * permission.
 */
class LicenseServiceDurableStateCoordinator(
    private val foundation: FoundationComposition,
    private val storeId: LicenseServiceDurableStoreId,
    supportedProtocolVersion: LicenseServiceProtocolVersion,
    supportedPurposes: Set<LicenseServiceEvidencePurpose>,
    supportedProfiles: Set<LicenseServiceEvidenceProfile>,
    trustedKeys: LicenseServiceTrustedKeyResolver,
    proofVerifier: LicenseServiceProofVerifier,
    private val backend: LicenseServiceDurableBackend,
    private val protector: LicenseServiceDurableStateProtector
) {
    private val verifier = LicenseServiceStateVerifier(
        supportedProtocolVersion = supportedProtocolVersion,
        supportedPurposes = supportedPurposes,
        supportedProfiles = supportedProfiles,
        trustedKeys = trustedKeys,
        proofVerifier = proofVerifier
    )
    private val lock = Any()
    private var published: LicenseServiceDurableStateSnapshot? = null
    private var initializationUncertain = false

    fun verifyAndAccept(
        envelope: LicenseServiceStateEnvelope
    ): LicenseServiceDurableStateAcceptanceResult {
        val verification = verifier.verify(envelope)
        if (verification is LicenseServiceStateVerificationResult.Rejected) {
            observeRejected("verification", verification.reason.name.lowercase())
            return LicenseServiceDurableStateAcceptanceResult.VerificationRejected(verification.reason)
        }
        verification as LicenseServiceStateVerificationResult.Verified

        return synchronized(lock) {
            transact(verification)
        }
    }

    fun inspectPublished(): LicenseServiceDurableStateSnapshot? = synchronized(lock) { published }

    fun policyContext(
        scope: LicenseServiceSecurityScope,
        now: Instant,
        suspiciousTimeOrReplayState: Boolean
    ): LicenseServiceDurablePolicyContextResult = synchronized(lock) {
        val snapshot = published ?: return@synchronized LicenseServiceDurablePolicyContextResult.Missing
        val state = snapshot.states.firstOrNull { it.scope == scope }
            ?: return@synchronized LicenseServiceDurablePolicyContextResult.Missing
        LicenseServiceDurablePolicyContextResult.Available(
            context = LicensePolicyContext(
                now = now,
                minimumRevocationEpoch = state.revocationEpoch ?: LicenseRevocationEpoch(0),
                minimumReplaySequence = state.replaySequence,
                suspiciousTimeOrReplayState = suspiciousTimeOrReplayState
            ),
            durableGeneration = snapshot.generation,
            backendRevision = snapshot.backendRevision,
            latestServerTime = state.serverTime
        )
    }

    private fun transact(
        verification: LicenseServiceStateVerificationResult.Verified
    ): LicenseServiceDurableStateAcceptanceResult {
        if (initializationUncertain) {
            return durableRejected(LicenseServiceDurableStateFailure.INITIALIZATION_UNCERTAIN)
        }

        val current = when (val loaded = loadCurrent()) {
            is CurrentLoadResult.Present -> loaded
            CurrentLoadResult.Missing -> null
            is CurrentLoadResult.Rejected -> return durableRejected(loaded.reason)
        }

        val publishedSnapshot = published
        if (publishedSnapshot != null) {
            if (current == null || current.snapshot != publishedSnapshot) {
                return durableRejected(LicenseServiceDurableStateFailure.RECOVERY_REJECTED)
            }
        }

        val incoming = verification.state
        val currentState = current?.snapshot?.states?.firstOrNull { it.scope == incoming.scope }
        if (currentState != null) {
            staleReason(currentState, incoming)?.let {
                observeRejected("state", it.name.lowercase())
                return LicenseServiceDurableStateAcceptanceResult.StateRejected(it)
            }
        }

        val merged = currentState?.let { merge(it, incoming) } ?: incoming
        if (currentState != null && merged == currentState) {
            val snapshot = current.snapshot
            published = snapshot
            observeUnchanged(snapshot)
            return LicenseServiceDurableStateAcceptanceResult.Unchanged(snapshot)
        }

        val nextGeneration = nextGeneration(current?.snapshot?.generation)
            ?: return durableRejected(LicenseServiceDurableStateFailure.GENERATION_OVERFLOW)
        val nextRevision = nextRevision(current?.snapshot?.backendRevision)
            ?: return durableRejected(LicenseServiceDurableStateFailure.REVISION_OVERFLOW)

        val candidateStates = if (current == null) {
            listOf(merged)
        } else {
            current.snapshot.states.filterNot { it.scope == merged.scope } + merged
        }
        val candidate = try {
            LicenseServiceDurableStateSnapshot(
                states = candidateStates,
                generation = nextGeneration,
                backendRevision = nextRevision,
                schemaVersion = LicenseServiceDurableStateSchemaVersion(1)
            )
        } catch (_: IllegalArgumentException) {
            return durableRejected(LicenseServiceDurableStateFailure.INCOMPATIBLE)
        }
        val plaintext = when (val encoded = LicenseServiceDurableStateCanonicalCodec.encode(candidate)) {
            is LicenseServiceDurableStateEncodeResult.Encoded -> encoded.payload
            is LicenseServiceDurableStateEncodeResult.Rejected -> {
                return durableRejected(LicenseServiceDurableStateFailure.INCOMPATIBLE)
            }
        }

        var freshInitialization = false
        val protectorReference = if (current == null) {
            when (val initialization = protector.prepareInitialization(storeId)) {
                is LicenseServiceDurableProtectorInitializationResult.Fresh -> {
                    initializationUncertain = true
                    freshInitialization = true
                    initialization.reference
                }
                is LicenseServiceDurableProtectorInitializationResult.Existing -> {
                    initializationUncertain = true
                    return durableRejected(LicenseServiceDurableStateFailure.INITIALIZATION_UNCERTAIN)
                }
                is LicenseServiceDurableProtectorInitializationResult.Rejected -> {
                    return durableRejected(mapProtectorFailure(initialization.reason))
                }
            }
        } else {
            current.protector
        }

        val binding = LicenseServiceDurableStateBinding(
            version = LicenseServiceDurableStateEnvelopeVersion(1),
            stateSchemaVersion = candidate.schemaVersion,
            purpose = LicenseServiceDurableStatePurpose.LICENSE_SERVICE_SECURITY_STATE,
            profile = LicenseServiceDurableStateEncryptionProfile.AES_256_GCM,
            storeId = storeId,
            generation = candidate.generation,
            backendRevision = candidate.backendRevision,
            protector = protectorReference
        )
        val sealed = when (val result = protector.seal(binding, plaintext)) {
            is LicenseServiceDurableProtectorSealResult.Sealed -> result.envelope
            is LicenseServiceDurableProtectorSealResult.Rejected -> {
                return durableRejected(mapProtectorFailure(result.reason))
            }
        }
        if (sealed.binding != binding) {
            return durableRejected(LicenseServiceDurableStateFailure.STALE_PROTECTOR_OWNERSHIP)
        }
        val encodedEnvelope = when (
            val result = LicenseServiceDurableStateEnvelopeCanonicalCodec.encode(sealed)
        ) {
            is LicenseServiceDurableStateEnvelopeEncodeResult.Encoded -> result.payload
            is LicenseServiceDurableStateEnvelopeEncodeResult.Rejected -> {
                return durableRejected(LicenseServiceDurableStateFailure.INCOMPATIBLE)
            }
        }

        val expectedRevision = LicenseServiceDurableExpectedRevision(
            current?.snapshot?.backendRevision?.value ?: 0L
        )
        return when (val committed = backend.commit(expectedRevision, encodedEnvelope)) {
            is LicenseServiceDurableBackendCommitResult.Committed -> {
                if (committed.revision != candidate.backendRevision) {
                    durableRejected(LicenseServiceDurableStateFailure.REVISION_MISMATCH)
                } else {
                    if (freshInitialization) {
                        initializationUncertain = false
                    }
                    published = candidate
                    observeAdvanced(candidate)
                    LicenseServiceDurableStateAcceptanceResult.Advanced(candidate)
                }
            }
            LicenseServiceDurableBackendCommitResult.Conflict ->
                durableRejected(LicenseServiceDurableStateFailure.REVISION_CONFLICT)
            LicenseServiceDurableBackendCommitResult.Failed ->
                durableRejected(LicenseServiceDurableStateFailure.PERSISTENCE_FAILED)
            LicenseServiceDurableBackendCommitResult.Uncertain ->
                durableRejected(LicenseServiceDurableStateFailure.PERSISTENCE_UNCERTAIN)
        }
    }

    private fun loadCurrent(): CurrentLoadResult = when (val loaded = backend.load()) {
        LicenseServiceDurableBackendLoadResult.Missing -> CurrentLoadResult.Missing
        LicenseServiceDurableBackendLoadResult.Corrupt ->
            CurrentLoadResult.Rejected(LicenseServiceDurableStateFailure.CORRUPT)
        LicenseServiceDurableBackendLoadResult.Incompatible ->
            CurrentLoadResult.Rejected(LicenseServiceDurableStateFailure.INCOMPATIBLE)
        LicenseServiceDurableBackendLoadResult.Failed ->
            CurrentLoadResult.Rejected(LicenseServiceDurableStateFailure.PERSISTENCE_FAILED)
        is LicenseServiceDurableBackendLoadResult.Loaded -> loadPresent(loaded)
    }

    private fun loadPresent(
        loaded: LicenseServiceDurableBackendLoadResult.Loaded
    ): CurrentLoadResult {
        val envelope = when (
            val decoded = LicenseServiceDurableStateEnvelopeCanonicalCodec.decode(loaded.envelope)
        ) {
            is LicenseServiceDurableStateEnvelopeDecodeResult.Decoded -> decoded.envelope
            is LicenseServiceDurableStateEnvelopeDecodeResult.Rejected -> {
                val reason = if (
                    decoded.reason == LicenseServiceDurableStateCodecRejection.UNSUPPORTED_VERSION
                ) {
                    LicenseServiceDurableStateFailure.INCOMPATIBLE
                } else {
                    LicenseServiceDurableStateFailure.CORRUPT
                }
                return CurrentLoadResult.Rejected(reason)
            }
        }
        if (envelope.binding.storeId != storeId) {
            return CurrentLoadResult.Rejected(LicenseServiceDurableStateFailure.INCOMPATIBLE)
        }
        if (envelope.binding.backendRevision != loaded.revision) {
            return CurrentLoadResult.Rejected(LicenseServiceDurableStateFailure.REVISION_MISMATCH)
        }

        val plaintext = when (val opened = protector.open(envelope)) {
            is LicenseServiceDurableProtectorOpenResult.Opened -> opened.payload
            is LicenseServiceDurableProtectorOpenResult.Rejected -> {
                return CurrentLoadResult.Rejected(mapProtectorFailure(opened.reason))
            }
        }
        val snapshot = when (val decoded = LicenseServiceDurableStateCanonicalCodec.decode(plaintext)) {
            is LicenseServiceDurableStateDecodeResult.Decoded -> decoded.snapshot
            is LicenseServiceDurableStateDecodeResult.Rejected -> {
                val reason = if (
                    decoded.reason == LicenseServiceDurableStateCodecRejection.UNSUPPORTED_VERSION
                ) {
                    LicenseServiceDurableStateFailure.INCOMPATIBLE
                } else {
                    LicenseServiceDurableStateFailure.CORRUPT
                }
                return CurrentLoadResult.Rejected(reason)
            }
        }
        if (
            snapshot.backendRevision != loaded.revision ||
            snapshot.backendRevision != envelope.binding.backendRevision
        ) {
            return CurrentLoadResult.Rejected(LicenseServiceDurableStateFailure.REVISION_MISMATCH)
        }
        if (
            snapshot.generation != envelope.binding.generation ||
            snapshot.schemaVersion != envelope.binding.stateSchemaVersion
        ) {
            return CurrentLoadResult.Rejected(LicenseServiceDurableStateFailure.RECOVERY_REJECTED)
        }
        return CurrentLoadResult.Present(snapshot, envelope.binding.protector)
    }

    private fun nextGeneration(
        current: LicenseServiceDurableStateGeneration?
    ): LicenseServiceDurableStateGeneration? {
        val value = current?.value ?: 0L
        if (value == Long.MAX_VALUE) return null
        return LicenseServiceDurableStateGeneration(value + 1L)
    }

    private fun nextRevision(
        current: LicenseServiceDurableBackendRevision?
    ): LicenseServiceDurableBackendRevision? {
        val value = current?.value ?: 0L
        if (value == Long.MAX_VALUE) return null
        return LicenseServiceDurableBackendRevision(value + 1L)
    }

    private fun staleReason(
        current: LicenseServiceSecurityState,
        incoming: LicenseServiceSecurityState
    ): LicenseServiceStateAcceptanceRejection? {
        if (
            current.revocationEpoch != null &&
            incoming.revocationEpoch != null &&
            incoming.revocationEpoch.value < current.revocationEpoch.value
        ) {
            return LicenseServiceStateAcceptanceRejection.STALE_REVOCATION_EPOCH
        }
        if (
            current.replaySequence != null &&
            incoming.replaySequence != null &&
            incoming.replaySequence.value < current.replaySequence.value
        ) {
            return LicenseServiceStateAcceptanceRejection.STALE_REPLAY_SEQUENCE
        }
        if (
            current.serverTime != null &&
            incoming.serverTime != null &&
            incoming.serverTime.isBefore(current.serverTime)
        ) {
            return LicenseServiceStateAcceptanceRejection.STALE_SERVER_TIME
        }
        return null
    }

    private fun merge(
        current: LicenseServiceSecurityState,
        incoming: LicenseServiceSecurityState
    ): LicenseServiceSecurityState = LicenseServiceSecurityState(
        scope = current.scope,
        revocationEpoch = maxRevocation(current.revocationEpoch, incoming.revocationEpoch),
        replaySequence = maxReplay(current.replaySequence, incoming.replaySequence),
        serverTime = maxTime(current.serverTime, incoming.serverTime)
    )

    private fun maxRevocation(
        current: LicenseRevocationEpoch?,
        incoming: LicenseRevocationEpoch?
    ): LicenseRevocationEpoch? = when {
        current == null -> incoming
        incoming == null -> current
        incoming.value > current.value -> incoming
        else -> current
    }

    private fun maxReplay(
        current: LicenseReplaySequence?,
        incoming: LicenseReplaySequence?
    ): LicenseReplaySequence? = when {
        current == null -> incoming
        incoming == null -> current
        incoming.value > current.value -> incoming
        else -> current
    }

    private fun maxTime(current: Instant?, incoming: Instant?): Instant? = when {
        current == null -> incoming
        incoming == null -> current
        incoming.isAfter(current) -> incoming
        else -> current
    }

    private fun mapProtectorFailure(
        reason: LicenseServiceDurableProtectorFailure
    ): LicenseServiceDurableStateFailure = when (reason) {
        LicenseServiceDurableProtectorFailure.AUTHENTICATION_FAILED ->
            LicenseServiceDurableStateFailure.AUTHENTICATION_FAILED
        LicenseServiceDurableProtectorFailure.PROTECTOR_MISSING ->
            LicenseServiceDurableStateFailure.PROTECTOR_MISSING
        LicenseServiceDurableProtectorFailure.PROTECTOR_INVALIDATED ->
            LicenseServiceDurableStateFailure.PROTECTOR_INVALIDATED
        LicenseServiceDurableProtectorFailure.STALE_PROTECTOR_OWNERSHIP ->
            LicenseServiceDurableStateFailure.STALE_PROTECTOR_OWNERSHIP
        LicenseServiceDurableProtectorFailure.REQUIRED_SECURITY_LEVEL_UNAVAILABLE ->
            LicenseServiceDurableStateFailure.REQUIRED_SECURITY_LEVEL_UNAVAILABLE
        LicenseServiceDurableProtectorFailure.FAILED ->
            LicenseServiceDurableStateFailure.PERSISTENCE_FAILED
    }

    private fun durableRejected(
        reason: LicenseServiceDurableStateFailure
    ): LicenseServiceDurableStateAcceptanceResult.DurableRejected {
        observeRejected("durable", reason.name.lowercase())
        return LicenseServiceDurableStateAcceptanceResult.DurableRejected(reason)
    }

    private fun observeAdvanced(snapshot: LicenseServiceDurableStateSnapshot) {
        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "LICENSE_SERVICE_DURABLE_STATE_ADVANCED",
            message = "license service durable security state advanced",
            context = foundation.rootContext(
                operation = "verifyAndAcceptDurableLicenseServiceState",
                component = "LicenseService",
                metadata = durableMetadata(snapshot)
            ),
            metadata = durableMetadata(snapshot)
        )
    }

    private fun observeUnchanged(snapshot: LicenseServiceDurableStateSnapshot) {
        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "LICENSE_SERVICE_DURABLE_STATE_UNCHANGED",
            message = "license service durable security state unchanged",
            context = foundation.rootContext(
                operation = "verifyAndAcceptDurableLicenseServiceState",
                component = "LicenseService",
                metadata = durableMetadata(snapshot)
            ),
            metadata = durableMetadata(snapshot)
        )
    }

    private fun observeRejected(category: String, reason: String) {
        val metadata = mapOf(
            "licenseServiceDurableResultCategory" to category,
            "licenseServiceDurableResultReason" to reason
        )
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "LICENSE_SERVICE_DURABLE_STATE_REJECTED",
            message = "license service durable security state rejected",
            context = foundation.rootContext(
                operation = "verifyAndAcceptDurableLicenseServiceState",
                component = "LicenseService",
                metadata = metadata
            ),
            metadata = metadata
        )
    }

    private fun durableMetadata(snapshot: LicenseServiceDurableStateSnapshot): Map<String, String> =
        mapOf(
            "licenseServiceDurableSchemaVersion" to snapshot.schemaVersion.value.toString(),
            "licenseServiceDurableGeneration" to snapshot.generation.value.toString(),
            "licenseServiceDurableBackendRevision" to snapshot.backendRevision.value.toString(),
            "licenseServiceDurableScopeCount" to snapshot.states.size.toString()
        )

    private sealed interface CurrentLoadResult {
        data object Missing : CurrentLoadResult

        data class Present(
            val snapshot: LicenseServiceDurableStateSnapshot,
            val protector: LicenseServiceDurableStateProtectorReference
        ) : CurrentLoadResult

        data class Rejected(val reason: LicenseServiceDurableStateFailure) : CurrentLoadResult
    }
}
