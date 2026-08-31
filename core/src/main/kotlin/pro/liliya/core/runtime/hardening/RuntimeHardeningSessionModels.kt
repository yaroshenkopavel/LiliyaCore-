package pro.liliya.core.runtime.hardening

import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.protectedmodel.ProtectedModelReference

@JvmInline
value class RuntimeModelSessionId(val value: String) {
    init { require(value.isNotBlank()) { "runtime model session id must not be blank" } }
    override fun toString(): String = "RuntimeModelSessionId([redacted])"
}

@JvmInline
value class RuntimeModelSessionGeneration(val value: Long) {
    init { require(value > 0L) { "runtime model session generation must be positive" } }
    override fun toString(): String = value.toString()
}

data class RuntimeModelSessionReference(
    val id: RuntimeModelSessionId,
    val generation: RuntimeModelSessionGeneration,
    val model: ProtectedModelReference
)

data class RuntimeHardeningLimits(
    val maxLiveSessions: Int = 1,
    val maxInFlightOperationsPerSession: Int,
    val maxDiagnosticSnapshotEntries: Int = 32
) {
    init {
        require(maxLiveSessions == 1) { "Runtime Hardening v0.1 supports exactly one live runtime session" }
        require(maxInFlightOperationsPerSession > 0) {
            "maximum in-flight operations per session must be positive"
        }
        require(maxDiagnosticSnapshotEntries > 0) {
            "maximum diagnostic snapshot entries must be positive"
        }
    }
}

enum class RuntimeModelSessionLifecycle {
    PREPARED,
    ACTIVE,
    QUIESCING,
    FAILED,
    RETIRED
}

enum class RuntimeHardeningFailure {
    ACTIVATION_REJECTED,
    ACTIVATION_FAILED,
    SESSION_STALE,
    SESSION_UNAVAILABLE,
    SESSION_FAILED,
    RESOURCE_LIMIT_REJECTED,
    OPERATION_REJECTED,
    OPERATION_FAILED,
    OPERATION_CANCELLED,
    OPERATION_TIMEOUT,
    RETIREMENT_FAILED,
    RECOVERY_REJECTED,
    PROVIDER_FAILED
}

sealed interface RuntimeSessionRegistrationResult {
    data class Registered(val ownership: RuntimeModelSessionOwnership) : RuntimeSessionRegistrationResult
    data class Rejected(val reason: RuntimeSessionRegistrationFailure) : RuntimeSessionRegistrationResult
}

enum class RuntimeSessionRegistrationFailure {
    LIVE_SESSION_EXISTS,
    GENERATION_OVERFLOW
}

sealed interface RuntimeSessionPublicationResult {
    data object Published : RuntimeSessionPublicationResult
    data object Stale : RuntimeSessionPublicationResult
    data class Failed(val throwable: Throwable) : RuntimeSessionPublicationResult {
        override fun toString(): String = "Failed(throwable=${throwable.javaClass.name})"
    }
}

internal sealed interface RuntimeActiveSessionGuardResult<out T> {
    data class Available<T>(val value: T) : RuntimeActiveSessionGuardResult<T>
    data object Unavailable : RuntimeActiveSessionGuardResult<Nothing>
}

internal sealed interface RuntimeOperationPublicationResult {
    data object Published : RuntimeOperationPublicationResult
    data object Stale : RuntimeOperationPublicationResult
    data class Failed(val throwable: Throwable) : RuntimeOperationPublicationResult
}

internal sealed interface RuntimeSessionQuiescingTransitionResult {
    data object Quiescing : RuntimeSessionQuiescingTransitionResult
    data object AlreadyQuiescing : RuntimeSessionQuiescingTransitionResult
    data object Stale : RuntimeSessionQuiescingTransitionResult
}

internal sealed interface RuntimeSessionFailureTransitionResult {
    data class Failed(val reason: RuntimeHardeningFailure) : RuntimeSessionFailureTransitionResult
    data class AlreadyFailed(val reason: RuntimeHardeningFailure) : RuntimeSessionFailureTransitionResult
    data object Stale : RuntimeSessionFailureTransitionResult
}

interface RuntimeModelSessionOwnership {
    val reference: RuntimeModelSessionReference
    fun isCurrent(): Boolean
    fun lifecycle(): RuntimeModelSessionLifecycle
    fun publishIfCurrent(publish: () -> Unit): RuntimeSessionPublicationResult
    fun retire(): Boolean
}

/**
 * Process-local exact ownership for Runtime Hardening v0.1.
 *
 * This registry owns structural runtime-session identity only. It is not License, Authority,
 * capability, protected-model policy or execution permission. V0.1 intentionally permits at most
 * one live runtime session in one registry/composition.
 */
