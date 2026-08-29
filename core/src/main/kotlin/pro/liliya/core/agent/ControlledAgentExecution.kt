package pro.liliya.core.agent

import pro.liliya.core.autonomy.AutonomyComposition
import pro.liliya.core.autonomy.AutonomyDeliberationComposition
import pro.liliya.core.autonomy.ControlledAutonomyExecution
import pro.liliya.core.autonomy.ControlledAutonomyExecutionRequest
import pro.liliya.core.autonomy.ControlledAutonomyExecutionResult
import pro.liliya.core.autonomy.AutonomyOrigin

sealed interface ControlledAgentExecutionResult {
    data object Succeeded : ControlledAgentExecutionResult

    data class Rejected(val reason: String) : ControlledAgentExecutionResult {
        init { require(reason.isNotBlank()) { "agent execution rejection reason must not be blank" } }
    }

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : ControlledAgentExecutionResult {
        init { require(reason.isNotBlank()) { "agent execution failure reason must not be blank" } }
    }
}

/**
 * Final Agent liveness/provenance guard immediately before the already frozen Controlled Autonomy
 * execution boundary.
 *
 * Agent identity is derived from the exact live Autonomy provenance referenced by the exact live
 * deliberation request. The caller cannot provide an unrelated Agent alongside another Autonomy
 * chain. This guard grants no permission; the delegated ControlledAutonomyExecution still performs
 * the complete Autonomy/cognitive/orchestration revalidation and fresh Authority/Execution checks.
 */
class ControlledAgentExecution private constructor(
    private val agents: AgentComposition,
    private val autonomy: AutonomyComposition,
    private val deliberation: AutonomyDeliberationComposition,
    private val delegate: (ControlledAutonomyExecutionRequest) -> ControlledAutonomyExecutionResult
) {
    constructor(
        agents: AgentComposition,
        autonomy: AutonomyComposition,
        deliberation: AutonomyDeliberationComposition,
        controlledAutonomy: ControlledAutonomyExecution
    ) : this(agents, autonomy, deliberation, controlledAutonomy::execute)

    internal constructor(
        agents: AgentComposition,
        autonomy: AutonomyComposition,
        deliberation: AutonomyDeliberationComposition,
        executor: AgentAutonomyExecutionDelegate
    ) : this(agents, autonomy, deliberation, executor::execute)

    fun execute(request: ControlledAutonomyExecutionRequest): ControlledAgentExecutionResult {
        val deliberationSnapshot = deliberation.inspect(request.deliberationRequestId)
            ?: return reject("autonomy deliberation request is not live")
        if (deliberationSnapshot.generation != request.deliberationGeneration) {
            return reject("autonomy deliberation request generation is stale")
        }

        val autonomyReference = deliberationSnapshot.request.autonomy
        val autonomySnapshot = autonomy.inspect(autonomyReference.proposalId)
            ?: return reject("agent autonomy proposal is not live")
        if (autonomySnapshot.generation != autonomyReference.proposalGeneration) {
            return reject("agent autonomy proposal generation is stale")
        }

        val origin = autonomySnapshot.proposal.origin
        if (origin !is AutonomyOrigin.Declared || origin.sourceId.value != "agent") {
            return reject("autonomy proposal is not agent-originated")
        }

        val exactAgent = parseAgentReference(origin.sourceReference?.value)
            ?: return reject("agent autonomy provenance is invalid")
        val agentSnapshot = agents.inspect(exactAgent.id)
            ?: return reject("agent is not live")
        if (agentSnapshot.generation != exactAgent.generation) {
            return reject("agent generation is stale")
        }

        return when (val result = delegate(request)) {
            ControlledAutonomyExecutionResult.Succeeded -> ControlledAgentExecutionResult.Succeeded
            is ControlledAutonomyExecutionResult.Rejected ->
                ControlledAgentExecutionResult.Rejected(result.reason)
            is ControlledAutonomyExecutionResult.Failed ->
                ControlledAgentExecutionResult.Failed(result.reason, result.throwable)
        }
    }

    private fun parseAgentReference(value: String?): ExactAgentReference? {
        if (value == null || !value.startsWith("agent:")) return null
        val body = value.removePrefix("agent:")
        val separator = body.lastIndexOf('@')
        if (separator <= 0 || separator == body.lastIndex) return null
        val idValue = body.substring(0, separator)
        val generationValue = body.substring(separator + 1).toLongOrNull() ?: return null
        return runCatching {
            ExactAgentReference(AgentId(idValue), AgentGeneration(generationValue))
        }.getOrNull()
    }

    private fun reject(reason: String): ControlledAgentExecutionResult.Rejected =
        ControlledAgentExecutionResult.Rejected(reason)

    private data class ExactAgentReference(
        val id: AgentId,
        val generation: AgentGeneration
    )
}

internal fun interface AgentAutonomyExecutionDelegate {
    fun execute(request: ControlledAutonomyExecutionRequest): ControlledAutonomyExecutionResult
}
