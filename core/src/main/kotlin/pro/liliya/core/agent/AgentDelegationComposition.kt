package pro.liliya.core.agent

import pro.liliya.core.foundation.FoundationComposition

interface AgentDelegationOwnership {
    val delegation: AgentDelegationRecord
    val generation: AgentDelegationGeneration
    fun remove(): Boolean
}

sealed interface AgentDelegationInstallResult {
    data class Installed(val ownership: AgentDelegationOwnership) : AgentDelegationInstallResult
    data class Rejected(val reason: String) : AgentDelegationInstallResult
}

class AgentDelegationComposition(
    private val foundation: FoundationComposition
) {
    private val store = AgentDelegationStore(foundation.observability)

    fun install(delegation: AgentDelegationRecord): AgentDelegationInstallResult {
        val installContext = foundation.rootContext(
            operation = "installAgentDelegation",
            component = "AgentDelegation",
            metadata = metadata(delegation)
        )

        return when (val result = store.register(delegation, installContext)) {
            is AgentDelegationRegistrationResult.Registered -> {
                val registration = result.registration
                AgentDelegationInstallResult.Installed(
                    ownership = object : AgentDelegationOwnership {
                        override val delegation: AgentDelegationRecord = registration.delegation
                        override val generation: AgentDelegationGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.childContext(
                                parent = installContext,
                                component = "AgentDelegation",
                                operation = "removeAgentDelegation",
                                metadata = metadata(delegation) +
                                    ("agentDelegationGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is AgentDelegationRegistrationResult.Rejected ->
                AgentDelegationInstallResult.Rejected(result.reason)
        }
    }

    fun find(id: AgentDelegationId): AgentDelegationRecord? = store.find(id)
    fun inspect(id: AgentDelegationId): AgentDelegationSnapshot? = store.inspect(id)
    fun contains(id: AgentDelegationId): Boolean = store.contains(id)
    fun snapshot(): List<AgentDelegationRecord> = store.snapshot()
    fun snapshotEntries(): List<AgentDelegationSnapshot> = store.snapshotEntries()

    private fun metadata(delegation: AgentDelegationRecord): Map<String, String> = mapOf(
        "agentDelegationId" to delegation.id.value,
        "parentAgentId" to delegation.parent.id.value,
        "parentAgentGeneration" to delegation.parent.generation.value.toString(),
        "childAgentId" to delegation.child.id.value,
        "childAgentGeneration" to delegation.child.generation.value.toString(),
        "createdAt" to delegation.createdAt.toString()
    )
}
