package pro.liliya.core.agent

import java.util.concurrent.ConcurrentHashMap
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

enum class AgentLifecycleStatus {
    ACTIVE,
    CANCELLED,
    STOPPED
}

data class AgentLifecycleSnapshot(
    val agentId: AgentId,
    val agentGeneration: AgentGeneration,
    val status: AgentLifecycleStatus
)

interface AgentLifecycleOwnership {
    val agentId: AgentId
    val agentGeneration: AgentGeneration

    fun status(): AgentLifecycleStatus?
    fun cancel(): Boolean
    fun stop(): Boolean
}

sealed interface AgentLifecycleActivationResult {
    data class Activated(val ownership: AgentLifecycleOwnership) : AgentLifecycleActivationResult
    data class Rejected(val reason: String) : AgentLifecycleActivationResult {
        init {
            require(reason.isNotBlank()) { "agent lifecycle activation rejection reason must not be blank" }
        }
    }
}

/**
 * Explicit exact-generation lifecycle state for one Agent registration.
 *
 * Lifecycle state is intentionally separate from Agent registry presence. Activation requires an
 * exact live Agent generation, while terminal state is retained for that exact generation even if
 * the Agent record is later removed. No scheduling, execution, delegation or background work is
 * performed here.
 */
class ControlledAgentLifecycle(
    private val foundation: FoundationComposition,
    private val agents: AgentComposition
) {
    private data class ExactAgentKey(
        val id: AgentId,
        val generation: AgentGeneration
    )

    private val lock = Any()
    private val states = ConcurrentHashMap<ExactAgentKey, AgentLifecycleStatus>()

    fun activate(
        agentId: AgentId,
        agentGeneration: AgentGeneration
    ): AgentLifecycleActivationResult = synchronized(lock) {
        val agentSnapshot = agents.inspect(agentId)
            ?: return@synchronized reject(agentId, agentGeneration, "agent is not live")
        if (agentSnapshot.generation != agentGeneration) {
            return@synchronized reject(agentId, agentGeneration, "agent generation is stale")
        }

        val key = ExactAgentKey(agentId, agentGeneration)
        if (states.putIfAbsent(key, AgentLifecycleStatus.ACTIVE) != null) {
            return@synchronized reject(agentId, agentGeneration, "agent lifecycle already exists")
        }

        record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_LIFECYCLE_ACTIVATED",
            message = "agent lifecycle activated",
            agentId = agentId,
            generation = agentGeneration,
            status = AgentLifecycleStatus.ACTIVE
        )

        AgentLifecycleActivationResult.Activated(
            ownership = object : AgentLifecycleOwnership {
                override val agentId: AgentId = agentId
                override val agentGeneration: AgentGeneration = agentGeneration

                override fun status(): AgentLifecycleStatus? = inspect(
                    agentId,
                    agentGeneration
                )?.status

                override fun cancel(): Boolean = transitionTerminal(
                    agentId = agentId,
                    generation = agentGeneration,
                    target = AgentLifecycleStatus.CANCELLED
                )

                override fun stop(): Boolean = transitionTerminal(
                    agentId = agentId,
                    generation = agentGeneration,
                    target = AgentLifecycleStatus.STOPPED
                )
            }
        )
    }

    fun inspect(
        agentId: AgentId,
        agentGeneration: AgentGeneration
    ): AgentLifecycleSnapshot? {
        val status = states[ExactAgentKey(agentId, agentGeneration)] ?: return null
        return AgentLifecycleSnapshot(agentId, agentGeneration, status)
    }

    fun isActive(
        agentId: AgentId,
        agentGeneration: AgentGeneration
    ): Boolean = inspect(agentId, agentGeneration)?.status == AgentLifecycleStatus.ACTIVE

    fun snapshot(): List<AgentLifecycleSnapshot> = states.entries
        .map { (key, status) -> AgentLifecycleSnapshot(key.id, key.generation, status) }
        .sortedWith(compareBy({ it.agentId.value }, { it.agentGeneration.value }))

    private fun transitionTerminal(
        agentId: AgentId,
        generation: AgentGeneration,
        target: AgentLifecycleStatus
    ): Boolean = synchronized(lock) {
        require(target != AgentLifecycleStatus.ACTIVE) { "terminal transition target must not be ACTIVE" }
        val key = ExactAgentKey(agentId, generation)
        if (states[key] != AgentLifecycleStatus.ACTIVE) {
            return@synchronized false
        }
        states[key] = target
        record(
            severity = DiagnosticSeverity.INFO,
            code = if (target == AgentLifecycleStatus.CANCELLED) {
                "AGENT_LIFECYCLE_CANCELLED"
            } else {
                "AGENT_LIFECYCLE_STOPPED"
            },
            message = if (target == AgentLifecycleStatus.CANCELLED) {
                "agent lifecycle cancelled"
            } else {
                "agent lifecycle stopped"
            },
            agentId = agentId,
            generation = generation,
            status = target
        )
        true
    }

    private fun reject(
        agentId: AgentId,
        generation: AgentGeneration,
        reason: String
    ): AgentLifecycleActivationResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_LIFECYCLE_ACTIVATION_REJECTED",
            message = reason,
            context = foundation.rootContext(
                operation = "activateAgentLifecycle",
                component = "Agent",
                metadata = structuralMetadata(agentId, generation, null)
            ),
            metadata = mapOf("rejectionReason" to reason)
        )
        return AgentLifecycleActivationResult.Rejected(reason)
    }

    private fun record(
        severity: DiagnosticSeverity,
        code: String,
        message: String,
        agentId: AgentId,
        generation: AgentGeneration,
        status: AgentLifecycleStatus
    ) {
        val metadata = structuralMetadata(agentId, generation, status)
        foundation.observability.record(
            severity = severity,
            code = code,
            message = message,
            context = foundation.rootContext(
                operation = when (status) {
                    AgentLifecycleStatus.ACTIVE -> "activateAgentLifecycle"
                    AgentLifecycleStatus.CANCELLED -> "cancelAgentLifecycle"
                    AgentLifecycleStatus.STOPPED -> "stopAgentLifecycle"
                },
                component = "Agent",
                metadata = metadata
            ),
            metadata = metadata
        )
    }

    private fun structuralMetadata(
        agentId: AgentId,
        generation: AgentGeneration,
        status: AgentLifecycleStatus?
    ): Map<String, String> = buildMap {
        put("agentId", agentId.value)
        put("agentGeneration", generation.value.toString())
        status?.let { put("agentLifecycleStatus", it.name) }
    }
}
