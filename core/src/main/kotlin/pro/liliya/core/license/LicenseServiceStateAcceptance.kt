package pro.liliya.core.license

import java.time.Instant
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

@JvmInline
value class LicenseServiceStateGeneration(val value: Long) {
    init {
        require(value > 0L) { "license service state generation must be positive" }
    }

    override fun toString(): String = value.toString()
}

class LicenseServiceAcceptedStateSnapshot(
    val state: LicenseServiceSecurityState,
    val generation: LicenseServiceStateGeneration
) {
    override fun toString(): String =
        "LicenseServiceAcceptedStateSnapshot(scope=${state.scope}, generation=$generation, " +
            "revocationEpoch=${state.revocationEpoch}, replaySequence=${state.replaySequence}, " +
            "serverTimePresent=${state.serverTime != null})"
}

enum class LicenseServiceStateAcceptanceRejection {
    STALE_REVOCATION_EPOCH,
    STALE_REPLAY_SEQUENCE,
    STALE_SERVER_TIME,
    GENERATION_OVERFLOW
}

sealed interface LicenseServiceStateAcceptanceResult {
    class Advanced internal constructor(
        val snapshot: LicenseServiceAcceptedStateSnapshot
    ) : LicenseServiceStateAcceptanceResult {
        override fun toString(): String =
            "LicenseServiceStateAcceptanceResult.Advanced(snapshot=$snapshot)"
    }

    class Unchanged internal constructor(
        val snapshot: LicenseServiceAcceptedStateSnapshot
    ) : LicenseServiceStateAcceptanceResult {
        override fun toString(): String =
            "LicenseServiceStateAcceptanceResult.Unchanged(snapshot=$snapshot)"
    }

    data class VerificationRejected(
        val reason: LicenseServiceStateVerificationRejection
    ) : LicenseServiceStateAcceptanceResult

    data class StateRejected(
        val reason: LicenseServiceStateAcceptanceRejection
    ) : LicenseServiceStateAcceptanceResult
}

sealed interface LicenseServicePolicyContextResult {
    class Available internal constructor(
        val context: LicensePolicyContext,
        val generation: LicenseServiceStateGeneration,
        val latestServerTime: Instant?
    ) : LicenseServicePolicyContextResult {
        override fun toString(): String =
            "LicenseServicePolicyContextResult.Available(generation=$generation, " +
                "latestServerTimePresent=${latestServerTime != null})"
    }

    data object Missing : LicenseServicePolicyContextResult
}

private sealed interface LicenseServiceStateStoreResult {
    data class Advanced(val snapshot: LicenseServiceAcceptedStateSnapshot) :
        LicenseServiceStateStoreResult

    data class Unchanged(val snapshot: LicenseServiceAcceptedStateSnapshot) :
        LicenseServiceStateStoreResult

    data class Rejected(val reason: LicenseServiceStateAcceptanceRejection) :
        LicenseServiceStateStoreResult
}

private class LicenseServiceAcceptedStateStore {
    private data class Entry(
        val state: LicenseServiceSecurityState,
        val generation: LicenseServiceStateGeneration
    )

    private val lock = Any()
    private var nextGeneration = 0L
    private val entries = mutableMapOf<LicenseServiceSecurityScope, Entry>()

    fun accept(
        verified: LicenseServiceStateVerificationResult.Verified
    ): LicenseServiceStateStoreResult = synchronized(lock) {
        val incoming = verified.state
        val current = entries[incoming.scope]

        if (current != null) {
            staleReason(current.state, incoming)?.let {
                return@synchronized LicenseServiceStateStoreResult.Rejected(it)
            }
        }

        val merged = if (current == null) {
            incoming
        } else {
            merge(current.state, incoming)
        }

        if (current != null && merged == current.state) {
            return@synchronized LicenseServiceStateStoreResult.Unchanged(snapshot(current))
        }

        if (nextGeneration == Long.MAX_VALUE) {
            return@synchronized LicenseServiceStateStoreResult.Rejected(
                LicenseServiceStateAcceptanceRejection.GENERATION_OVERFLOW
            )
        }
        nextGeneration += 1L
        val entry = Entry(
            state = merged,
            generation = LicenseServiceStateGeneration(nextGeneration)
        )
        entries[merged.scope] = entry
        LicenseServiceStateStoreResult.Advanced(snapshot(entry))
    }

