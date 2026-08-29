package pro.liliya.core.agent

import pro.liliya.core.autonomy.AutonomyComposition
import pro.liliya.core.autonomy.AutonomyDeliberationAttemptResult
import pro.liliya.core.autonomy.AutonomyGeneration
import pro.liliya.core.autonomy.AutonomyOrigin
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.autonomy.ControlledAutonomyDeliberationGate
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

sealed interface AgentInitiativeAttemptResult {
    data class Claimed(
        val attempt: AutonomyDeliberationAttemptResult.Claimed
    ) : AgentInitiativeAttemptResult

    data class Rejected(val reason: String) : AgentInitiativeAttemptResult {
        init {
            require(reason.isNotBlank()) { "agent initiative attempt rejection reason must not be blank" }
        }
    }
}

/**
 * Fresh Agent liveness/lifecycle/provenance gate immediately before a bounded Autonomy attempt.
 */
class ControlledAgentInitiativeGate(
    private val foundation: FoundationComposition,
    private val agents: AgentComposition,
    private val lifecycle: ControlledAgentLifecycle,
    private val autonomy: AutonomyComposition,
    private val autonomyGate: ControlledAutonomyDeliberationGate
) {
    fun claimAttempt(
        agentId: AgentId,
        agentGeneration: AgentGeneration,
        autonomyProposalId: AutonomyProposalId,
        autonomyGeneration: AutonomyGeneration
    ): AgentInitiativeAttemptResult {
        val context = foundation.rootContext(
            operation = "claimAgentInitiativeAttempt",
            component = "Agent",
            metadata = structuralMetadata(
                agentId,
                agentGeneration,
                autonomyProposalId,
                autonomyGeneration
            )
        )

        val agentSnapshot = agents.inspect(agentId)
            ?: return reject("agent is not live", context)
        if (agentSnapshot.generation != agentGeneration) {
            return reject("agent generation is stale", context)
        }
        if (!lifecycle.isActive(agentId, agentGeneration)) {
            return reject("agent lifecycle is not active", context)
        }

        val autonomySnapshot = autonomy.inspect(autonomyProposalId)
            ?: return reject("autonomy proposal is not live", context)
        if (autonomySnapshot.generation != autonomyGeneration) {
            return reject("autonomy proposal generation is stale", context)
        }

        val expectedReference = "agent:${agentId.value}@${agentGeneration.value}"
        val origin = autonomySnapshot.proposal.origin
        if (
            origin !is AutonomyOrigin.Declared ||
            origin.sourceId.value != "agent" ||
            origin.sourceReference?.value != expectedReference
        ) {
            return reject("autonomy proposal provenance does not match exact agent", context)
        }

        return when (
            val claimed = autonomyGate.claimAttempt(
                autonomyProposalId,
                autonomyGeneration
            )
        ) {
            is AutonomyDeliberationAttemptResult.Claimed -> {
                foundation.observability.record(
                    severity = DiagnosticSeverity.INFO,
                    code = "AGENT_INITIATIVE_ATTEMPT_CLAIMED",
                    message = "agent initiative attempt claimed",
                    context = context,
                    metadata = structuralMetadata(
                        agentId,
                        agentGeneration,
                        autonomyProposalId,
                        autonomyGeneration
                    ) + mapOf(
                        "autonomyAttemptNumber" to claimed.evidence.attemptNumber.toString()
                    )
                )
                AgentInitiativeAttemptResult.Claimed(claimed)
            }

            is AutonomyDeliberationAttemptResult.Rejected ->
                reject("autonomy attempt rejected: ${claimed.reason}", context)
        }
    }

    private fun reject(
        reason: String,
        context: pro.liliya.core.logging.LogContext
    ): AgentInitiativeAttemptResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_INITIATIVE_ATTEMPT_REJECTED",
            message = reason,
            context = context,
            metadata = mapOf("rejectionReason" to reason)
        )
        return AgentInitiativeAttemptResult.Rejected(reason)
    }

    private fun structuralMetadata(
        agentId: AgentId,
        agentGeneration: AgentGeneration,
        autonomyProposalId: AutonomyProposalId,
        autonomyGeneration: AutonomyGeneration
    ): Map<String, String> = mapOf(
        "agentId" to agentId.value,
        "agentGeneration" to agentGeneration.value.toString(),
        "autonomyProposalId" to autonomyProposalId.value,
        "autonomyGeneration" to autonomyGeneration.value.toString()
    )
}
