package pro.liliya.core.runtime.hardening

import java.util.concurrent.atomic.AtomicLong

@JvmInline
value class RuntimeModelOperationSequence(val value: Long) {
    init { require(value > 0L) { "runtime model operation sequence must be positive" } }
    override fun toString(): String = value.toString()
}

data class RuntimeModelOperationTicket(
    val sequence: RuntimeModelOperationSequence,
    val session: RuntimeModelSessionReference
)

sealed interface RuntimeOperationAdmissionResult {
    data class Admitted(val ticket: RuntimeModelOperationTicket) : RuntimeOperationAdmissionResult
    data class Rejected(val reason: RuntimeHardeningFailure) : RuntimeOperationAdmissionResult
}

enum class RuntimeOperationTerminal {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}

sealed interface RuntimeOperationReleaseResult {
    data object Published : RuntimeOperationReleaseResult
    data object Stale : RuntimeOperationReleaseResult
    data object AlreadyReleased : RuntimeOperationReleaseResult
    data class Terminated(val reason: RuntimeHardeningFailure) : RuntimeOperationReleaseResult
    data class Failed(
        val reason: RuntimeHardeningFailure,
        val throwable: Throwable
    ) : RuntimeOperationReleaseResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable.javaClass.name})"
    }
}

/**
 * Process-local operation supervision for one Runtime Hardening v0.1 composition.
 *
 * Admission is serialized with runtime-session ownership so an operation ticket is issued only for
 * the exact current ACTIVE session. A ticket is structural runtime ownership only: it is not License,
 * Capability, Authority or permission to perform an external side effect.
 *
 * Terminal release is local and exactly-once. A successful stale operation may finish its local
 * cleanup but cannot publish state into a replacement session. Cancellation and timeout are explicit
 * caller-selected terminal outcomes; this supervisor has no hidden clock, retry or replay policy.
 */
class RuntimeModelOperationSupervisor internal constructor(
    private val registry: RuntimeModelSessionRegistry,
    private val limits: RuntimeHardeningLimits,
    initialSequence: Long = 0L
) {
    private data class ActiveOperation(
        val ticket: RuntimeModelOperationTicket
    )

    private val lock = Any()
    private val nextSequence = AtomicLong(initialSequence)
    private val activeOperations = linkedMapOf<Long, ActiveOperation>()

    fun admit(): RuntimeOperationAdmissionResult =
        when (val guarded = registry.withCurrentActiveSession { session ->
            synchronized(lock) {
                val inFlightForSession = activeOperations.values.count { it.ticket.session == session }
                if (inFlightForSession >= limits.maxInFlightOperationsPerSession) {
                    return@synchronized RuntimeOperationAdmissionResult.Rejected(
                        RuntimeHardeningFailure.RESOURCE_LIMIT_REJECTED
                    )
                }

                val nextValue = nextSequence.incrementAndGet()
                if (nextValue <= 0L) {
                    return@synchronized RuntimeOperationAdmissionResult.Rejected(
                        RuntimeHardeningFailure.OPERATION_REJECTED
                    )
                }

                val ticket = RuntimeModelOperationTicket(
                    sequence = RuntimeModelOperationSequence(nextValue),
                    session = session
                )
                activeOperations[nextValue] = ActiveOperation(ticket)
                RuntimeOperationAdmissionResult.Admitted(ticket)
            }
        }) {
            is RuntimeActiveSessionGuardResult.Available -> guarded.value
            RuntimeActiveSessionGuardResult.Unavailable ->
                RuntimeOperationAdmissionResult.Rejected(RuntimeHardeningFailure.SESSION_UNAVAILABLE)
        }

    fun release(
        ticket: RuntimeModelOperationTicket,
        terminal: RuntimeOperationTerminal,
        publishSuccess: () -> Unit = {}
    ): RuntimeOperationReleaseResult {
        val released = synchronized(lock) {
            val sequence = ticket.sequence.value
            val active = activeOperations[sequence]
            if (active == null || active.ticket != ticket) {
                return@synchronized false
            }

            activeOperations.remove(sequence)
            true
        }

        if (!released) return RuntimeOperationReleaseResult.AlreadyReleased

        when (terminal) {
            RuntimeOperationTerminal.FAILED ->
                return RuntimeOperationReleaseResult.Terminated(RuntimeHardeningFailure.OPERATION_FAILED)
            RuntimeOperationTerminal.CANCELLED ->
                return RuntimeOperationReleaseResult.Terminated(RuntimeHardeningFailure.OPERATION_CANCELLED)
            RuntimeOperationTerminal.TIMED_OUT ->
                return RuntimeOperationReleaseResult.Terminated(RuntimeHardeningFailure.OPERATION_TIMEOUT)
            RuntimeOperationTerminal.SUCCEEDED -> Unit
        }

        return when (val publication = registry.publishOperationIfCurrent(ticket.session, publishSuccess)) {
            RuntimeOperationPublicationResult.Published -> RuntimeOperationReleaseResult.Published
            RuntimeOperationPublicationResult.Stale -> RuntimeOperationReleaseResult.Stale
            is RuntimeOperationPublicationResult.Failed -> RuntimeOperationReleaseResult.Failed(
                reason = RuntimeHardeningFailure.OPERATION_FAILED,
                throwable = publication.throwable
            )
        }
    }

    fun inFlightCount(): Int = synchronized(lock) {
        activeOperations.size
    }

    fun inFlightCount(session: RuntimeModelSessionReference): Int = synchronized(lock) {
        activeOperations.values.count { it.ticket.session == session }
    }
}
