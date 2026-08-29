package pro.liliya.core.learning

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface LearningConsolidationRegistration {
    val proposal: LearningConsolidationProposal
    val generation: LearningConsolidationGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface LearningConsolidationRegistrationResult {
    data class Registered(
        val registration: LearningConsolidationRegistration
    ) : LearningConsolidationRegistrationResult

    data class Rejected(val reason: String) : LearningConsolidationRegistrationResult
}

internal class LearningConsolidationStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: LearningConsolidationGeneration,
        val proposal: LearningConsolidationProposal
    )

    private val nextGeneration = AtomicLong(0)
    private val entries = ConcurrentHashMap<LearningConsolidationId, Entry>()

    fun register(
        proposal: LearningConsolidationProposal,
        context: LogContext
    ): LearningConsolidationRegistrationResult {
        val entry = Entry(
            generation = LearningConsolidationGeneration(nextGeneration.incrementAndGet()),
            proposal = proposal
        )
        val existing = entries.putIfAbsent(proposal.id, entry)
        if (existing != null) {
            val reason = "learning consolidation ${proposal.id} is already registered"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "LEARNING_CONSOLIDATION_REGISTRATION_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(proposal, entry.generation) + ("rejectionReason" to reason)
            )
            return LearningConsolidationRegistrationResult.Rejected(reason)
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "LEARNING_CONSOLIDATION_REGISTERED",
            message = "learning consolidation proposal registered",
            context = context,
            metadata = metadata(proposal, entry.generation)
        )

        return LearningConsolidationRegistrationResult.Registered(
            registration = object : LearningConsolidationRegistration {
                override val proposal: LearningConsolidationProposal = proposal
                override val generation: LearningConsolidationGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = entries.remove(proposal.id, entry)
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) {
                            "LEARNING_CONSOLIDATION_REMOVED"
                        } else {
                            "LEARNING_CONSOLIDATION_REMOVAL_REJECTED"
                        },
                        message = if (removed) {
                            "learning consolidation proposal removed"
                        } else {
                            "learning consolidation registration is no longer current"
                        },
                        context = context,
                        metadata = metadata(proposal, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: LearningConsolidationId): LearningConsolidationProposal? = entries[id]?.proposal

    fun inspect(id: LearningConsolidationId): LearningConsolidationSnapshot? = entries[id]?.let { entry ->
        LearningConsolidationSnapshot(entry.proposal, entry.generation)
    }

    fun contains(id: LearningConsolidationId): Boolean = entries.containsKey(id)

    fun snapshot(): List<LearningConsolidationProposal> = snapshotEntries().map { it.proposal }

    fun snapshotEntries(): List<LearningConsolidationSnapshot> = entries.values
        .map { entry -> LearningConsolidationSnapshot(entry.proposal, entry.generation) }
        .sortedWith(
            compareBy<LearningConsolidationSnapshot> { it.proposal.createdAt }
                .thenBy { it.proposal.id.value }
        )

    private fun metadata(
        proposal: LearningConsolidationProposal,
        generation: LearningConsolidationGeneration
    ): Map<String, String> = buildMap {
        put("learningConsolidationId", proposal.id.value)
        put("learningConsolidationGeneration", generation.value.toString())
        put("sourceCount", proposal.sources.size.toString())
        put("sourceMutationIds", proposal.sources.joinToString(",") { it.mutation.mutationId.value })
        put("createdAt", proposal.createdAt.toString())
    }
}
