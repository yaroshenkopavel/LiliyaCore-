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

    fun replaceTarget(reference: ProtectedModelReference): ProtectedModelOpenTicket = synchronized(lock) {
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
        val current = target
        current != null &&
            current.reference == ticket.reference &&
            current.epoch == ticket.attemptId.value
    }

    fun retire(expected: ProtectedModelReference): Boolean = synchronized(lock) {
        if (target?.reference != expected) return@synchronized false
        target = null
        true
    }
}

/**
 * Higher-layer protected-model access composition.
 *
 * Order: exact active target -> fresh policy decision -> authenticated loader open -> exact stale check
 * -> bounded publication callback. There is no hidden retry, replay, reconciliation or rollback.
 */
class ProtectedModelAccessCoordinator(
    private val policy: ProtectedModelAccessPolicy,
    private val ownership: ProtectedModelRuntimeOwnership,
    private val loader: ProtectedModelPayloadLoader
) {
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

        if (!ownership.isCurrent(ticket)) {
            return ProtectedModelAccessResult.Rejected(ProtectedModelAccessFailure.STALE_OWNERSHIP)
        }

        return try {
            publish(reference, value)
            ProtectedModelAccessResult.Opened(reference, value)
        } catch (throwable: Throwable) {
            ProtectedModelAccessResult.Failed(
                ProtectedModelAccessFailure.PUBLISH_FAILED,
                throwable
            )
        }
    }
}
