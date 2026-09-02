package pro.liliya.core.protectedmodel

import java.util.concurrent.atomic.AtomicLong

/**
 * Fresh higher-layer policy decision for one exact protected-model generation.
 *
 * Implementations may adapt License Core/offline-lease policy, but this boundary intentionally does not
 * embed License, Authority, key possession or execution semantics into the crypto primitives.
 */
fun interface ProtectedModelAccessPolicy {
    fun decide(reference: ProtectedModelReference): ProtectedModelPolicyDecision
}

sealed interface ProtectedModelPolicyDecision {
    data object Allowed : ProtectedModelPolicyDecision
    data class Rejected(val reason: ProtectedModelPolicyFailure) : ProtectedModelPolicyDecision
    data class Failed(
        val reason: ProtectedModelPolicyFailure,
        val throwable: Throwable? = null
    ) : ProtectedModelPolicyDecision {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

enum class ProtectedModelPolicyFailure {
    ENTITLEMENT_REJECTED,
    EVIDENCE_STALE,
    EVIDENCE_UNAVAILABLE,
    PROVIDER_FAILED
}

@JvmInline
value class ProtectedModelOpenAttemptId(val value: Long) {
    init { require(value > 0L) { "protected model open attempt id must be positive" } }
}

data class ProtectedModelOpenTicket internal constructor(
    val attemptId: ProtectedModelOpenAttemptId,
    val reference: ProtectedModelReference
)

enum class ProtectedModelAccessFailure {
    NO_ACTIVE_TARGET,
    TARGET_MISMATCH,
    POLICY_REJECTED,
    POLICY_FAILED,
    OPEN_REJECTED,
    OPEN_FAILED,
    STALE_OWNERSHIP,
    PUBLISH_FAILED,
    PROVIDER_FAILED
}

sealed interface ProtectedModelAccessResult<out T> {
    data class Opened<T>(
        val reference: ProtectedModelReference,
        val value: T
    ) : ProtectedModelAccessResult<T>

    data class Rejected(val reason: ProtectedModelAccessFailure) : ProtectedModelAccessResult<Nothing>

    data class Failed(
        val reason: ProtectedModelAccessFailure,
        val throwable: Throwable? = null
    ) : ProtectedModelAccessResult<Nothing> {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

/**
 * Exact process-local authorization for final protected-model runtime publication.
 *
 * Construction is restricted to the access coordinator. The ticket binds this capability to the exact
 * ownership epoch that was current when a fresh policy decision was accepted. Structural reference
 * equality alone is therefore insufficient to reuse authorization after replacement/retirement.
 */
internal class ProtectedModelPublicationAuthorization internal constructor(
    internal val ticket: ProtectedModelOpenTicket
) {
    val reference: ProtectedModelReference = ticket.reference

    override fun toString(): String =
        "ProtectedModelPublicationAuthorization(reference=$reference, epoch=<redacted>)"
}

internal sealed interface ProtectedModelAuthorizationResult {
    data class Authorized(
        val authorization: ProtectedModelPublicationAuthorization
    ) : ProtectedModelAuthorizationResult

    data class Rejected(
        val reason: ProtectedModelAccessFailure
    ) : ProtectedModelAuthorizationResult

    data class Failed(
        val reason: ProtectedModelAccessFailure,
        val throwable: Throwable? = null
    ) : ProtectedModelAuthorizationResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

internal sealed interface ProtectedModelAuthorizedPublicationResult {
    data object Published : ProtectedModelAuthorizedPublicationResult
    data object Stale : ProtectedModelAuthorizedPublicationResult

    data class Failed(
        val reason: ProtectedModelAccessFailure,
        val throwable: Throwable? = null
    ) : ProtectedModelAuthorizedPublicationResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

/**
 * Exact process-local publication ownership for protected-model generations.
 *
 * A newer target immediately invalidates all older tickets. Successful verification, policy approval,
 * key release or decryption never by themselves publish a runtime model handle.
 */
class ProtectedModelRuntimeOwnership {
    private data class Target(
        val reference: ProtectedModelReference,
        val epoch: Long
    )

    private val lock = Any()
    private val nextEpoch = AtomicLong(0L)
    private var target: Target? = null
    private var publicationInProgress = false

    fun replaceTarget(reference: ProtectedModelReference): ProtectedModelOpenTicket = synchronized(lock) {
        check(!publicationInProgress) {
            "protected model target replacement is not allowed from inside publication"
        }
        val epoch = nextEpoch.incrementAndGet()
        check(epoch > 0L) { "protected model ownership epoch overflow" }
        target = Target(reference, epoch)
        ProtectedModelOpenTicket(ProtectedModelOpenAttemptId(epoch), reference)
    }

    fun currentReference(): ProtectedModelReference? = synchronized(lock) { target?.reference }

    fun ticketFor(reference: ProtectedModelReference): ProtectedModelOpenTicket? = synchronized(lock) {
        val current = target ?: return@synchronized null
        if (current.reference != reference) return@synchronized null
        ProtectedModelOpenTicket(ProtectedModelOpenAttemptId(current.epoch), current.reference)
    }

    fun isCurrent(ticket: ProtectedModelOpenTicket): Boolean = synchronized(lock) {
        matchesCurrent(ticket)
    }

    /**
     * Runs final runtime publication behind the same ownership barrier used by replacement and retirement.
     * Cross-thread mutations wait for publication to finish; same-thread reentrant mutations are rejected,
     * so monitor reentrancy cannot turn a stale publication into a successful one.
     */
    fun publishIfCurrent(ticket: ProtectedModelOpenTicket, publish: () -> Unit): Boolean = synchronized(lock) {
        if (!matchesCurrent(ticket)) return@synchronized false
        check(!publicationInProgress) { "nested protected model publication is not allowed" }
        publicationInProgress = true
        try {
            publish()
            true
        } finally {
            publicationInProgress = false
        }
    }

    fun retire(expected: ProtectedModelReference): Boolean = synchronized(lock) {
        check(!publicationInProgress) {
            "protected model target retirement is not allowed from inside publication"
        }
        if (target?.reference != expected) return@synchronized false
        target = null
        true
    }

    private fun matchesCurrent(ticket: ProtectedModelOpenTicket): Boolean {
        val current = target
        return current != null &&
            current.reference == ticket.reference &&
            current.epoch == ticket.attemptId.value
    }
}

/**
 * Higher-layer protected-model access composition.
 *
 * Order: exact active target -> fresh policy decision -> authenticated loader open -> exact atomic
 * ownership/publication barrier. There is no hidden retry, replay, reconciliation or rollback.
 */
class ProtectedModelAccessCoordinator(
    private val policy: ProtectedModelAccessPolicy,
    private val ownership: ProtectedModelRuntimeOwnership,
    private val loader: ProtectedModelPayloadLoader
) {
    internal fun authorizeExistingReference(
        reference: ProtectedModelReference
    ): ProtectedModelAuthorizationResult {
        val ticket = ownership.ticketFor(reference)
            ?: return if (ownership.currentReference() == null) {
                ProtectedModelAuthorizationResult.Rejected(
                    ProtectedModelAccessFailure.NO_ACTIVE_TARGET
                )
            } else {
                ProtectedModelAuthorizationResult.Rejected(
                    ProtectedModelAccessFailure.TARGET_MISMATCH
                )
            }

        val decision = try {
            policy.decide(reference)
        } catch (throwable: Throwable) {
            return ProtectedModelAuthorizationResult.Failed(
                ProtectedModelAccessFailure.PROVIDER_FAILED,
                throwable
            )
        }
        when (decision) {
            ProtectedModelPolicyDecision.Allowed -> Unit
            is ProtectedModelPolicyDecision.Rejected ->
                return ProtectedModelAuthorizationResult.Rejected(
                    ProtectedModelAccessFailure.POLICY_REJECTED
                )
            is ProtectedModelPolicyDecision.Failed ->
                return ProtectedModelAuthorizationResult.Failed(
                    ProtectedModelAccessFailure.POLICY_FAILED,
                    decision.throwable
                )
        }

        if (!ownership.isCurrent(ticket)) {
            return ProtectedModelAuthorizationResult.Rejected(
                ProtectedModelAccessFailure.STALE_OWNERSHIP
            )
        }

        return ProtectedModelAuthorizationResult.Authorized(
            ProtectedModelPublicationAuthorization(ticket)
        )
    }

    internal fun publishAuthorized(
        authorization: ProtectedModelPublicationAuthorization,
        publish: (ProtectedModelReference) -> Unit
    ): ProtectedModelAuthorizedPublicationResult = try {
        if (!ownership.publishIfCurrent(authorization.ticket) {
                publish(authorization.reference)
            }
        ) {
            ProtectedModelAuthorizedPublicationResult.Stale
        } else {
            ProtectedModelAuthorizedPublicationResult.Published
        }
    } catch (throwable: Throwable) {
        ProtectedModelAuthorizedPublicationResult.Failed(
            ProtectedModelAccessFailure.PUBLISH_FAILED,
            throwable
        )
    }

    fun <T> openAndPublish(
        envelope: ProtectedModelPackageEnvelope,
        ciphertext: ByteArray,
        consumer: ProtectedModelPlaintextConsumer<T>,
        publish: (ProtectedModelReference, T) -> Unit
    ): ProtectedModelAccessResult<T> {
        val reference = envelope.manifest.model
        val ticket = ownership.ticketFor(reference)
            ?: return if (ownership.currentReference() == null) {
                ProtectedModelAccessResult.Rejected(ProtectedModelAccessFailure.NO_ACTIVE_TARGET)
            } else {
                ProtectedModelAccessResult.Rejected(ProtectedModelAccessFailure.TARGET_MISMATCH)
            }

        val decision = try {
            policy.decide(reference)
        } catch (throwable: Throwable) {
            return ProtectedModelAccessResult.Failed(
                ProtectedModelAccessFailure.PROVIDER_FAILED,
                throwable
            )
        }
        when (decision) {
            ProtectedModelPolicyDecision.Allowed -> Unit
            is ProtectedModelPolicyDecision.Rejected ->
                return ProtectedModelAccessResult.Rejected(ProtectedModelAccessFailure.POLICY_REJECTED)
            is ProtectedModelPolicyDecision.Failed ->
                return ProtectedModelAccessResult.Failed(
                    ProtectedModelAccessFailure.POLICY_FAILED,
                    decision.throwable
                )
        }

        if (!ownership.isCurrent(ticket)) {
            return ProtectedModelAccessResult.Rejected(ProtectedModelAccessFailure.STALE_OWNERSHIP)
        }

        val opened = loader.open(envelope, ciphertext, consumer)
        val value = when (opened) {
            is ProtectedModelOpenResult.Opened -> opened.value
            is ProtectedModelOpenResult.Rejected ->
                return ProtectedModelAccessResult.Rejected(ProtectedModelAccessFailure.OPEN_REJECTED)
            is ProtectedModelOpenResult.Failed ->
                return ProtectedModelAccessResult.Failed(
                    ProtectedModelAccessFailure.OPEN_FAILED,
                    opened.throwable
                )
        }

        return try {
            if (!ownership.publishIfCurrent(ticket) { publish(reference, value) }) {
                ProtectedModelAccessResult.Rejected(ProtectedModelAccessFailure.STALE_OWNERSHIP)
            } else {
                ProtectedModelAccessResult.Opened(reference, value)
            }
        } catch (throwable: Throwable) {
            ProtectedModelAccessResult.Failed(
                ProtectedModelAccessFailure.PUBLISH_FAILED,
                throwable
            )
        }
    }
}
