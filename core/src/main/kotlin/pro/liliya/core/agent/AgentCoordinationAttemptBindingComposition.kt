package pro.liliya.core.agent

import pro.liliya.core.autonomy.AutonomyAttemptReference
import pro.liliya.core.foundation.FoundationComposition

interface AgentCoordinationAttemptBindingOwnership {
    val binding: AgentCoordinationAttemptBinding
    val generation: AgentCoordinationAttemptBindingGeneration
    fun remove(): Boolean
}

sealed interface AgentCoordinationAttemptBindingInstallResult {
    data class Installed(
        val ownership: AgentCoordinationAttemptBindingOwnership
    ) : AgentCoordinationAttemptBindingInstallResult

    data class Rejected(val reason: String) : AgentCoordinationAttemptBindingInstallResult
}

/** Controlled ownership boundary over the private coordination-attempt binding store. */
class AgentCoordinationAttemptBindingComposition(
    private val foundation: FoundationComposition
) {
    private val store = AgentCoordinationAttemptBindingStore(foundation.observability)

    fun install(
        binding: AgentCoordinationAttemptBinding
    ): AgentCoordinationAttemptBindingInstallResult {
        val installContext = foundation.rootContext(
            operation = "installAgentCoordinationAttemptBinding",
            component = "AgentCoordination",
            metadata = metadata(binding)
        )

        return when (val result = store.register(binding, installContext)) {
            is AgentCoordinationAttemptBindingRegistrationResult.Registered -> {
                val registration = result.registration
                AgentCoordinationAttemptBindingInstallResult.Installed(
                    ownership = object : AgentCoordinationAttemptBindingOwnership {
                        override val binding: AgentCoordinationAttemptBinding = registration.binding
                        override val generation: AgentCoordinationAttemptBindingGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.childContext(
                                parent = installContext,
                                component = "AgentCoordination",
                                operation = "removeAgentCoordinationAttemptBinding",
                                metadata = metadata(binding) +
                                    ("coordinationAttemptBindingGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is AgentCoordinationAttemptBindingRegistrationResult.Rejected ->
                AgentCoordinationAttemptBindingInstallResult.Rejected(result.reason)
        }
    }

    fun find(
        coordination: ExactAgentCoordinationReference
    ): AgentCoordinationAttemptBinding? = store.find(coordination)

    fun inspect(
        coordination: ExactAgentCoordinationReference
    ): AgentCoordinationAttemptBindingSnapshot? = store.inspect(coordination)

    fun findByAttempt(
        attempt: AutonomyAttemptReference
    ): AgentCoordinationAttemptBinding? = store.findByAttempt(attempt)

    fun snapshot(): List<AgentCoordinationAttemptBindingSnapshot> = store.snapshot()

    private fun metadata(binding: AgentCoordinationAttemptBinding): Map<String, String> = buildMap {
        put("agentCoordinationId", binding.coordination.id.value)
        put("agentCoordinationGeneration", binding.coordination.generation.value.toString())
        put("assignmentCount", binding.assignments.size.toString())
        binding.assignments.forEachIndexed { index, assignment ->
            put("assignment${index}AgentId", assignment.participant.id.value)
            put("assignment${index}AgentGeneration", assignment.participant.generation.value.toString())
            put("assignment${index}AutonomyProposalId", assignment.attempt.proposalId.value)
            put("assignment${index}AutonomyGeneration", assignment.attempt.proposalGeneration.value.toString())
            put("assignment${index}AttemptNumber", assignment.attempt.attemptNumber.toString())
        }
    }
}