    fun inspect(scope: LicenseServiceSecurityScope): LicenseServiceAcceptedStateSnapshot? =
        synchronized(lock) {
            entries[scope]?.let(::snapshot)
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
    ): LicenseServiceSecurityState =
        LicenseServiceSecurityState(
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

    private fun snapshot(entry: Entry): LicenseServiceAcceptedStateSnapshot =
        LicenseServiceAcceptedStateSnapshot(
            state = entry.state,
            generation = entry.generation
        )
}

/**
 * Process-local authenticated service security-state boundary.
 *
 * This composition verifies the exact service-state envelope before any retained state can advance.
 * Accepted state is security evidence only: it is not License entitlement, Authority or Execution
 * permission, and this v0.1 slice makes no crash/restart durability claim.
 */
class LicenseServiceStateAcceptanceComposition(
    private val foundation: FoundationComposition,
    supportedProtocolVersion: LicenseServiceProtocolVersion,
    supportedPurposes: Set<LicenseServiceEvidencePurpose>,
    supportedProfiles: Set<LicenseServiceEvidenceProfile>,
    trustedKeys: LicenseServiceTrustedKeyResolver,
    proofVerifier: LicenseServiceProofVerifier
) {
    private val verifier = LicenseServiceStateVerifier(
        supportedProtocolVersion = supportedProtocolVersion,
        supportedPurposes = supportedPurposes,
        supportedProfiles = supportedProfiles,
        trustedKeys = trustedKeys,
        proofVerifier = proofVerifier
    )
    private val store = LicenseServiceAcceptedStateStore()

    fun verifyAndAccept(
        envelope: LicenseServiceStateEnvelope
    ): LicenseServiceStateAcceptanceResult {
        val verification = verifier.verify(envelope)
        if (verification is LicenseServiceStateVerificationResult.Rejected) {
            observeVerificationRejected(envelope, verification.reason)
            return LicenseServiceStateAcceptanceResult.VerificationRejected(verification.reason)
        }
        verification as LicenseServiceStateVerificationResult.Verified

        return when (val result = store.accept(verification)) {
            is LicenseServiceStateStoreResult.Advanced -> {
                observeAccepted(envelope, result.snapshot, advanced = true)
                LicenseServiceStateAcceptanceResult.Advanced(result.snapshot)
            }

            is LicenseServiceStateStoreResult.Unchanged -> {
                observeAccepted(envelope, result.snapshot, advanced = false)
                LicenseServiceStateAcceptanceResult.Unchanged(result.snapshot)
            }

            is LicenseServiceStateStoreResult.Rejected -> {
                observeStateRejected(envelope, verification.state, result.reason)
                LicenseServiceStateAcceptanceResult.StateRejected(result.reason)
            }
        }
    }

    fun inspect(scope: LicenseServiceSecurityScope): LicenseServiceAcceptedStateSnapshot? =
        store.inspect(scope)

    fun policyContext(
        scope: LicenseServiceSecurityScope,
        now: Instant,
        suspiciousTimeOrReplayState: Boolean
    ): LicenseServicePolicyContextResult {
        val snapshot = store.inspect(scope) ?: return LicenseServicePolicyContextResult.Missing
        return LicenseServicePolicyContextResult.Available(
            context = LicensePolicyContext(
                now = now,
                minimumRevocationEpoch = snapshot.state.revocationEpoch
                    ?: LicenseRevocationEpoch(0),
                minimumReplaySequence = snapshot.state.replaySequence,
                suspiciousTimeOrReplayState = suspiciousTimeOrReplayState
            ),
            generation = snapshot.generation,
            latestServerTime = snapshot.state.serverTime
        )
    }

    private fun observeVerificationRejected(
        envelope: LicenseServiceStateEnvelope,
        reason: LicenseServiceStateVerificationRejection
    ) {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "LICENSE_SERVICE_STATE_VERIFICATION_REJECTED",
            message = "license service state verification rejected",
            context = foundation.rootContext(
                operation = "verifyAndAcceptLicenseServiceState",
                component = "LicenseService",
                metadata = envelopeMetadata(envelope)
            ),
            metadata = envelopeMetadata(envelope) +
                ("licenseServiceStateVerificationRejection" to reason.name.lowercase())
        )
    }

    private fun observeAccepted(
        envelope: LicenseServiceStateEnvelope,
        snapshot: LicenseServiceAcceptedStateSnapshot,
        advanced: Boolean
    ) {
        val metadata = envelopeMetadata(envelope) + stateMetadata(snapshot)
        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = if (advanced) {
                "LICENSE_SERVICE_STATE_ADVANCED"
            } else {
                "LICENSE_SERVICE_STATE_UNCHANGED"
            },
            message = if (advanced) {
                "license service security state advanced"
            } else {
                "license service security state unchanged"
            },
            context = foundation.rootContext(
                operation = "verifyAndAcceptLicenseServiceState",
                component = "LicenseService",
                metadata = metadata
            ),
            metadata = metadata
        )
    }

    private fun observeStateRejected(
        envelope: LicenseServiceStateEnvelope,
        state: LicenseServiceSecurityState,
        reason: LicenseServiceStateAcceptanceRejection
    ) {
        val metadata = envelopeMetadata(envelope) + stateMetadata(state) +
            ("licenseServiceStateAcceptanceRejection" to reason.name.lowercase())
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "LICENSE_SERVICE_STATE_ACCEPTANCE_REJECTED",
            message = "license service security state acceptance rejected",
            context = foundation.rootContext(
                operation = "verifyAndAcceptLicenseServiceState",
                component = "LicenseService",
                metadata = metadata
            ),
            metadata = metadata
        )
    }

    private fun envelopeMetadata(envelope: LicenseServiceStateEnvelope): Map<String, String> = mapOf(
        "licenseServiceProtocolVersion" to envelope.protocolVersion.value.toString(),
        "licenseServiceEvidencePurpose" to envelope.purpose.name.lowercase(),
        "licenseServiceEvidenceProfile" to envelope.profile.value,
        "licenseServiceSigningKeyId" to envelope.signingKeyId.value
    )

    private fun stateMetadata(
        snapshot: LicenseServiceAcceptedStateSnapshot
    ): Map<String, String> = stateMetadata(snapshot.state) +
        ("licenseServiceStateGeneration" to snapshot.generation.value.toString())

    private fun stateMetadata(state: LicenseServiceSecurityState): Map<String, String> = buildMap {
        put("licenseProductId", state.scope.productId.value)
        state.revocationEpoch?.let {
            put("licenseServiceRevocationEpoch", it.value.toString())
        }
        state.replaySequence?.let {
            put("licenseServiceReplaySequence", it.value.toString())
        }
        put("licenseServiceServerTimePresent", (state.serverTime != null).toString())
    }
}
