package pro.liliya.core.agent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.autonomy.AutonomyAttemptReference
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

@JvmInline
value class AgentCoordinationAttemptBindingGeneration(val value: Long) {
    init { require(value > 0L) { "agent coordination attempt binding generation must be positive" } }
    override fun toString(): String = value.toString()
}

data class AgentCoordinationAttemptAssignment(
    val participant: ExactAgentReference,
    val attempt: AutonomyAttemptReference
)

class AgentCoordinationAttemptBinding(
    val coordination: ExactAgentCoordinationReference,
    assignments: List<AgentCoordinationAttemptAssignment>
) {
    private val suppliedAssignments = assignments.toList()

    val assignments: List<AgentCoordinationAttemptAssignment>

    init {
        require(suppliedAssignments.size >= 2) {
            "agent coordination attempt binding requires at least two assignments"
        }
        require(suppliedAssignments.map { it.participant }.distinct().size == suppliedAssignments.size) {
            "agent coordination attempt participants must be exact-reference unique"
        }
        require(suppliedAssignments.map { it.participant.id }.distinct().size == suppliedAssignments.size) {
            "agent coordination attempt binding cannot contain multiple generations of one agent id"
        }
        require(suppliedAssignments.map { it.attempt }.distinct().size == suppliedAssignments.size) {
            "agent coordination attempt references must be exact-reference unique"
        }

        this.assignments = suppliedAssignments.sortedWith(
            compareBy<AgentCoordinationAttemptAssignment>(
                { it.participant.id.value },
                { it.participant.generation.value },
                { it.attempt.proposalId.value },
                { it.attempt.proposalGeneration.value },
                { it.attempt.attemptNumber }
            )
        )
    }

    override fun equals(other: Any?): Boolean =
        other is AgentCoordinationAttemptBinding &&
            coordination == other.coordination &&
            assignments == other.assignments

    override fun hashCode(): Int = 31 * coordination.hashCode() + assignments.hashCode()

    override fun toString(): String =
        "AgentCoordinationAttemptBinding(coordination=$coordination, assignments=$assignments)"
}

data class AgentCoordinationAttemptBindingSnapshot(
    val binding: AgentCoordinationAttemptBinding,
    val generation: AgentCoordinationAttemptBindingGeneration
)

