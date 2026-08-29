package pro.liliya.core.learning

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

internal enum class LearningConsolidationClaimRejection {
    CONSOLIDATION_MISSING,
    CONSOLIDATION_GENERATION_MISMATCH,
    ALREADY_CLAIMED
}

internal interface LearningConsolidationClaimRegistration {
    val proposal: LearningConsolidationProposal
    val generation: LearningConsolidationGeneration
    fun release(context: LogContext): Boolean
}

internal sealed interface LearningConsolidationClaimRegistrationResult {
    data class Claimed(
        val claim: LearningConsolidationClaimRegistration
    ) : LearningConsolidationClaimRegistrationResult

    data class Rejected(
        val reason: LearningConsolidationClaimRejection
    ) : LearningConsolidationClaimRegistrationResult
}

internal class LearningConsolidationStore(
    private val observability: CoreObservability
) {
    private class ClaimToken

    private data class Entry(
        val generation: LearningConsolidationGeneration,
        val proposal: LearningConsolidationProposal,
        var activeClaim: ClaimToken? = null
    )

    private val lock = Any()
    private val nextGeneration = AtomicLong(0)
    private val entries = mutableMapOf<LearningConsolidationId, Entry>()

    fun register(
        proposal: LearningConsolidationProposal,
        context: LogContext
    ): LearningConsolidationRegistrationResult = synchronized(lock) {
        val existing = entries[proposal.id]
        if (existing != null) {
            val reason = "learning consolidation ${proposal.id} is already registered"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "LEARNING_CONSOLIDATION_REGISTRATION_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(proposal, existing.generation) + ("rejectionReason" to reason)
            )
            return@synchronized LearningConsolidationRegistrationResult.Rejected(reason)
        }

        val entry = Entry(
            generation = LearningConsolidationGeneration(nextGeneration.incrementAndGet()),
            proposal = proposal
        )
        entries[proposal.id] = entry

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "LEARNING_CONSOLIDATION_REGISTERED",
            message = "learning consolidation proposal registered",
            context = context,
            metadata = metadata(proposal, entry.generation)
        )

        LearningConsolidationRegistrationResult.Registered(
            registration = object : LearningConsolidationRegistration {
                override val proposal: LearningConsolidationProposal = proposal
                override val generation: LearningConsolidationGeneration = entry.generation

                override fun remove(context: LogContext): Boolean = synchronized(lock) {
                    val current = entries[proposal.id]
                    val removed = current === entry && entry.activeClaim == null && entries.remove(proposal.id) === entry
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) {
                            "LEARNING_CONSOLIDATION_REMOVED"
                        } else {
                            "LEARNING_CONSOLIDATION_REMOVAL_REJECTED"
                        },
                        message = if (removed) {
                            "learning consolidation proposal removed"
                        } else if (current === entry && entry.activeClaim != null) {
                            "learning consolidation proposal is actively claimed"
                        } else {
                            "learning consolidation registration is no longer current"
                        },
                        context = context,
                        metadata = metadata(proposal, entry.generation)
                    )
                    removed
                }
            }
        )
    }

    fun claim(
        reference: LearningConsolidationReference,
        context: LogContext
    ): LearningConsolidationClaimRegistrationResult = synchronized(lock) {
        val entry = entries[reference.consolidationId]
            ?: return@synchronized claimRejected(
                reference,
                LearningConsolidationClaimRejection.CONSOLIDATION_MISSING,
                context
            )
        if (entry.generation != reference.generation) {
            return@synchronized claimRejected(
                reference,
                LearningConsolidationClaimRejection.CONSOLIDATION_GENERATION_MISMATCH,
                context
            )
        }
        if (entry.activeClaim != null) {
            return@synchronized claimRejected(
                reference,
                LearningConsolidationClaimRejection.ALREADY_CLAIMED,
                context
            )
        }

        val token = ClaimToken()
        entry.activeClaim = token
        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "LEARNING_CONSOLIDATION_CLAIMED",
            message = "learning consolidation proposal claimed",
            context = context,
            metadata = metadata(entry.proposal, entry.generation)
        )

        LearningConsolidationClaimRegistrationResult.Claimed(
            claim = object : LearningConsolidationClaimRegistration {
                override val proposal: LearningConsolidationProposal = entry.proposal
                override val generation: LearningConsolidationGeneration = entry.generation

                override fun release(context: LogContext): Boolean = synchronized(lock) {
                    val current = entries[reference.consolidationId]
                    val released = current === entry && entry.activeClaim === token
                    if (released) {
                        entry.activeClaim = null
                    }
                    observability.record(
                        severity = if (released) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (released) {
                            "LEARNING_CONSOLIDATION_CLAIM_RELEASED"
                        } else {
                            "LEARNING_CONSOLIDATION_CLAIM_RELEASE_REJECTED"
                        },
                        message = if (released) {
                            "learning consolidation proposal claim released"
                        } else {
                            "learning consolidation proposal claim is no longer current"
                        },
                        context = context,
                        metadata = metadata(entry.proposal, entry.generation)
                    )
                    released
                }
            }
        )
    }

    fun find(id: LearningConsolidationId): LearningConsolidationProposal? = synchronized(lock) {
        entries[id]?.proposal
    }

    fun inspect(id: LearningConsolidationId): LearningConsolidationSnapshot? = synchronized(lock) {
        entries[id]?.let { entry -> LearningConsolidationSnapshot(entry.proposal, entry.generation) }
    }

    fun contains(id: LearningConsolidationId): Boolean = synchronized(lock) {
        entries.containsKey(id)
    }

    fun snapshot(): List<LearningConsolidationProposal> = snapshotEntries().map { it.proposal }

    fun snapshotEntries(): List<LearningConsolidationSnapshot> = synchronized(lock) {
        entries.values
            .map { entry -> LearningConsolidationSnapshot(entry.proposal, entry.generation) }
            .sortedWith(
                compareBy<LearningConsolidationSnapshot> { it.proposal.createdAt }
                    .thenBy { it.proposal.id.value }
            )
    }

    private fun claimRejected(
        reference: LearningConsolidationReference,
        reason: LearningConsolidationClaimRejection,
        context: LogContext
    ): LearningConsolidationClaimRegistrationResult.Rejected {
        observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "LEARNING_CONSOLIDATION_CLAIM_REJECTED",
            message = "learning consolidation proposal claim rejected",
            context = context,
            metadata = mapOf(
                "learningConsolidationId" to reference.consolidationId.value,
                "learningConsolidationGeneration" to reference.generation.value.toString(),
                "rejectionReason" to reason.name.lowercase()
            )
        )
        return LearningConsolidationClaimRegistrationResult.Rejected(reason)
    }

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
