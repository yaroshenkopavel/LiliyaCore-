package pro.liliya.core.planning

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface PlanningProposalRegistration {
    val proposal: PlanningProposal
    val generation: PlanningGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface PlanningProposalRegistrationResult {
    data class Registered(val registration: PlanningProposalRegistration) : PlanningProposalRegistrationResult
    data class Rejected(val reason: String) : PlanningProposalRegistrationResult
}

internal class PlanningProposalStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: PlanningGeneration,
        val proposal: PlanningProposal
    )

    private val nextGeneration = AtomicLong(0)
    private val proposals = ConcurrentHashMap<PlanningProposalId, Entry>()

    fun register(
        proposal: PlanningProposal,
        context: LogContext
    ): PlanningProposalRegistrationResult {
        val entry = Entry(
            generation = PlanningGeneration(nextGeneration.incrementAndGet()),
            proposal = proposal
        )
        val previous = proposals.putIfAbsent(proposal.id, entry)
        if (previous != null) {
            val reason = "planning proposal id is already registered"
            observability.record(
                DiagnosticSeverity.WARNING,
                "PLANNING_PROPOSAL_REGISTRATION_REJECTED",
                reason,
                context,
                metadata(proposal, previous.generation) + ("rejectionReason" to reason)
            )
            return PlanningProposalRegistrationResult.Rejected(reason)
        }

        observability.record(
            DiagnosticSeverity.INFO,
            "PLANNING_PROPOSAL_REGISTERED",
            "planning proposal registered",
            context,
            metadata(proposal, entry.generation)
        )

        return PlanningProposalRegistrationResult.Registered(
            object : PlanningProposalRegistration {
                override val proposal: PlanningProposal = proposal
                override val generation: PlanningGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = proposals.remove(proposal.id, entry)
                    observability.record(
                        if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        if (removed) "PLANNING_PROPOSAL_REMOVED" else "PLANNING_PROPOSAL_REMOVAL_REJECTED",
                        if (removed) "planning proposal removed" else "planning proposal registration is no longer current",
                        context,
                        metadata(proposal, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: PlanningProposalId): PlanningProposal? = proposals[id]?.proposal

    fun inspect(id: PlanningProposalId): PlanningProposalSnapshot? = proposals[id]?.let { entry ->
        PlanningProposalSnapshot(entry.proposal, entry.generation)
    }

    fun contains(id: PlanningProposalId): Boolean = proposals.containsKey(id)

    fun snapshot(): List<PlanningProposal> = snapshotEntries().map { it.proposal }

    fun snapshotEntries(): List<PlanningProposalSnapshot> = proposals.values
        .map { PlanningProposalSnapshot(it.proposal, it.generation) }
        .sortedWith(compareBy<PlanningProposalSnapshot> { it.proposal.createdAt }.thenBy { it.proposal.id.value })

    private fun metadata(
        proposal: PlanningProposal,
        generation: PlanningGeneration
    ): Map<String, String> = buildMap {
        put("planningProposalId", proposal.id.value)
        put("planningGeneration", generation.value.toString())
        put("planningSourceId", proposal.origin.sourceId.value)
        proposal.origin.sourceReference?.let { put("planningSourceReference", it.value) }
        put("planningStepCount", proposal.steps.size.toString())
        put("createdAt", proposal.createdAt.toString())
    }
}
