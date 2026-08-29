package pro.liliya.core.agent

import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.LogContext

data class AgentCoordinationPreflightRequest(
    val coordinationId: AgentCoordinationId,
    val coordinationGeneration: AgentCoordinationGeneration
)

class AgentCoordinationReadyEvidence internal constructor(
    val coordinationId: AgentCoordinationId,
    val coordinationGeneration: AgentCoordinationGeneration,
    participants: List<ExactAgentReference>
) {
    val participants: List<ExactAgentReference> = participants.toList()

    override fun toString(): String =
        "AgentCoordinationReadyEvidence(" +
            "coordinationId=$coordinationId, generation=$coordinationGeneration, " +
            "participants=$participants)"
}

sealed interface AgentCoordinationPreflightResult {
    data class Ready(val evidence: AgentCoordinationReadyEvidence) : AgentCoordinationPreflightResult

    data class Rejected(val reason: String) : AgentCoordinationPreflightResult {
        init {
            require(reason.isNotBlank()) { "agent coordination preflight rejection reason must not be blank" }
        }
    }
}

/**
 * Fresh fail-closed validation for one exact structural Agent coordination.
 *
 * Structural coordination proves participant provenance only. This preflight revalidates the exact
 * coordination generation and every exact participant Agent generation plus ACTIVE lifecycle state.
 * It returns structural readiness evidence only and performs no delegation creation, Autonomy write,
 * scheduling, fan-out, voting/consensus, Authority or Execution.
 */
class ControlledAgentCoordinationPreflight(
    private val foundation: FoundationComposition,
    private val coordinations: AgentCoordinationComposition,
    private val agents: AgentComposition,
    private val lifecycle: ControlledAgentLifecycle
) {
    fun check(request: AgentCoordinationPreflightRequest): AgentCoordinationPreflightResult {
        val context = foundation.rootContext(
            operation = "preflightAgentCoordination",
            component = "AgentCoordination",
            metadata = requestMetadata(request)
        )

        val coordinationSnapshot = coordinations.inspect(request.coordinationId)
            ?: return reject("agent coordination is not live", context)
        if (coordinationSnapshot.generation != request.coordinationGeneration) {
            return reject("agent coordination generation is stale", context)
        }

        val coordination = coordinationSnapshot.coordination
        coordination.participants.forEach { participant ->
            if (!isExactLiveAgent(participant)) {
                return reject("coordination participant generation is not live", context)
            }
            if (!isActive(participant)) {
                return reject("coordination participant lifecycle is not ACTIVE", context)
            }
        }

        val evidence = AgentCoordinationReadyEvidence(
            coordinationId = coordination.id,
            coordinationGeneration = coordinationSnapshot.generation,
            participants = coordination.participants
        )
        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_COORDINATION_PREFLIGHT_READY",
            message = "agent coordination preflight ready",
            context = context,
            metadata = evidenceMetadata(evidence)
        )
        return AgentCoordinationPreflightResult.Ready(evidence)
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
    ): AgentCoordinationPreflightResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_COORDINATION_PREFLIGHT_REJECTED",
            message = reason,
            context = context,
            metadata = mapOf("rejectionReason" to reason)
        )
        return AgentCoordinationPreflightResult.Rejected(reason)
    }

    private fun requestMetadata(request: AgentCoordinationPreflightRequest): Map<String, String> = mapOf(
        "agentCoordinationId" to request.coordinationId.value,
        "agentCoordinationGeneration" to request.coordinationGeneration.value.toString()
    )

    private fun evidenceMetadata(evidence: AgentCoordinationReadyEvidence): Map<String, String> = buildMap {
        put("agentCoordinationId", evidence.coordinationId.value)
        put("agentCoordinationGeneration", evidence.coordinationGeneration.value.toString())
        put("participantCount", evidence.participants.size.toString())
        evidence.participants.forEachIndexed { index, participant ->
            put("participant${index}AgentId", participant.id.value)
            put("participant${index}AgentGeneration", participant.generation.value.toString())
        }
    }
}
