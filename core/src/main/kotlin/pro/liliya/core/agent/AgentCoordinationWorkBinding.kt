package pro.liliya.core.agent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

data class ExactAgentCoordinationReference(
    val id: AgentCoordinationId,
    val generation: AgentCoordinationGeneration
)

@JvmInline
value class AgentCoordinationWorkBindingGeneration(val value: Long) {
    init { require(value > 0L) { "agent coordination work binding generation must be positive" } }
    override fun toString(): String = value.toString()
}

data class AgentCoordinationWorkAssignment(
    val participant: ExactAgentReference,
    val autonomy: ExactAutonomyReference
)

class AgentCoordinationWorkBinding(
    val coordination: ExactAgentCoordinationReference,
    assignments: List<AgentCoordinationWorkAssignment>
) {
    private val suppliedAssignments = assignments.toList()

    val assignments: List<AgentCoordinationWorkAssignment>

    init {
        require(suppliedAssignments.size >= 2) {
            "agent coordination work binding requires at least two assignments"
        }
        require(suppliedAssignments.map { it.participant }.distinct().size == suppliedAssignments.size) {
            "agent coordination work participants must be exact-reference unique"
        }
        require(suppliedAssignments.map { it.participant.id }.distinct().size == suppliedAssignments.size) {
            "agent coordination work cannot contain multiple generations of the same agent id"
        }
        require(suppliedAssignments.map { it.autonomy }.distinct().size == suppliedAssignments.size) {
            "agent coordination work autonomy references must be exact-reference unique"
        }

        this.assignments = suppliedAssignments.sortedWith(
            compareBy<AgentCoordinationWorkAssignment>(
                { it.participant.id.value },
                { it.participant.generation.value },
                { it.autonomy.proposalId.value },
                { it.autonomy.generation.value }
            )
        )
    }

    override fun equals(other: Any?): Boolean =
        other is AgentCoordinationWorkBinding &&
            coordination == other.coordination &&
            assignments == other.assignments

    override fun hashCode(): Int = 31 * coordination.hashCode() + assignments.hashCode()

    override fun toString(): String =
        "AgentCoordinationWorkBinding(coordination=$coordination, assignments=$assignments)"
}

data class AgentCoordinationWorkBindingSnapshot(
    val binding: AgentCoordinationWorkBinding,
    val generation: AgentCoordinationWorkBindingGeneration
)

