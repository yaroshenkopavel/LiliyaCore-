package pro.liliya.core.agent

import pro.liliya.core.foundation.FoundationComposition

interface AgentOwnership {
    val agent: AgentRecord
    val generation: AgentGeneration
    fun remove(): Boolean
}

sealed interface AgentInstallResult {
    data class Installed(val ownership: AgentOwnership) : AgentInstallResult
    data class Rejected(val reason: String) : AgentInstallResult
}

class AgentComposition(
    private val foundation: FoundationComposition
) {
    private val store = AgentStore(foundation.observability)

    fun install(agent: AgentRecord): AgentInstallResult {
        val installContext = foundation.rootContext(
            operation = "installAgent",
            component = "Agent",
            metadata = agentMetadata(agent)
        )

        return when (val result = store.register(agent, installContext)) {
            is AgentRegistrationResult.Registered -> {
                val registration = result.registration
                AgentInstallResult.Installed(
                    ownership = object : AgentOwnership {
                        override val agent: AgentRecord = registration.agent
                        override val generation: AgentGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.childContext(
                                parent = installContext,
                                component = "Agent",
                                operation = "removeAgent",
                                metadata = agentMetadata(agent) +
                                    ("agentGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is AgentRegistrationResult.Rejected -> AgentInstallResult.Rejected(result.reason)
        }
    }

    fun find(id: AgentId): AgentRecord? = store.find(id)
    fun inspect(id: AgentId): AgentSnapshot? = store.inspect(id)
    fun contains(id: AgentId): Boolean = store.contains(id)
    fun snapshot(): List<AgentRecord> = store.snapshot()
    fun snapshotEntries(): List<AgentSnapshot> = store.snapshotEntries()

    private fun agentMetadata(agent: AgentRecord): Map<String, String> = buildMap {
        put("agentId", agent.id.value)
        put("agentOriginType", when (agent.origin) {
            is AgentOrigin.Declared -> "declared"
            is AgentOrigin.Autonomy -> "autonomy"
        })
        when (val origin = agent.origin) {
            is AgentOrigin.Declared -> {
                put("agentSourceId", origin.sourceId.value)
                origin.sourceReference?.let { put("agentSourceReference", it.value) }
            }
            is AgentOrigin.Autonomy -> {
                put("autonomyProposalId", origin.proposalId.value)
                put("autonomyGeneration", origin.generation.value.toString())
            }
        }
        put("createdAt", agent.createdAt.toString())
    }
}
