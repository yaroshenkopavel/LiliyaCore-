package pro.liliya.core.agent

import pro.liliya.core.foundation.FoundationComposition

interface AgentCoordinationOwnership {
    val coordination: AgentCoordinationRecord
    val generation: AgentCoordinationGeneration
    fun remove(): Boolean
}

sealed interface AgentCoordinationInstallResult {
    data class Installed(val ownership: AgentCoordinationOwnership) : AgentCoordinationInstallResult
    data class Rejected(val reason: String) : AgentCoordinationInstallResult
}

class AgentCoordinationComposition(
    private val foundation: FoundationComposition
) {
    private val store = AgentCoordinationStore(foundation.observability)

    fun install(coordination: AgentCoordinationRecord): AgentCoordinationInstallResult {
        val installContext = foundation.rootContext(
            operation = "installAgentCoordination",
            component = "AgentCoordination",
            metadata = metadata(coordination)
        )

        return when (val result = store.register(coordination, installContext)) {
            is AgentCoordinationRegistrationResult.Registered -> {
                val registration = result.registration
                AgentCoordinationInstallResult.Installed(
                    ownership = object : AgentCoordinationOwnership {
                        override val coordination: AgentCoordinationRecord = registration.coordination
                        override val generation: AgentCoordinationGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.childContext(
                                parent = installContext,
                                component = "AgentCoordination",
                                operation = "removeAgentCoordination",
                                metadata = metadata(coordination) +
                                    ("agentCoordinationGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is AgentCoordinationRegistrationResult.Rejected ->
                AgentCoordinationInstallResult.Rejected(result.reason)
        }
    }

    fun find(id: AgentCoordinationId): AgentCoordinationRecord? = store.find(id)
    fun inspect(id: AgentCoordinationId): AgentCoordinationSnapshot? = store.inspect(id)
    fun contains(id: AgentCoordinationId): Boolean = store.contains(id)
    fun snapshot(): List<AgentCoordinationRecord> = store.snapshot()
    fun snapshotEntries(): List<AgentCoordinationSnapshot> = store.snapshotEntries()

    private fun metadata(coordination: AgentCoordinationRecord): Map<String, String> = buildMap {
        put("agentCoordinationId", coordination.id.value)
        put("participantCount", coordination.participants.size.toString())
        coordination.participants.forEachIndexed { index, participant ->
            put("participant${index}AgentId", participant.id.value)
            put("participant${index}AgentGeneration", participant.generation.value.toString())
        }
        put("createdAt", coordination.createdAt.toString())
    }
}
