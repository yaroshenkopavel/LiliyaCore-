package pro.liliya.core.agent

import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.LogContext

data class AgentDelegationPreflightRequest(
    val delegationId: AgentDelegationId,
    val delegationGeneration: AgentDelegationGeneration
)

class AgentDelegationReadyEvidence internal constructor(
    val delegationId: AgentDelegationId,
    val delegationGeneration: AgentDelegationGeneration,
    val parent: ExactAgentReference,
    val child: ExactAgentReference
) {
    override fun toString(): String =
        "AgentDelegationReadyEvidence(" +
            "delegationId=$delegationId, generation=$delegationGeneration, " +
            "parent=$parent, child=$child)"
}

sealed interface AgentDelegationPreflightResult {
    data class Ready(val evidence: AgentDelegationReadyEvidence) : AgentDelegationPreflightResult

    data class Rejected(val reason: String) : AgentDelegationPreflightResult {
        init {
            require(reason.isNotBlank()) { "agent delegation preflight rejection reason must not be blank" }
        }
    }
}

/**
 * Fresh fail-closed validation for one exact structural Agent delegation.
 *
 * A structural delegation record proves only recorded provenance. This preflight revalidates the
 * exact delegation generation, both exact Agent generations and ACTIVE lifecycle state for both
 * endpoints. It creates readiness evidence only; it performs no initiative creation, scheduling,
 * Authority, Execution or tool access.
 */
class ControlledAgentDelegationPreflight(
    private val foundation: FoundationComposition,
    private val delegations: AgentDelegationComposition,
    private val agents: AgentComposition,
    private val lifecycle: ControlledAgentLifecycle
) {
    fun check(request: AgentDelegationPreflightRequest): AgentDelegationPreflightResult {
        val context = foundation.rootContext(
            operation = "preflightAgentDelegation",
            component = "AgentDelegation",
            metadata = requestMetadata(request)
        )

        val delegationSnapshot = delegations.inspect(request.delegationId)
            ?: return reject("agent delegation is not live", context)
        if (delegationSnapshot.generation != request.delegationGeneration) {
            return reject("agent delegation generation is stale", context)
        }

        val delegation = delegationSnapshot.delegation
        if (!isExactLiveAgent(delegation.parent)) {
            return reject("parent agent generation is not live", context)
        }
        if (!isActive(delegation.parent)) {
            return reject("parent agent lifecycle is not ACTIVE", context)
        }
        if (!isExactLiveAgent(delegation.child)) {
            return reject("child agent generation is not live", context)
        }
        if (!isActive(delegation.child)) {
            return reject("child agent lifecycle is not ACTIVE", context)
        }

        val evidence = AgentDelegationReadyEvidence(
            delegationId = delegation.id,
            delegationGeneration = delegationSnapshot.generation,
            parent = delegation.parent,
            child = delegation.child
        )
        val metadata = evidenceMetadata(evidence)
        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_DELEGATION_PREFLIGHT_READY",
            message = "agent delegation preflight ready",
            context = context,
            metadata = metadata
        )
        return AgentDelegationPreflightResult.Ready(evidence)
    }

    private fun isExactLiveAgent(reference: ExactAgentReference): Boolean {
        val snapshot = agents.inspect(reference.id) ?: return false
        return snapshot.generation == reference.generation
    }

    private fun isActive(reference: ExactAgentReference): Boolean =
        lifecycle.inspect(reference.id, reference.generation)?.status == AgentLifecycleStatus.ACTIVE

    private fun reject(
        reason: String,
        context: LogContext
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

    private fun requestMetadata(request: AgentDelegationPreflightRequest): Map<String, String> = mapOf(
        "agentDelegationId" to request.delegationId.value,
        "agentDelegationGeneration" to request.delegationGeneration.value.toString()
    )

    private fun evidenceMetadata(evidence: AgentDelegationReadyEvidence): Map<String, String> = mapOf(
        "agentDelegationId" to evidence.delegationId.value,
        "agentDelegationGeneration" to evidence.delegationGeneration.value.toString(),
        "parentAgentId" to evidence.parent.id.value,
        "parentAgentGeneration" to evidence.parent.generation.value.toString(),
        "childAgentId" to evidence.child.id.value,
        "childAgentGeneration" to evidence.child.generation.value.toString()
    )
}
