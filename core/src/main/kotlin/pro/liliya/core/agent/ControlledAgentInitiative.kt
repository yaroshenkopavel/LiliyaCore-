package pro.liliya.core.agent

import java.time.Instant
import pro.liliya.core.autonomy.AutonomyBudget
import pro.liliya.core.autonomy.AutonomyComposition
import pro.liliya.core.autonomy.AutonomyInstallResult
import pro.liliya.core.autonomy.AutonomyOrigin
import pro.liliya.core.autonomy.AutonomyOwnership
import pro.liliya.core.autonomy.AutonomyPriority
import pro.liliya.core.autonomy.AutonomyProposal
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.autonomy.AutonomySourceId
import pro.liliya.core.autonomy.AutonomySourceReference
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

data class AgentInitiativeRequest(
    val agentId: AgentId,
    val agentGeneration: AgentGeneration,
    val autonomyProposalId: AutonomyProposalId,
    val objective: String,
    val triggerDescription: String,
    val priority: AutonomyPriority,
    val budget: AutonomyBudget,
    val createdAt: Instant
) {
    init {
        require(objective.isNotBlank()) { "agent initiative objective must not be blank" }
        require(triggerDescription.isNotBlank()) { "agent initiative trigger description must not be blank" }
    }
}

sealed interface AgentInitiativeResult {
    data class Created(val ownership: AutonomyOwnership) : AgentInitiativeResult
    data class Rejected(val reason: String) : AgentInitiativeResult {
        init { require(reason.isNotBlank()) { "agent initiative rejection reason must not be blank" } }
    }
}

/**
 * Controlled non-executing bridge from an exact live ACTIVE Agent registration into ordinary
 * bounded Autonomy data. Agent identity/role/lifecycle never grants permission and this bridge
 * performs no attempt claim, scheduling, deliberation, Authority or Execution.
 */
class ControlledAgentInitiative(
    private val foundation: FoundationComposition,
    private val agents: AgentComposition,
    private val lifecycle: ControlledAgentLifecycle,
    private val autonomy: AutonomyComposition
) {
    fun create(request: AgentInitiativeRequest): AgentInitiativeResult {
        val context = foundation.rootContext(
            operation = "createAgentInitiative",
            component = "Agent",
            metadata = structuralMetadata(request)
        )

        val agentSnapshot = agents.inspect(request.agentId)
            ?: return reject("agent is not live", context)
        if (agentSnapshot.generation != request.agentGeneration) {
            return reject("agent generation is stale", context)
        }
        if (!lifecycle.isActive(request.agentId, request.agentGeneration)) {
            return reject("agent lifecycle is not active", context)
        }

        val proposal = AutonomyProposal(
            id = request.autonomyProposalId,
            origin = AutonomyOrigin.Declared(
                sourceId = AGENT_AUTONOMY_SOURCE,
                sourceReference = AutonomySourceReference(
                    "agent:${request.agentId.value}@${request.agentGeneration.value}"
                )
            ),
            objective = request.objective,
            triggerDescription = request.triggerDescription,
            priority = request.priority,
            budget = request.budget,
            createdAt = request.createdAt
        )

        return when (val installed = autonomy.install(proposal)) {
            is AutonomyInstallResult.Installed -> {
                foundation.observability.record(
                    severity = DiagnosticSeverity.INFO,
                    code = "AGENT_INITIATIVE_CREATED",
                    message = "agent initiative created as autonomy proposal",
                    context = context,
                    metadata = structuralMetadata(request) + mapOf(
                        "autonomyGeneration" to installed.ownership.generation.value.toString()
                    )
                )
                AgentInitiativeResult.Created(installed.ownership)
            }

            is AutonomyInstallResult.Rejected -> reject(
                "autonomy proposal rejected: ${installed.reason}",
                context
            )
        }
    }

    private fun reject(
        reason: String,
        context: pro.liliya.core.logging.LogContext
    ): AgentInitiativeResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_INITIATIVE_REJECTED",
            message = reason,
            context = context,
            metadata = mapOf("rejectionReason" to reason)
        )
        return AgentInitiativeResult.Rejected(reason)
    }

    private fun structuralMetadata(request: AgentInitiativeRequest): Map<String, String> = mapOf(
        "agentId" to request.agentId.value,
        "agentGeneration" to request.agentGeneration.value.toString(),
        "autonomyProposalId" to request.autonomyProposalId.value,
        "autonomyPriority" to request.priority.name,
        "autonomyMaxAttempts" to request.budget.maxAttempts.toString(),
        "createdAt" to request.createdAt.toString()
    )

    companion object {
        private val AGENT_AUTONOMY_SOURCE = AutonomySourceId("agent")
    }
}