internal interface AgentCoordinationAttemptBindingRegistration {
    val binding: AgentCoordinationAttemptBinding
    val generation: AgentCoordinationAttemptBindingGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface AgentCoordinationAttemptBindingRegistrationResult {
    data class Registered(
        val registration: AgentCoordinationAttemptBindingRegistration
    ) : AgentCoordinationAttemptBindingRegistrationResult

    data class Rejected(val reason: String) : AgentCoordinationAttemptBindingRegistrationResult
}

/**
 * Private atomic multi-index store for one exact coordination generation and its complete exact
 * participant attempt-set. One exact Autonomy attempt may belong to at most one coordination
 * attempt binding in this store. This store records provenance only and grants no permission.
 */
internal class AgentCoordinationAttemptBindingStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: AgentCoordinationAttemptBindingGeneration,
        val binding: AgentCoordinationAttemptBinding
    )

    private val lock = Any()
    private val nextGeneration = AtomicLong(0)
    private val bindings = ConcurrentHashMap<ExactAgentCoordinationReference, Entry>()
    private val attemptOwners = ConcurrentHashMap<AutonomyAttemptReference, ExactAgentCoordinationReference>()

    fun register(
        binding: AgentCoordinationAttemptBinding,
        context: LogContext
    ): AgentCoordinationAttemptBindingRegistrationResult = synchronized(lock) {
        if (bindings.containsKey(binding.coordination)) {
            return@synchronized reject(
                binding,
                "exact coordination generation already has an attempt binding",
                context
            )
        }
        if (binding.assignments.any { attemptOwners.containsKey(it.attempt) }) {
            return@synchronized reject(
                binding,
                "exact autonomy attempt is already coordination-bound",
                context
            )
        }

        val entry = Entry(
            generation = AgentCoordinationAttemptBindingGeneration(nextGeneration.incrementAndGet()),
            binding = binding
        )
        bindings[binding.coordination] = entry
        binding.assignments.forEach { assignment ->
            attemptOwners[assignment.attempt] = binding.coordination
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_COORDINATION_ATTEMPTS_BOUND",
            message = "coordination attempts structurally bound",
            context = context,
            metadata = metadata(binding, entry.generation)
        )

        AgentCoordinationAttemptBindingRegistrationResult.Registered(
            object : AgentCoordinationAttemptBindingRegistration {
                override val binding: AgentCoordinationAttemptBinding = binding
                override val generation: AgentCoordinationAttemptBindingGeneration = entry.generation

                override fun remove(context: LogContext): Boolean = synchronized(lock) {
                    val removed = bindings.remove(binding.coordination, entry)
                    if (removed) {
                        binding.assignments.forEach { assignment ->
                            attemptOwners.remove(assignment.attempt, binding.coordination)
                        }
                    }
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) {
                            "AGENT_COORDINATION_ATTEMPTS_UNBOUND"
                        } else {
                            "AGENT_COORDINATION_ATTEMPTS_UNBIND_REJECTED"
                        },
                        message = if (removed) {
                            "coordination attempt binding removed"
                        } else {
                            "coordination attempt binding is no longer current"
                        },
                        context = context,
                        metadata = metadata(binding, entry.generation)
                    )
                    removed
                }
            }
        )
    }

    fun find(coordination: ExactAgentCoordinationReference): AgentCoordinationAttemptBinding? =
        bindings[coordination]?.binding

    fun inspect(coordination: ExactAgentCoordinationReference): AgentCoordinationAttemptBindingSnapshot? =
        bindings[coordination]?.let { AgentCoordinationAttemptBindingSnapshot(it.binding, it.generation) }

    fun findByAttempt(attempt: AutonomyAttemptReference): AgentCoordinationAttemptBinding? {
        val coordination = attemptOwners[attempt] ?: return null
        return bindings[coordination]?.binding
    }

    fun snapshot(): List<AgentCoordinationAttemptBindingSnapshot> = bindings.values
        .map { AgentCoordinationAttemptBindingSnapshot(it.binding, it.generation) }
        .sortedWith(
            compareBy(
                { it.binding.coordination.id.value },
                { it.binding.coordination.generation.value }
            )
        )
        .toList()

    private fun reject(
        binding: AgentCoordinationAttemptBinding,
        reason: String,
        context: LogContext
    ): AgentCoordinationAttemptBindingRegistrationResult.Rejected {
        observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_COORDINATION_ATTEMPT_BINDING_REJECTED",
            message = reason,
            context = context,
            metadata = metadata(binding, null) + ("rejectionReason" to reason)
        )
        return AgentCoordinationAttemptBindingRegistrationResult.Rejected(reason)
    }

    private fun metadata(
        binding: AgentCoordinationAttemptBinding,
        generation: AgentCoordinationAttemptBindingGeneration?
    ): Map<String, String> = buildMap {
        put("agentCoordinationId", binding.coordination.id.value)
        put("agentCoordinationGeneration", binding.coordination.generation.value.toString())
        generation?.let { put("coordinationAttemptBindingGeneration", it.value.toString()) }
        put("assignmentCount", binding.assignments.size.toString())
        binding.assignments.forEachIndexed { index, assignment ->
            put("assignment${index}AgentId", assignment.participant.id.value)
            put("assignment${index}AgentGeneration", assignment.participant.generation.value.toString())
            put("assignment${index}AutonomyProposalId", assignment.attempt.proposalId.value)
            put("assignment${index}AutonomyGeneration", assignment.attempt.proposalGeneration.value.toString())
            put("assignment${index}AttemptNumber", assignment.attempt.attemptNumber.toString())
        }
    }
}