class RuntimeModelSessionRegistry internal constructor(
    initialGeneration: Long = 0L
) {
    private data class Entry(
        val reference: RuntimeModelSessionReference,
        var lifecycle: RuntimeModelSessionLifecycle = RuntimeModelSessionLifecycle.PREPARED,
        var failure: RuntimeHardeningFailure? = null,
        var publicationInProgress: Boolean = false
    )

    private val lock = Any()
    private val nextGeneration = AtomicLong(initialGeneration)
    private var current: Entry? = null
    private var operationSupervisorClaimed = false

    fun register(
        id: RuntimeModelSessionId,
        model: ProtectedModelReference
    ): RuntimeSessionRegistrationResult = synchronized(lock) {
        check(current?.publicationInProgress != true) {
            "runtime session registration is not allowed from inside publication"
        }
        if (current != null) {
            return@synchronized RuntimeSessionRegistrationResult.Rejected(
                RuntimeSessionRegistrationFailure.LIVE_SESSION_EXISTS
            )
        }

        val nextValue = nextGeneration.incrementAndGet()
        if (nextValue <= 0L) {
            return@synchronized RuntimeSessionRegistrationResult.Rejected(
                RuntimeSessionRegistrationFailure.GENERATION_OVERFLOW
            )
        }

        val entry = Entry(
            RuntimeModelSessionReference(
                id = id,
                generation = RuntimeModelSessionGeneration(nextValue),
                model = model
            )
        )
        current = entry
        RuntimeSessionRegistrationResult.Registered(ownership(entry))
    }

    fun currentReference(): RuntimeModelSessionReference? = synchronized(lock) {
        current?.reference
    }

    fun currentLifecycle(): RuntimeModelSessionLifecycle? = synchronized(lock) {
        current?.lifecycle
    }

    fun currentFailure(): RuntimeHardeningFailure? = synchronized(lock) {
        current?.failure
    }

    fun snapshot(): List<RuntimeModelSessionReference> = synchronized(lock) {
        listOfNotNull(current?.reference)
    }

    internal fun claimOperationSupervisor(): Boolean = synchronized(lock) {
        if (operationSupervisorClaimed) {
            false
        } else {
            operationSupervisorClaimed = true
            true
        }
    }

    internal fun <T> withCurrentActiveSession(
        block: (RuntimeModelSessionReference) -> T
    ): RuntimeActiveSessionGuardResult<T> = synchronized(lock) {
        val entry = current
        if (
            entry == null ||
            entry.lifecycle != RuntimeModelSessionLifecycle.ACTIVE ||
            entry.publicationInProgress
        ) {
            RuntimeActiveSessionGuardResult.Unavailable
        } else {
            RuntimeActiveSessionGuardResult.Available(block(entry.reference))
        }
    }

    internal fun beginQuiescingIfCurrent(
        reference: RuntimeModelSessionReference
    ): RuntimeSessionQuiescingTransitionResult = synchronized(lock) {
        val entry = current
        if (entry == null || entry.reference != reference) {
            return@synchronized RuntimeSessionQuiescingTransitionResult.Stale
        }
        check(!entry.publicationInProgress) {
            "runtime session quiescing is not allowed from inside publication"
        }
        when (entry.lifecycle) {
            RuntimeModelSessionLifecycle.ACTIVE -> {
                entry.lifecycle = RuntimeModelSessionLifecycle.QUIESCING
                RuntimeSessionQuiescingTransitionResult.Quiescing
            }
            RuntimeModelSessionLifecycle.QUIESCING ->
                RuntimeSessionQuiescingTransitionResult.AlreadyQuiescing
            else -> RuntimeSessionQuiescingTransitionResult.Stale
        }
    }

    internal fun failIfCurrent(
        reference: RuntimeModelSessionReference,
        reason: RuntimeHardeningFailure
    ): RuntimeSessionFailureTransitionResult = synchronized(lock) {
        require(reason == RuntimeHardeningFailure.SESSION_FAILED || reason == RuntimeHardeningFailure.PROVIDER_FAILED) {
            "runtime session failure reason must be SESSION_FAILED or PROVIDER_FAILED"
        }
        val entry = current
        if (entry == null || entry.reference != reference) {
            return@synchronized RuntimeSessionFailureTransitionResult.Stale
        }
        check(!entry.publicationInProgress) {
            "runtime session failure transition is not allowed from inside publication"
        }
        when (entry.lifecycle) {
            RuntimeModelSessionLifecycle.ACTIVE,
            RuntimeModelSessionLifecycle.QUIESCING -> {
                entry.lifecycle = RuntimeModelSessionLifecycle.FAILED
                entry.failure = reason
                RuntimeSessionFailureTransitionResult.Failed(reason)
            }
            RuntimeModelSessionLifecycle.FAILED ->
                RuntimeSessionFailureTransitionResult.AlreadyFailed(
                    checkNotNull(entry.failure) { "failed runtime session must retain structural failure reason" }
                )
            else -> RuntimeSessionFailureTransitionResult.Stale
        }
    }

    internal fun isCurrentQuiescing(reference: RuntimeModelSessionReference): Boolean = synchronized(lock) {
        val entry = current
        entry != null &&
            entry.reference == reference &&
            entry.lifecycle == RuntimeModelSessionLifecycle.QUIESCING &&
            !entry.publicationInProgress
    }

    internal fun retireQuiescingIfCurrent(reference: RuntimeModelSessionReference): Boolean = synchronized(lock) {
        val entry = current
        if (
            entry == null ||
            entry.reference != reference ||
            entry.lifecycle != RuntimeModelSessionLifecycle.QUIESCING
        ) {
            return@synchronized false
        }
        check(!entry.publicationInProgress) {
            "runtime session retirement is not allowed from inside publication"
        }
        entry.lifecycle = RuntimeModelSessionLifecycle.RETIRED
        current = null
        true
    }

    internal fun retireFailedIfCurrent(reference: RuntimeModelSessionReference): Boolean = synchronized(lock) {
        val entry = current
        if (
            entry == null ||
            entry.reference != reference ||
            entry.lifecycle != RuntimeModelSessionLifecycle.FAILED
        ) {
            return@synchronized false
        }
        check(!entry.publicationInProgress) {
            "failed runtime session retirement is not allowed from inside publication"
        }
        entry.lifecycle = RuntimeModelSessionLifecycle.RETIRED
        current = null
        true
    }

    internal fun publishOperationIfCurrent(
        reference: RuntimeModelSessionReference,
        publish: () -> Unit
    ): RuntimeOperationPublicationResult = synchronized(lock) {
        val entry = current
        if (entry == null || entry.reference != reference || entry.lifecycle != RuntimeModelSessionLifecycle.ACTIVE) {
            return@synchronized RuntimeOperationPublicationResult.Stale
        }
        check(!entry.publicationInProgress) { "nested runtime session publication is not allowed" }
        entry.publicationInProgress = true
        try {
            publish()
            if (current !== entry || entry.lifecycle != RuntimeModelSessionLifecycle.ACTIVE) {
                RuntimeOperationPublicationResult.Stale
            } else {
                RuntimeOperationPublicationResult.Published
            }
        } catch (throwable: Throwable) {
            RuntimeOperationPublicationResult.Failed(throwable)
        } finally {
            entry.publicationInProgress = false
        }
    }

    private fun ownership(entry: Entry): RuntimeModelSessionOwnership =
        object : RuntimeModelSessionOwnership {
            override val reference: RuntimeModelSessionReference = entry.reference

            override fun isCurrent(): Boolean = synchronized(lock) {
                current === entry
            }

            override fun lifecycle(): RuntimeModelSessionLifecycle = synchronized(lock) {
                entry.lifecycle
            }

            override fun publishIfCurrent(publish: () -> Unit): RuntimeSessionPublicationResult = synchronized(lock) {
                if (current !== entry || entry.lifecycle != RuntimeModelSessionLifecycle.PREPARED) {
                    return@synchronized RuntimeSessionPublicationResult.Stale
                }
                check(!entry.publicationInProgress) { "nested runtime session publication is not allowed" }
                entry.publicationInProgress = true
                try {
                    publish()
                    if (current !== entry) {
                        return@synchronized RuntimeSessionPublicationResult.Stale
                    }
                    entry.lifecycle = RuntimeModelSessionLifecycle.ACTIVE
                    RuntimeSessionPublicationResult.Published
                } catch (throwable: Throwable) {
                    if (current === entry) {
                        entry.lifecycle = RuntimeModelSessionLifecycle.FAILED
                        entry.failure = RuntimeHardeningFailure.ACTIVATION_FAILED
                        current = null
                    }
                    RuntimeSessionPublicationResult.Failed(throwable)
                } finally {
                    entry.publicationInProgress = false
                }
            }

            override fun retire(): Boolean = synchronized(lock) {
                check(!entry.publicationInProgress) {
                    "runtime session retirement is not allowed from inside publication"
                }
                if (current !== entry) return@synchronized false
                if (operationSupervisorClaimed && entry.lifecycle != RuntimeModelSessionLifecycle.PREPARED) {
                    return@synchronized false
                }
                entry.lifecycle = RuntimeModelSessionLifecycle.RETIRED
                current = null
                true
            }
        }
}
