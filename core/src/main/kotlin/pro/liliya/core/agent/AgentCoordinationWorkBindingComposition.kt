package pro.liliya.core.agent

import pro.liliya.core.foundation.FoundationComposition

interface AgentCoordinationWorkBindingOwnership {
    val binding: AgentCoordinationWorkBinding
    val generation: AgentCoordinationWorkBindingGeneration
    fun remove(): Boolean
}

sealed interface AgentCoordinationWorkBindingInstallResult {
    data class Installed(
        val ownership: AgentCoordinationWorkBindingOwnership
    ) : AgentCoordinationWorkBindingInstallResult

    data class Rejected(val reason: String) : AgentCoordinationWorkBindingInstallResult
}

/** Controlled ownership boundary over the private coordination-work binding store. */
class AgentCoordinationWorkBindingComposition(
    private val foundation: FoundationComposition
) {
    private val store = AgentCoordinationWorkBindingStore(foundation.observability)

    fun install(binding: AgentCoordinationWorkBinding): AgentCoordinationWorkBindingInstallResult {
        val installContext = foundation.rootContext(
            operation = "installAgentCoordinationWorkBinding",
            component = "AgentCoordination",
            metadata = metadata(binding)
        )

        return when (val result = store.register(binding, installContext)) {
            is AgentCoordinationWorkBindingRegistrationResult.Registered -> {
                val registration = result.registration
                AgentCoordinationWorkBindingInstallResult.Installed(
                    ownership = object : AgentCoordinationWorkBindingOwnership {
                        override val binding: AgentCoordinationWorkBinding = registration.binding
                        override val generation: AgentCoordinationWorkBindingGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.childContext(
                                parent = installContext,
                                component = "AgentCoordination",
                                operation = "removeAgentCoordinationWorkBinding",
                                metadata = metadata(binding) +
                                    ("coordinationWorkBindingGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is AgentCoordinationWorkBindingRegistrationResult.Rejected ->
                AgentCoordinationWorkBindingInstallResult.Rejected(result.reason)
        }
    }

    fun find(coordination: ExactAgentCoordinationReference): AgentCoordinationWorkBinding? =
        store.find(coordination)

    fun inspect(coordination: ExactAgentCoordinationReference): AgentCoordinationWorkBindingSnapshot? =
        store.inspect(coordination)

    fun findByAutonomy(autonomy: ExactAutonomyReference): AgentCoordinationWorkBinding? =
        store.findByAutonomy(autonomy)

    fun snapshot(): List<AgentCoordinationWorkBindingSnapshot> = store.snapshot()

    private fun metadata(binding: AgentCoordinationWorkBinding): Map<String, String> = buildMap {
        put("agentCoordinationId", binding.coordination.id.value)
        put("agentCoordinationGeneration", binding.coordination.generation.value.toString())
        put("assignmentCount", binding.assignments.size.toString())
        binding.assignments.forEachIndexed { index, assignment ->
            put("assignment${index}AgentId", assignment.participant.id.value)
            put("assignment${index}AgentGeneration", assignment.participant.generation.value.toString())
            put("assignment${index}AutonomyProposalId", assignment.autonomy.proposalId.value)
            put("assignment${index}AutonomyGeneration", assignment.autonomy.generation.value.toString())
        }
    }
}