internal interface AgentCoordinationWorkBindingRegistration {
    val binding: AgentCoordinationWorkBinding
    val generation: AgentCoordinationWorkBindingGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface AgentCoordinationWorkBindingRegistrationResult {
    data class Registered(
        val registration: AgentCoordinationWorkBindingRegistration
    ) : AgentCoordinationWorkBindingRegistrationResult

    data class Rejected(val reason: String) : AgentCoordinationWorkBindingRegistrationResult
}

/**
 * Private exact structural binding store for one coordination generation and its participant work.
 *
 * Registration is atomic across the coordination key and exact Autonomy secondary index. Therefore
 * one exact Autonomy generation cannot be reused by multiple coordination work-sets in this store.
 * The store performs no work and grants no permission.
 */
internal class AgentCoordinationWorkBindingStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: AgentCoordinationWorkBindingGeneration,
        val binding: AgentCoordinationWorkBinding
    )

    private val lock = Any()
    private val nextGeneration = AtomicLong(0)
    private val bindings = ConcurrentHashMap<ExactAgentCoordinationReference, Entry>()
    private val autonomyOwners = ConcurrentHashMap<ExactAutonomyReference, ExactAgentCoordinationReference>()

    fun register(
        binding: AgentCoordinationWorkBinding,
        context: LogContext
    ): AgentCoordinationWorkBindingRegistrationResult = synchronized(lock) {
        if (bindings.containsKey(binding.coordination)) {
            return@synchronized reject(
                binding,
                "exact coordination generation already has a work binding",
                context
            )
        }

        if (binding.assignments.any { autonomyOwners.containsKey(it.autonomy) }) {
            return@synchronized reject(
                binding,
                "exact autonomy generation is already coordination-bound",
                context
            )
        }

        val entry = Entry(
            generation = AgentCoordinationWorkBindingGeneration(nextGeneration.incrementAndGet()),
            binding = binding
        )
        bindings[binding.coordination] = entry
        binding.assignments.forEach { assignment ->
            autonomyOwners[assignment.autonomy] = binding.coordination
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_COORDINATION_WORK_BOUND",
            message = "coordination work structurally bound",
            context = context,
            metadata = metadata(binding, entry.generation)
        )

        AgentCoordinationWorkBindingRegistrationResult.Registered(
            object : AgentCoordinationWorkBindingRegistration {
                override val binding: AgentCoordinationWorkBinding = binding
                override val generation: AgentCoordinationWorkBindingGeneration = entry.generation

                override fun remove(context: LogContext): Boolean = synchronized(lock) {
                    val removed = bindings.remove(binding.coordination, entry)
                    if (removed) {
                        binding.assignments.forEach { assignment ->
                            autonomyOwners.remove(assignment.autonomy, binding.coordination)
                        }
                    }
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) {
                            "AGENT_COORDINATION_WORK_UNBOUND"
                        } else {
                            "AGENT_COORDINATION_WORK_UNBIND_REJECTED"
                        },
                        message = if (removed) {
                            "coordination work binding removed"
                        } else {
                            "coordination work binding is no longer current"
                        },
                        context = context,
                        metadata = metadata(binding, entry.generation)
                    )
                    removed
                }
            }
        )
    }

    fun find(coordination: ExactAgentCoordinationReference): AgentCoordinationWorkBinding? =
        bindings[coordination]?.binding

    fun inspect(coordination: ExactAgentCoordinationReference): AgentCoordinationWorkBindingSnapshot? =
        bindings[coordination]?.let { AgentCoordinationWorkBindingSnapshot(it.binding, it.generation) }

    fun findByAutonomy(autonomy: ExactAutonomyReference): AgentCoordinationWorkBinding? = synchronized(lock) {
        val coordination = autonomyOwners[autonomy] ?: return@synchronized null
        bindings[coordination]?.binding
    }

    fun snapshot(): List<AgentCoordinationWorkBindingSnapshot> = bindings.values
        .map { AgentCoordinationWorkBindingSnapshot(it.binding, it.generation) }
        .sortedWith(
            compareBy<AgentCoordinationWorkBindingSnapshot>(
                { it.binding.coordination.id.value },
                { it.binding.coordination.generation.value },
                { it.generation.value }
            )
        )
        .toList()

    private fun reject(
        binding: AgentCoordinationWorkBinding,
        reason: String,
        context: LogContext
    ): AgentCoordinationWorkBindingRegistrationResult.Rejected {
        observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_COORDINATION_WORK_BINDING_REJECTED",
            message = reason,
            context = context,
            metadata = metadata(binding, null) + ("rejectionReason" to reason)
        )
        return AgentCoordinationWorkBindingRegistrationResult.Rejected(reason)
    }

    private fun metadata(
        binding: AgentCoordinationWorkBinding,
        generation: AgentCoordinationWorkBindingGeneration?
    ): Map<String, String> = buildMap {
        put("agentCoordinationId", binding.coordination.id.value)
        put("agentCoordinationGeneration", binding.coordination.generation.value.toString())
        generation?.let { put("coordinationWorkBindingGeneration", it.value.toString()) }
        put("assignmentCount", binding.assignments.size.toString())
        binding.assignments.forEachIndexed { index, assignment ->
            put("assignment${index}AgentId", assignment.participant.id.value)
            put("assignment${index}AgentGeneration", assignment.participant.generation.value.toString())
            put("assignment${index}AutonomyProposalId", assignment.autonomy.proposalId.value)
            put("assignment${index}AutonomyGeneration", assignment.autonomy.generation.value.toString())
        }
    }
}
