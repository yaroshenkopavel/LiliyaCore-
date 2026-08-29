package pro.liliya.core.agent

import java.time.Instant
import pro.liliya.core.autonomy.AutonomyBudget
import pro.liliya.core.autonomy.AutonomyPriority
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

data class AgentDelegatedInitiativeRequest(
    val delegationId: AgentDelegationId,
    val delegationGeneration: AgentDelegationGeneration,
    val autonomyProposalId: AutonomyProposalId,
    val objective: String,
    val triggerDescription: String,
    val priority: AutonomyPriority,
    val budget: AutonomyBudget,
    val createdAt: Instant
) {
    init {
        require(objective.isNotBlank()) { "delegated initiative objective must not be blank" }
        require(triggerDescription.isNotBlank()) { "delegated initiative trigger description must not be blank" }
    }
}

sealed interface AgentDelegatedInitiativeResult {
    data class Created(val result: AgentInitiativeResult.Created) : AgentDelegatedInitiativeResult
    data class Rejected(val reason: String) : AgentDelegatedInitiativeResult {
        init { require(reason.isNotBlank()) { "delegated initiative rejection reason must not be blank" } }
    }
}

/**
 * Controlled non-executing bridge from an exact live delegation relation into the existing child
 * Agent initiative boundary.
 *
 * The delegation relation is structural evidence only. Parent and child Agent generations plus
 * ACTIVE lifecycle are freshly validated immediately before downstream work creation. The child
 * identity is derived from the exact delegation record rather than accepted from the caller.
 *
 * This bridge performs no attempt claim, scheduling, Authority or Execution.
 */
class ControlledAgentDelegationInitiative(
    private val foundation: FoundationComposition,
    private val delegations: AgentDelegationComposition,
    private val agents: AgentComposition,
    private val lifecycle: ControlledAgentLifecycle,
    private val childInitiative: ControlledAgentInitiative
) {
    fun create(request: AgentDelegatedInitiativeRequest): AgentDelegatedInitiativeResult {
        val context = foundation.rootContext(
            operation = "createDelegatedAgentInitiative",
            component = "AgentDelegation",
            metadata = structuralMetadata(request)
        )

        val delegationSnapshot = delegations.inspect(request.delegationId)
            ?: return reject("delegation is not live", context)
        if (delegationSnapshot.generation != request.delegationGeneration) {
            return reject("delegation generation is stale", context)
        }

        val delegation = delegationSnapshot.delegation
        if (!isExactLiveActive(delegation.parent)) {
            return reject("parent agent is not exact live active", context)
        }
        if (!isExactLiveActive(delegation.child)) {
            return reject("child agent is not exact live active", context)
        }

        val childRequest = AgentInitiativeRequest(
            agentId = delegation.child.id,
            agentGeneration = delegation.child.generation,
            autonomyProposalId = request.autonomyProposalId,
            objective = request.objective,
            triggerDescription = request.triggerDescription,
            priority = request.priority,
            budget = request.budget,
            createdAt = request.createdAt
        )

        return when (val result = childInitiative.create(childRequest)) {
            is AgentInitiativeResult.Created -> {
                foundation.observability.record(
                    severity = DiagnosticSeverity.INFO,
                    code = "AGENT_DELEGATED_INITIATIVE_CREATED",
                    message = "delegated agent initiative created",
                    context = context,
                    metadata = structuralMetadata(request) + mapOf(
                        "parentAgentId" to delegation.parent.id.value,
                        "parentAgentGeneration" to delegation.parent.generation.value.toString(),
                        "childAgentId" to delegation.child.id.value,
                        "childAgentGeneration" to delegation.child.generation.value.toString(),
                        "autonomyGeneration" to result.ownership.generation.value.toString()
                    )
                )
                AgentDelegatedInitiativeResult.Created(result)
            }

            is AgentInitiativeResult.Rejected ->
                reject("child initiative rejected: ${result.reason}", context)
        }
    }

    private fun isExactLiveActive(reference: ExactAgentReference): Boolean {
        val snapshot = agents.inspect(reference.id) ?: return false
        if (snapshot.generation != reference.generation) return false
        return lifecycle.isActive(reference.id, reference.generation)
    }

    private fun reject(
        reason: String,
        context: pro.liliya.core.logging.LogContext
    ): AgentDelegatedInitiativeResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_DELEGATED_INITIATIVE_REJECTED",
            message = reason,
            context = context,
            metadata = mapOf("rejectionReason" to reason)
        )
        return AgentDelegatedInitiativeResult.Rejected(reason)
    }

    private fun structuralMetadata(request: AgentDelegatedInitiativeRequest): Map<String, String> = mapOf(
        "agentDelegationId" to request.delegationId.value,
        "agentDelegationGeneration" to request.delegationGeneration.value.toString(),
        "autonomyProposalId" to request.autonomyProposalId.value,
        "autonomyPriority" to request.priority.name,
        "autonomyMaxAttempts" to request.budget.maxAttempts.toString(),
        "createdAt" to request.createdAt.toString()
    )
}
