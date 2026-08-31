package pro.liliya.core.runtime.hardening

import java.util.concurrent.atomic.AtomicLong

@JvmInline
value class RuntimeModelOperationSequence(val value: Long) {
    init { require(value > 0L) { "runtime model operation sequence must be positive" } }
    override fun toString(): String = value.toString()
}

class RuntimeModelOperationTicket internal constructor(
    val sequence: RuntimeModelOperationSequence,
    val session: RuntimeModelSessionReference
)

sealed interface RuntimeOperationAdmissionResult {
    data class Admitted(val ticket: RuntimeModelOperationTicket) : RuntimeOperationAdmissionResult
    data class Rejected(val reason: RuntimeHardeningFailure) : RuntimeOperationAdmissionResult
}

enum class RuntimeOperationTerminal { SUCCEEDED, FAILED, CANCELLED, TIMED_OUT }

sealed interface RuntimeOperationReleaseResult {
    data object Published : RuntimeOperationReleaseResult
    data object Stale : RuntimeOperationReleaseResult
    data object AlreadyReleased : RuntimeOperationReleaseResult
    data class Terminated(val reason: RuntimeHardeningFailure) : RuntimeOperationReleaseResult
    data class Failed(val reason: RuntimeHardeningFailure, val throwable: Throwable) : RuntimeOperationReleaseResult {
        override fun toString(): String = "Failed(reason=$reason, throwable=${throwable.javaClass.name})"
    }
}

sealed interface RuntimeSessionQuiescenceResult {
    data object Quiescing : RuntimeSessionQuiescenceResult
    data object AlreadyQuiescing : RuntimeSessionQuiescenceResult
    data object Stale : RuntimeSessionQuiescenceResult
}

sealed interface RuntimeSessionDrainRetirementResult {
    data object Retired : RuntimeSessionDrainRetirementResult
    data class DrainRequired(val inFlightOperations: Int) : RuntimeSessionDrainRetirementResult
    data object Stale : RuntimeSessionDrainRetirementResult
    data class Failed(val reason: RuntimeHardeningFailure, val throwable: Throwable) : RuntimeSessionDrainRetirementResult {
        override fun toString(): String = "Failed(reason=$reason, throwable=${throwable.javaClass.name})"
    }
}

sealed interface RuntimeSessionFailureResult {
    data class Failed(val reason: RuntimeHardeningFailure) : RuntimeSessionFailureResult
    data class AlreadyFailed(val reason: RuntimeHardeningFailure) : RuntimeSessionFailureResult
    data object Stale : RuntimeSessionFailureResult
}

sealed interface RuntimeFailedSessionRetirementResult {
    data object Retired : RuntimeFailedSessionRetirementResult
    data object Stale : RuntimeFailedSessionRetirementResult
}

sealed interface RuntimeRetirementRecoveryResult {
    data object Retired : RuntimeRetirementRecoveryResult
    data object Stale : RuntimeRetirementRecoveryResult
    data class Failed(val reason: RuntimeHardeningFailure, val throwable: Throwable) : RuntimeRetirementRecoveryResult {
        override fun toString(): String = "Failed(reason=$reason, throwable=${throwable.javaClass.name})"
    }
}

