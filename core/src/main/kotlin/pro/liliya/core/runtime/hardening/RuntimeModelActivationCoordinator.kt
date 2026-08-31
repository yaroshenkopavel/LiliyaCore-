package pro.liliya.core.runtime.hardening

import pro.liliya.core.protectedmodel.ProtectedModelAccessResult

sealed interface RuntimeModelActivationResult<out T> {
    data class Activated<T>(
        val session: RuntimeModelSessionReference,
        val value: T
    ) : RuntimeModelActivationResult<T>

    data class Rejected(val reason: RuntimeHardeningFailure) : RuntimeModelActivationResult<Nothing>

    data class Failed(
        val reason: RuntimeHardeningFailure,
        val throwable: Throwable? = null
    ) : RuntimeModelActivationResult<Nothing> {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

/**
 * Runtime Hardening v0.1 activation boundary.
 *
 * The input is an already-opened value from the frozen protected-model access path. This coordinator
 * does not verify packages, decide License policy, grant Authority or execute capabilities. It only
 * binds that exact protected-model generation to one exact process-local runtime session and publishes
 * it behind the registry's atomic ownership barrier.
 */
class RuntimeModelActivationCoordinator(
    private val registry: RuntimeModelSessionRegistry
) {
    fun <T> activate(
        sessionId: RuntimeModelSessionId,
        openedModel: ProtectedModelAccessResult.Opened<T>,
        publish: (RuntimeModelSessionReference, T) -> Unit
    ): RuntimeModelActivationResult<T> {
        val registered = registry.register(sessionId, openedModel.reference)
        val ownership = when (registered) {
            is RuntimeSessionRegistrationResult.Registered -> registered.ownership
            is RuntimeSessionRegistrationResult.Rejected ->
                return RuntimeModelActivationResult.Rejected(RuntimeHardeningFailure.ACTIVATION_REJECTED)
        }

        return when (val publication = ownership.publishIfCurrent {
            publish(ownership.reference, openedModel.value)
        }) {
            RuntimeSessionPublicationResult.Published ->
                RuntimeModelActivationResult.Activated(ownership.reference, openedModel.value)

            RuntimeSessionPublicationResult.Stale ->
                RuntimeModelActivationResult.Rejected(RuntimeHardeningFailure.SESSION_STALE)

            is RuntimeSessionPublicationResult.Failed ->
                RuntimeModelActivationResult.Failed(
                    RuntimeHardeningFailure.ACTIVATION_FAILED,
                    publication.throwable
                )
        }
    }
}
