package pro.liliya.core.agent

import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

data class AgentDelegationPreflightEvidence(
    val delegationId: AgentDelegationId,
    val delegationGeneration: AgentDelegationGeneration,
    val parent: ExactAgentReference,
    val child: ExactAgentReference
)

sealed interface AgentDelegationPreflightResult {
    data class Ready(val evidence: AgentDelegationPreflightEvidence) : AgentDelegationPreflightResult
    data class Rejected(val reason: String) : AgentDelegationPreflightResult {
        init { require(reason.isNotBlank()) { "delegation preflight rejection reason must not be blank" } }
    }
}

/**
 * Fresh evidence-only preflight for an exact Agent delegation relation.
 *
 * The result is not permission and cannot create work. Every downstream bridge must revalidate the
 * exact delegation generation and both exact ACTIVE Agent endpoints again immediately before its
 * own mutable boundary.
 */
class ControlledAgentDelegationPreflight(
    private val foundation: FoundationComposition,
    private val delegations: AgentDelegationComposition,
    private val agents: AgentComposition,
    private val lifecycle: ControlledAgentLifecycle
) {
    fun preflight(
        delegationId: AgentDelegationId,
        delegationGeneration: AgentDelegationGeneration
    ): AgentDelegationPreflightResult {
        val context = foundation.rootContext(
            operation = "preflightAgentDelegation",
            component = "AgentDelegation",
            metadata = mapOf(
                "agentDelegationId" to delegationId.value,
                "agentDelegationGeneration" to delegationGeneration.value.toString()
            )
        )

        val snapshot = delegations.inspect(delegationId)
            ?: return reject("delegation is not live", context)
        if (snapshot.generation != delegationGeneration) {
            return reject("delegation generation is stale", context)
        }

        val delegation = snapshot.delegation
        if (!isExactLiveActive(delegation.parent)) {
            return reject("parent agent is not exact live active", context)
        }
        if (!isExactLiveActive(delegation.child)) {
            return reject("child agent is not exact live active", context)
        }

        val evidence = AgentDelegationPreflightEvidence(
            delegationId = delegation.id,
            delegationGeneration = snapshot.generation,
            parent = delegation.parent,
            child = delegation.child
        )

        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_DELEGATION_PREFLIGHT_READY",
            message = "agent delegation preflight ready",
            context = context,
            metadata = mapOf(
                "agentDelegationId" to evidence.delegationId.value,
                "agentDelegationGeneration" to evidence.delegationGeneration.value.toString(),
                "parentAgentId" to evidence.parent.id.value,
                "parentAgentGeneration" to evidence.parent.generation.value.toString(),
                "childAgentId" to evidence.child.id.value,
                "childAgentGeneration" to evidence.child.generation.value.toString()
            )
        )
        return AgentDelegationPreflightResult.Ready(evidence)
    }

    private fun isExactLiveActive(reference: ExactAgentReference): Boolean {
        val snapshot = agents.inspect(reference.id) ?: return false
        if (snapshot.generation != reference.generation) return false
        return lifecycle.isActive(reference.id, reference.generation)
    }

    private fun reject(
        reason: String,
        context: pro.liliya.core.logging.LogContext
    ): AgentDelegationPreflightResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_DELEGATION_PREFLIGHT_REJECTED",
            message = reason,
            context = context,
            metadata = mapOf("rejectionReason" to reason)
        )
        return AgentDelegationPreflightResult.Rejected(reason)
    }
}
