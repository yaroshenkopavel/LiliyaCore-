package pro.liliya.core.agent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface AgentRegistration {
    val agent: AgentRecord
    val generation: AgentGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface AgentRegistrationResult {
    data class Registered(val registration: AgentRegistration) : AgentRegistrationResult
    data class Rejected(val reason: String) : AgentRegistrationResult
}

internal class AgentStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: AgentGeneration,
        val agent: AgentRecord
    )

    private val nextGeneration = AtomicLong(0)
    private val agents = ConcurrentHashMap<AgentId, Entry>()

    fun register(agent: AgentRecord, context: LogContext): AgentRegistrationResult {
        val entry = Entry(
            generation = AgentGeneration(nextGeneration.incrementAndGet()),
            agent = agent
        )
        val existing = agents.putIfAbsent(agent.id, entry)
        if (existing != null) {
            val reason = "agent id is already registered"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "AGENT_REGISTRATION_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(agent, existing.generation) + ("rejectionReason" to reason)
            )
            return AgentRegistrationResult.Rejected(reason)
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_REGISTERED",
            message = "agent registered",
            context = context,
            metadata = metadata(agent, entry.generation)
        )

        return AgentRegistrationResult.Registered(
            object : AgentRegistration {
                override val agent: AgentRecord = agent
                override val generation: AgentGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = agents.remove(agent.id, entry)
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) "AGENT_REMOVED" else "AGENT_REMOVAL_REJECTED",
                        message = if (removed) {
                            "agent removed"
                        } else {
                            "agent registration is no longer current"
                        },
                        context = context,
                        metadata = metadata(agent, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: AgentId): AgentRecord? = agents[id]?.agent

    fun inspect(id: AgentId): AgentSnapshot? = agents[id]?.let {
        AgentSnapshot(it.agent, it.generation)
    }

    fun contains(id: AgentId): Boolean = agents.containsKey(id)

    fun snapshot(): List<AgentRecord> = agents.values
        .map { it.agent }
        .sortedWith(compareBy<AgentRecord>({ it.createdAt }, { it.id.value }))
        .toList()

    fun snapshotEntries(): List<AgentSnapshot> = agents.values
        .map { AgentSnapshot(it.agent, it.generation) }
        .sortedWith(compareBy<AgentSnapshot>({ it.agent.createdAt }, { it.agent.id.value }))
        .toList()

    private fun metadata(agent: AgentRecord, generation: AgentGeneration): Map<String, String> = buildMap {
        put("agentId", agent.id.value)
        put("agentGeneration", generation.value.toString())
        put("agentOriginType", when (agent.origin) {
            is AgentOrigin.Declared -> "declared"
            is AgentOrigin.Autonomy -> "autonomy"
        })
        when (val origin = agent.origin) {
            is AgentOrigin.Declared -> {
                put("agentSourceId", origin.sourceId.value)
                origin.sourceReference?.let { put("agentSourceReference", it.value) }
            }
            is AgentOrigin.Autonomy -> {
                put("autonomyProposalId", origin.proposalId.value)
                put("autonomyGeneration", origin.generation.value.toString())
            }
        }
        put("createdAt", agent.createdAt.toString())
    }
}
