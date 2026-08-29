package pro.liliya.core.agent

import java.util.concurrent.ConcurrentHashMap
import pro.liliya.core.autonomy.AutonomyGeneration
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

data class ExactAgentDelegationReference(
    val id: AgentDelegationId,
    val generation: AgentDelegationGeneration
)

data class ExactAutonomyReference(
    val proposalId: AutonomyProposalId,
    val generation: AutonomyGeneration
)

data class AgentDelegatedWorkBinding(
    val delegation: ExactAgentDelegationReference,
    val child: ExactAgentReference,
    val autonomy: ExactAutonomyReference
)

internal interface AgentDelegatedWorkRegistration {
    val binding: AgentDelegatedWorkBinding
    fun remove(context: LogContext): Boolean
}

internal sealed interface AgentDelegatedWorkRegistrationResult {
    data class Registered(
        val registration: AgentDelegatedWorkRegistration
    ) : AgentDelegatedWorkRegistrationResult

    data class Rejected(val reason: String) : AgentDelegatedWorkRegistrationResult
}

/**
 * Exact structural binding between one delegated relation and one exact Autonomy generation.
 *
 * The Autonomy reference is the store key, so one exact Autonomy generation cannot be associated
 * with multiple delegations. The binding grants no permission and performs no work.
 */
internal class AgentDelegatedWorkBindingStore(
    private val observability: CoreObservability
) {
    private val bindings = ConcurrentHashMap<ExactAutonomyReference, AgentDelegatedWorkBinding>()

    fun register(
        binding: AgentDelegatedWorkBinding,
        context: LogContext
    ): AgentDelegatedWorkRegistrationResult {
        val existing = bindings.putIfAbsent(binding.autonomy, binding)
        if (existing != null) {
            val reason = "exact autonomy generation is already delegation-bound"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "AGENT_DELEGATED_WORK_BINDING_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(binding) + ("rejectionReason" to reason)
            )
            return AgentDelegatedWorkRegistrationResult.Rejected(reason)
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_DELEGATED_WORK_BOUND",
            message = "delegated work structurally bound",
            context = context,
            metadata = metadata(binding)
        )

        return AgentDelegatedWorkRegistrationResult.Registered(
            object : AgentDelegatedWorkRegistration {
                override val binding: AgentDelegatedWorkBinding = binding

                override fun remove(context: LogContext): Boolean {
                    val removed = bindings.remove(binding.autonomy, binding)
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) {
                            "AGENT_DELEGATED_WORK_UNBOUND"
                        } else {
                            "AGENT_DELEGATED_WORK_UNBIND_REJECTED"
                        },
                        message = if (removed) {
                            "delegated work binding removed"
                        } else {
                            "delegated work binding is no longer current"
                        },
                        context = context,
                        metadata = metadata(binding)
                    )
                    return removed
                }
            }
        )
    }

    fun find(autonomy: ExactAutonomyReference): AgentDelegatedWorkBinding? = bindings[autonomy]

    fun contains(autonomy: ExactAutonomyReference): Boolean = bindings.containsKey(autonomy)

    fun snapshot(): List<AgentDelegatedWorkBinding> = bindings.values
        .sortedWith(
            compareBy<AgentDelegatedWorkBinding>(
                { it.autonomy.proposalId.value },
                { it.autonomy.generation.value },
                { it.delegation.id.value },
                { it.delegation.generation.value }
            )
        )
        .toList()

    private fun metadata(binding: AgentDelegatedWorkBinding): Map<String, String> = mapOf(
        "agentDelegationId" to binding.delegation.id.value,
        "agentDelegationGeneration" to binding.delegation.generation.value.toString(),
        "childAgentId" to binding.child.id.value,
        "childAgentGeneration" to binding.child.generation.value.toString(),
        "autonomyProposalId" to binding.autonomy.proposalId.value,
        "autonomyGeneration" to binding.autonomy.generation.value.toString()
    )
}
