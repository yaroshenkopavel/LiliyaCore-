package pro.liliya.core.agent

import pro.liliya.core.foundation.FoundationComposition

interface AgentDelegatedWorkBindingOwnership {
    val binding: AgentDelegatedWorkBinding
    fun remove(): Boolean
}

sealed interface AgentDelegatedWorkBindingInstallResult {
    data class Installed(
        val ownership: AgentDelegatedWorkBindingOwnership
    ) : AgentDelegatedWorkBindingInstallResult

    data class Rejected(val reason: String) : AgentDelegatedWorkBindingInstallResult
}

/** Controlled ownership boundary over the private delegated-work binding store. */
class AgentDelegatedWorkBindingComposition(
    private val foundation: FoundationComposition
) {
    private val store = AgentDelegatedWorkBindingStore(foundation.observability)

    fun install(binding: AgentDelegatedWorkBinding): AgentDelegatedWorkBindingInstallResult {
        val installContext = foundation.rootContext(
            operation = "installAgentDelegatedWorkBinding",
            component = "AgentDelegation",
            metadata = metadata(binding)
        )

        return when (val result = store.register(binding, installContext)) {
            is AgentDelegatedWorkRegistrationResult.Registered -> {
                val registration = result.registration
                AgentDelegatedWorkBindingInstallResult.Installed(
                    ownership = object : AgentDelegatedWorkBindingOwnership {
                        override val binding: AgentDelegatedWorkBinding = registration.binding

                        override fun remove(): Boolean = registration.remove(
                            foundation.childContext(
                                parent = installContext,
                                component = "AgentDelegation",
                                operation = "removeAgentDelegatedWorkBinding",
                                metadata = metadata(binding)
                            )
                        )
                    }
                )
            }

            is AgentDelegatedWorkRegistrationResult.Rejected ->
                AgentDelegatedWorkBindingInstallResult.Rejected(result.reason)
        }
    }

    fun find(autonomy: ExactAutonomyReference): AgentDelegatedWorkBinding? = store.find(autonomy)
    fun contains(autonomy: ExactAutonomyReference): Boolean = store.contains(autonomy)
    fun snapshot(): List<AgentDelegatedWorkBinding> = store.snapshot()

    private fun metadata(binding: AgentDelegatedWorkBinding): Map<String, String> = mapOf(
        "agentDelegationId" to binding.delegation.id.value,
        "agentDelegationGeneration" to binding.delegation.generation.value.toString(),
        "childAgentId" to binding.child.id.value,
        "childAgentGeneration" to binding.child.generation.value.toString(),
        "autonomyProposalId" to binding.autonomy.proposalId.value,
        "autonomyGeneration" to binding.autonomy.generation.value.toString()
    )
}