/** Process-local operation supervision for one Runtime Hardening v0.1 composition. */
class RuntimeModelOperationSupervisor internal constructor(
    private val registry: RuntimeModelSessionRegistry,
    private val limits: RuntimeHardeningLimits,
    initialSequence: Long = 0L
) {
    private data class ActiveOperation(val ticket: RuntimeModelOperationTicket)

    init {
        check(registry.claimOperationSupervisor()) {
            "runtime model session registry already owns an operation supervisor"
        }
    }

    private val lock = Any()
    private val nextSequence = AtomicLong(initialSequence)
    private val activeOperations = linkedMapOf<Long, ActiveOperation>()

    fun admit(): RuntimeOperationAdmissionResult =
        when (val guarded = registry.withCurrentActiveSession { session ->
            synchronized(lock) {
                val inFlightForSession = activeOperations.values.count { it.ticket.session == session }
                if (inFlightForSession >= limits.maxInFlightOperationsPerSession) {
                    return@synchronized RuntimeOperationAdmissionResult.Rejected(RuntimeHardeningFailure.RESOURCE_LIMIT_REJECTED)
                }
                val nextValue = nextSequence.incrementAndGet()
                if (nextValue <= 0L) {
                    return@synchronized RuntimeOperationAdmissionResult.Rejected(RuntimeHardeningFailure.OPERATION_REJECTED)
                }
                val ticket = RuntimeModelOperationTicket(RuntimeModelOperationSequence(nextValue), session)
                activeOperations[nextValue] = ActiveOperation(ticket)
                RuntimeOperationAdmissionResult.Admitted(ticket)
            }
        }) {
            is RuntimeActiveSessionGuardResult.Available -> guarded.value
            RuntimeActiveSessionGuardResult.Unavailable -> RuntimeOperationAdmissionResult.Rejected(RuntimeHardeningFailure.SESSION_UNAVAILABLE)
        }

    fun beginQuiescing(session: RuntimeModelSessionReference): RuntimeSessionQuiescenceResult =
        when (registry.beginQuiescingIfCurrent(session)) {
            RuntimeSessionQuiescingTransitionResult.Quiescing -> RuntimeSessionQuiescenceResult.Quiescing
            RuntimeSessionQuiescingTransitionResult.AlreadyQuiescing -> RuntimeSessionQuiescenceResult.AlreadyQuiescing
            RuntimeSessionQuiescingTransitionResult.Stale -> RuntimeSessionQuiescenceResult.Stale
        }

    fun retireIfDrained(session: RuntimeModelSessionReference, retire: () -> Unit = {}): RuntimeSessionDrainRetirementResult {
        if (!registry.isCurrentQuiescing(session)) return RuntimeSessionDrainRetirementResult.Stale
        val inFlight = synchronized(lock) { activeOperations.values.count { it.ticket.session == session } }
        if (inFlight > 0) return RuntimeSessionDrainRetirementResult.DrainRequired(inFlight)
        return when (val transition = registry.retireQuiescingIfCurrent(session, retire)) {
            RuntimeSessionRetirementTransitionResult.Retired -> RuntimeSessionDrainRetirementResult.Retired
            RuntimeSessionRetirementTransitionResult.Stale -> RuntimeSessionDrainRetirementResult.Stale
            is RuntimeSessionRetirementTransitionResult.Failed -> RuntimeSessionDrainRetirementResult.Failed(
                RuntimeHardeningFailure.RETIREMENT_FAILED,
                transition.throwable
            )
        }
    }

    fun failSession(session: RuntimeModelSessionReference, reason: RuntimeHardeningFailure): RuntimeSessionFailureResult =
        when (val transition = registry.failIfCurrent(session, reason)) {
            is RuntimeSessionFailureTransitionResult.Failed -> RuntimeSessionFailureResult.Failed(transition.reason)
            is RuntimeSessionFailureTransitionResult.AlreadyFailed -> RuntimeSessionFailureResult.AlreadyFailed(transition.reason)
            RuntimeSessionFailureTransitionResult.Stale -> RuntimeSessionFailureResult.Stale
        }

    fun retireFailed(session: RuntimeModelSessionReference): RuntimeFailedSessionRetirementResult =
        if (registry.retireFailedIfCurrent(session)) RuntimeFailedSessionRetirementResult.Retired
        else RuntimeFailedSessionRetirementResult.Stale

    fun recoverRetirementFailure(
        session: RuntimeModelSessionReference,
        recoverCleanup: () -> Unit
    ): RuntimeRetirementRecoveryResult =
        when (val transition = registry.recoverRetirementFailureIfCurrent(session, recoverCleanup)) {
            RuntimeSessionRetirementTransitionResult.Retired -> RuntimeRetirementRecoveryResult.Retired
            RuntimeSessionRetirementTransitionResult.Stale -> RuntimeRetirementRecoveryResult.Stale
            is RuntimeSessionRetirementTransitionResult.Failed -> RuntimeRetirementRecoveryResult.Failed(
                RuntimeHardeningFailure.RECOVERY_REJECTED,
                transition.throwable
            )
        }

    fun release(
        ticket: RuntimeModelOperationTicket,
        terminal: RuntimeOperationTerminal,
        publishSuccess: () -> Unit = {}
    ): RuntimeOperationReleaseResult {
        val released = synchronized(lock) {
            val sequence = ticket.sequence.value
            val active = activeOperations[sequence]
            if (active == null || active.ticket !== ticket) return@synchronized false
            activeOperations.remove(sequence)
            true
        }
        if (!released) return RuntimeOperationReleaseResult.AlreadyReleased
        when (terminal) {
            RuntimeOperationTerminal.FAILED -> return RuntimeOperationReleaseResult.Terminated(RuntimeHardeningFailure.OPERATION_FAILED)
            RuntimeOperationTerminal.CANCELLED -> return RuntimeOperationReleaseResult.Terminated(RuntimeHardeningFailure.OPERATION_CANCELLED)
            RuntimeOperationTerminal.TIMED_OUT -> return RuntimeOperationReleaseResult.Terminated(RuntimeHardeningFailure.OPERATION_TIMEOUT)
            RuntimeOperationTerminal.SUCCEEDED -> Unit
        }
        return when (val publication = registry.publishOperationIfCurrent(ticket.session, publishSuccess)) {
            RuntimeOperationPublicationResult.Published -> RuntimeOperationReleaseResult.Published
            RuntimeOperationPublicationResult.Stale -> RuntimeOperationReleaseResult.Stale
            is RuntimeOperationPublicationResult.Failed -> RuntimeOperationReleaseResult.Failed(
                RuntimeHardeningFailure.OPERATION_FAILED,
                publication.throwable
            )
        }
    }

    fun inFlightCount(): Int = synchronized(lock) { activeOperations.size }
    fun inFlightCount(session: RuntimeModelSessionReference): Int = synchronized(lock) {
        activeOperations.values.count { it.ticket.session == session }
    }
}
