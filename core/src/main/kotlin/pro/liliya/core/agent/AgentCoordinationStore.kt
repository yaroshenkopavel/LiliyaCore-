package pro.liliya.core.agent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface AgentCoordinationRegistration {
    val coordination: AgentCoordinationRecord
    val generation: AgentCoordinationGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface AgentCoordinationRegistrationResult {
    data class Registered(val registration: AgentCoordinationRegistration) : AgentCoordinationRegistrationResult
    data class Rejected(val reason: String) : AgentCoordinationRegistrationResult
}

internal class AgentCoordinationStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: AgentCoordinationGeneration,
        val coordination: AgentCoordinationRecord
    )

    private val nextGeneration = AtomicLong(0)
    private val coordinations = ConcurrentHashMap<AgentCoordinationId, Entry>()

    fun register(
        coordination: AgentCoordinationRecord,
        context: LogContext
    ): AgentCoordinationRegistrationResult {
        val entry = Entry(
            generation = AgentCoordinationGeneration(nextGeneration.incrementAndGet()),
            coordination = coordination
        )
        val existing = coordinations.putIfAbsent(coordination.id, entry)
        if (existing != null) {
            val reason = "agent coordination id is already registered"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "AGENT_COORDINATION_REGISTRATION_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(coordination, existing.generation) + ("rejectionReason" to reason)
            )
            return AgentCoordinationRegistrationResult.Rejected(reason)
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_COORDINATION_REGISTERED",
            message = "agent coordination registered",
            context = context,
            metadata = metadata(coordination, entry.generation)
        )

        return AgentCoordinationRegistrationResult.Registered(
            object : AgentCoordinationRegistration {
                override val coordination: AgentCoordinationRecord = coordination
                override val generation: AgentCoordinationGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = coordinations.remove(coordination.id, entry)
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) "AGENT_COORDINATION_REMOVED" else "AGENT_COORDINATION_REMOVAL_REJECTED",
                        message = if (removed) {
                            "agent coordination removed"
                        } else {
                            "agent coordination registration is no longer current"
                        },
                        context = context,
                        metadata = metadata(coordination, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: AgentCoordinationId): AgentCoordinationRecord? = coordinations[id]?.coordination

    fun inspect(id: AgentCoordinationId): AgentCoordinationSnapshot? = coordinations[id]?.let {
        AgentCoordinationSnapshot(it.coordination, it.generation)
    }

    fun contains(id: AgentCoordinationId): Boolean = coordinations.containsKey(id)

    fun snapshot(): List<AgentCoordinationRecord> = coordinations.values
        .map { it.coordination }
        .sortedWith(compareBy<AgentCoordinationRecord>({ it.createdAt }, { it.id.value }))
        .toList()

    fun snapshotEntries(): List<AgentCoordinationSnapshot> = coordinations.values
        .map { AgentCoordinationSnapshot(it.coordination, it.generation) }
        .sortedWith(compareBy<AgentCoordinationSnapshot>({ it.coordination.createdAt }, { it.coordination.id.value }))
        .toList()

    private fun metadata(
        coordination: AgentCoordinationRecord,
        generation: AgentCoordinationGeneration
    ): Map<String, String> = buildMap {
        put("agentCoordinationId", coordination.id.value)
        put("agentCoordinationGeneration", generation.value.toString())
        put("participantCount", coordination.participants.size.toString())
        coordination.participants.forEachIndexed { index, participant ->
            put("participant${index}AgentId", participant.id.value)
            put("participant${index}AgentGeneration", participant.generation.value.toString())
        }
        put("createdAt", coordination.createdAt.toString())
    }
}
