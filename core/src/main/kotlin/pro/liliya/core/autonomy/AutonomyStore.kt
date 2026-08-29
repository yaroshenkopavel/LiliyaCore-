package pro.liliya.core.autonomy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface AutonomyRegistration {
    val proposal: AutonomyProposal
    val generation: AutonomyGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface AutonomyRegistrationResult {
    data class Registered(val registration: AutonomyRegistration) : AutonomyRegistrationResult
    data class Rejected(val reason: String) : AutonomyRegistrationResult
}

internal class AutonomyStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: AutonomyGeneration,
        val proposal: AutonomyProposal
    )

    private val nextGeneration = AtomicLong(0)
    private val proposals = ConcurrentHashMap<AutonomyProposalId, Entry>()

    fun register(
        proposal: AutonomyProposal,
        context: LogContext
    ): AutonomyRegistrationResult {
        val entry = Entry(
            generation = AutonomyGeneration(nextGeneration.incrementAndGet()),
            proposal = proposal
        )
        val previous = proposals.putIfAbsent(proposal.id, entry)
        if (previous != null) {
            val reason = "autonomy proposal id is already registered"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "AUTONOMY_PROPOSAL_REGISTRATION_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(proposal, previous.generation) + ("rejectionReason" to reason)
            )
            return AutonomyRegistrationResult.Rejected(reason)
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AUTONOMY_PROPOSAL_REGISTERED",
            message = "autonomy proposal registered",
            context = context,
            metadata = metadata(proposal, entry.generation)
        )

        return AutonomyRegistrationResult.Registered(
            object : AutonomyRegistration {
                override val proposal: AutonomyProposal = proposal
                override val generation: AutonomyGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = proposals.remove(proposal.id, entry)
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) "AUTONOMY_PROPOSAL_REMOVED" else "AUTONOMY_PROPOSAL_REMOVAL_REJECTED",
                        message = if (removed) {
                            "autonomy proposal removed"
                        } else {
                            "autonomy proposal registration is no longer current"
                        },
                        context = context,
                        metadata = metadata(proposal, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: AutonomyProposalId): AutonomyProposal? = proposals[id]?.proposal

    fun inspect(id: AutonomyProposalId): AutonomySnapshot? = proposals[id]?.let { entry ->
        AutonomySnapshot(entry.proposal, entry.generation)
    }

    fun contains(id: AutonomyProposalId): Boolean = proposals.containsKey(id)

    fun snapshot(): List<AutonomyProposal> =
        proposals.values
            .map { it.proposal }
            .sortedWith(compareBy<AutonomyProposal>({ it.createdAt }, { it.id.value }))
            .toList()

    fun snapshotEntries(): List<AutonomySnapshot> =
        proposals.values
            .map { AutonomySnapshot(it.proposal, it.generation) }
            .sortedWith(compareBy<AutonomySnapshot>({ it.proposal.createdAt }, { it.proposal.id.value }))
            .toList()

    private fun metadata(
        proposal: AutonomyProposal,
        generation: AutonomyGeneration
    ): Map<String, String> = buildMap {
        put("autonomyProposalId", proposal.id.value)
        put("autonomyGeneration", generation.value.toString())
        put("autonomyPriority", proposal.priority.name)
        put("autonomyMaxAttempts", proposal.budget.maxAttempts.toString())
        put("createdAt", proposal.createdAt.toString())
        when (val origin = proposal.origin) {
            is AutonomyOrigin.Reflection -> {
                put("autonomyOriginType", "reflection")
                put("reflectionRecordId", origin.recordId.value)
                put("reflectionGeneration", origin.generation.value.toString())
            }
            is AutonomyOrigin.Declared -> {
                put("autonomyOriginType", "declared")
                put("autonomySourceId", origin.sourceId.value)
                origin.sourceReference?.let { put("autonomySourceReference", it.value) }
            }
        }
    }
}
