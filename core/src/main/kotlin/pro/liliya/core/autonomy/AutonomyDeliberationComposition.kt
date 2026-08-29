package pro.liliya.core.autonomy

import pro.liliya.core.foundation.FoundationComposition

interface AutonomyDeliberationOwnership {
    val request: AutonomyDeliberationRequest
    val generation: AutonomyDeliberationGeneration
    fun remove(): Boolean
}

sealed interface AutonomyDeliberationInstallResult {
    data class Installed(val ownership: AutonomyDeliberationOwnership) : AutonomyDeliberationInstallResult
    data class Rejected(val reason: String) : AutonomyDeliberationInstallResult
}

class AutonomyDeliberationComposition(
    private val foundation: FoundationComposition
) {
    private val store = AutonomyDeliberationStore(foundation.observability)

    fun install(request: AutonomyDeliberationRequest): AutonomyDeliberationInstallResult {
        val installContext = foundation.rootContext(
            operation = "installAutonomyDeliberationRequest",
            component = "Autonomy",
            metadata = metadata(request)
        )

        return when (val result = store.register(request, installContext)) {
            is AutonomyDeliberationRegistrationResult.Registered -> {
                val registration = result.registration
                AutonomyDeliberationInstallResult.Installed(
                    ownership = object : AutonomyDeliberationOwnership {
                        override val request: AutonomyDeliberationRequest = registration.request
                        override val generation: AutonomyDeliberationGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.childContext(
                                parent = installContext,
                                component = "Autonomy",
                                operation = "removeAutonomyDeliberationRequest",
                                metadata = metadata(request) +
                                    ("autonomyDeliberationGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is AutonomyDeliberationRegistrationResult.Rejected ->
                AutonomyDeliberationInstallResult.Rejected(result.reason)
        }
    }

    fun find(id: AutonomyDeliberationRequestId): AutonomyDeliberationRequest? = store.find(id)

    fun inspect(id: AutonomyDeliberationRequestId): AutonomyDeliberationSnapshot? = store.inspect(id)

    fun contains(id: AutonomyDeliberationRequestId): Boolean = store.find(id) != null

    fun snapshot(): List<AutonomyDeliberationRequest> = store.snapshot()

    private fun metadata(request: AutonomyDeliberationRequest): Map<String, String> = mapOf(
        "autonomyDeliberationRequestId" to request.id.value,
        "autonomyProposalId" to request.autonomy.proposalId.value,
        "autonomyGeneration" to request.autonomy.proposalGeneration.value.toString(),
        "autonomyAttemptNumber" to request.autonomy.attemptNumber.toString(),
        "createdAt" to request.createdAt.toString()
    )
}
