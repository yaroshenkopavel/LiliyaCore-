package pro.liliya.core.agent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface AgentDelegationRegistration {
    val delegation: AgentDelegationRecord
    val generation: AgentDelegationGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface AgentDelegationRegistrationResult {
    data class Registered(val registration: AgentDelegationRegistration) : AgentDelegationRegistrationResult
    data class Rejected(val reason: String) : AgentDelegationRegistrationResult
}

internal class AgentDelegationStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: AgentDelegationGeneration,
        val delegation: AgentDelegationRecord
    )

    private val nextGeneration = AtomicLong(0)
    private val delegations = ConcurrentHashMap<AgentDelegationId, Entry>()

    fun register(
        delegation: AgentDelegationRecord,
        context: LogContext
    ): AgentDelegationRegistrationResult {
        val entry = Entry(
            generation = AgentDelegationGeneration(nextGeneration.incrementAndGet()),
            delegation = delegation
        )
        val existing = delegations.putIfAbsent(delegation.id, entry)
        if (existing != null) {
            val reason = "agent delegation id is already registered"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "AGENT_DELEGATION_REGISTRATION_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(delegation, existing.generation) + ("rejectionReason" to reason)
            )
            return AgentDelegationRegistrationResult.Rejected(reason)
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_DELEGATION_REGISTERED",
            message = "agent delegation registered",
            context = context,
            metadata = metadata(delegation, entry.generation)
        )

        return AgentDelegationRegistrationResult.Registered(
            object : AgentDelegationRegistration {
                override val delegation: AgentDelegationRecord = delegation
                override val generation: AgentDelegationGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = delegations.remove(delegation.id, entry)
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) {
                            "AGENT_DELEGATION_REMOVED"
                        } else {
                            "AGENT_DELEGATION_REMOVAL_REJECTED"
                        },
                        message = if (removed) {
                            "agent delegation removed"
                        } else {
                            "agent delegation registration is no longer current"
                        },
                        context = context,
                        metadata = metadata(delegation, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: AgentDelegationId): AgentDelegationRecord? = delegations[id]?.delegation

    fun inspect(id: AgentDelegationId): AgentDelegationSnapshot? = delegations[id]?.let {
        AgentDelegationSnapshot(it.delegation, it.generation)
    }

    fun contains(id: AgentDelegationId): Boolean = delegations.containsKey(id)

    fun snapshot(): List<AgentDelegationRecord> = delegations.values
        .map { it.delegation }
        .sortedWith(compareBy<AgentDelegationRecord>({ it.createdAt }, { it.id.value }))
        .toList()

    fun snapshotEntries(): List<AgentDelegationSnapshot> = delegations.values
        .map { AgentDelegationSnapshot(it.delegation, it.generation) }
        .sortedWith(compareBy<AgentDelegationSnapshot>({ it.delegation.createdAt }, { it.delegation.id.value }))
        .toList()

    private fun metadata(
        delegation: AgentDelegationRecord,
        generation: AgentDelegationGeneration
    ): Map<String, String> = mapOf(
        "agentDelegationId" to delegation.id.value,
        "agentDelegationGeneration" to generation.value.toString(),
        "parentAgentId" to delegation.parent.id.value,
        "parentAgentGeneration" to delegation.parent.generation.value.toString(),
        "childAgentId" to delegation.child.id.value,
        "childAgentGeneration" to delegation.child.generation.value.toString(),
        "createdAt" to delegation.createdAt.toString()
    )
}
