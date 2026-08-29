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
    data class Registered(val registration: LearningConsolidationRegistration) : LearningConsolidationRegistrationResult
    data class Rejected(val reason: String) : LearningConsolidationRegistrationResult
}

enum class LearningConsolidationConversionRejection {
    CONSOLIDATION_MISSING,
    CONSOLIDATION_GENERATION_MISMATCH,
    ALREADY_CLAIMED
}

internal interface LearningConsolidationConversionClaimRegistration {
    val proposal: LearningConsolidationProposal
    val reference: LearningConsolidationReference
    fun release(context: LogContext): Boolean
    fun complete(candidate: LearningCandidateReference, context: LogContext): Boolean
}

internal sealed interface LearningConsolidationConversionClaimResult {
    data class Claimed(
        val claim: LearningConsolidationConversionClaimRegistration
    ) : LearningConsolidationConversionClaimResult

    data class AlreadyConverted(
        val candidate: LearningCandidateReference
    ) : LearningConsolidationConversionClaimResult

    data class Rejected(
        val reason: LearningConsolidationConversionRejection
    ) : LearningConsolidationConversionClaimResult
}

internal class LearningConsolidationStore(
    private val observability: CoreObservability
) {
    private class ConversionToken

    private data class Entry(
        val generation: LearningConsolidationGeneration,
        val proposal: LearningConsolidationProposal,
        var activeConversion: ConversionToken? = null,
        var convertedCandidate: LearningCandidateReference? = null
    )

    private val lock = Any()
    private val nextGeneration = AtomicLong(0)
    private val entries = mutableMapOf<LearningConsolidationId, Entry>()

    fun register(
        proposal: LearningConsolidationProposal,
        context: LogContext
    ): LearningConsolidationRegistrationResult = synchronized(lock) {
        if (entries.containsKey(proposal.id)) {
            val reason = "learning consolidation ${proposal.id} is already registered"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "LEARNING_CONSOLIDATION_REGISTRATION_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(proposal, null) + ("rejectionReason" to reason)
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
                    val removed = current === entry &&
                        entry.activeConversion == null &&
                        entries.remove(proposal.id) === entry
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) "LEARNING_CONSOLIDATION_REMOVED" else "LEARNING_CONSOLIDATION_REMOVAL_REJECTED",
                        message = if (removed) {
                            "learning consolidation proposal removed"
                        } else if (current === entry && entry.activeConversion != null) {
                            "learning consolidation proposal is actively converting"
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

    fun claimConversion(
        reference: LearningConsolidationReference,
        context: LogContext
    ): LearningConsolidationConversionClaimResult = synchronized(lock) {
        val entry = entries[reference.consolidationId]
            ?: return@synchronized conversionRejected(
                reference,
                LearningConsolidationConversionRejection.CONSOLIDATION_MISSING,
                context
            )
        if (entry.generation != reference.generation) {
            return@synchronized conversionRejected(
                reference,
                LearningConsolidationConversionRejection.CONSOLIDATION_GENERATION_MISMATCH,
                context
            )
        }
        entry.convertedCandidate?.let { candidate ->
            return@synchronized LearningConsolidationConversionClaimResult.AlreadyConverted(candidate)
        }
        if (entry.activeConversion != null) {
            return@synchronized conversionRejected(
                reference,
                LearningConsolidationConversionRejection.ALREADY_CLAIMED,
                context
            )
        }

        val token = ConversionToken()
        entry.activeConversion = token
        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "LEARNING_CONSOLIDATION_CONVERSION_CLAIMED",
            message = "learning consolidation candidate conversion claimed",
            context = context,
            metadata = metadata(entry.proposal, entry.generation)
        )

        LearningConsolidationConversionClaimResult.Claimed(
            claim = object : LearningConsolidationConversionClaimRegistration {
                override val proposal: LearningConsolidationProposal = entry.proposal
                override val reference: LearningConsolidationReference = reference

                override fun release(context: LogContext): Boolean = synchronized(lock) {
                    val current = entries[reference.consolidationId]
                    val released = current === entry && entry.activeConversion === token
                    if (released) entry.activeConversion = null
                    observability.record(
                        severity = if (released) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (released) {
                            "LEARNING_CONSOLIDATION_CONVERSION_RELEASED"
                        } else {
                            "LEARNING_CONSOLIDATION_CONVERSION_RELEASE_REJECTED"
                        },
                        message = if (released) {
                            "learning consolidation candidate conversion released"
                        } else {
                            "learning consolidation conversion claim is no longer current"
                        },
                        context = context,
                        metadata = metadata(entry.proposal, entry.generation)
                    )
                    released
                }

                override fun complete(
                    candidate: LearningCandidateReference,
                    context: LogContext
                ): Boolean = synchronized(lock) {
                    val current = entries[reference.consolidationId]
                    val completed = current === entry &&
                        entry.activeConversion === token &&
                        entry.convertedCandidate == null
                    if (completed) {
                        entry.convertedCandidate = candidate
                        entry.activeConversion = null
                    }
                    observability.record(
                        severity = if (completed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (completed) {
                            "LEARNING_CONSOLIDATION_CONVERSION_COMPLETED"
                        } else {
                            "LEARNING_CONSOLIDATION_CONVERSION_COMPLETION_REJECTED"
                        },
                        message = if (completed) {
                            "learning consolidation candidate conversion completed"
                        } else {
                            "learning consolidation conversion claim is no longer current"
                        },
                        context = context,
                        metadata = metadata(entry.proposal, entry.generation) + mapOf(
                            "learningCandidateId" to candidate.candidateId.value,
                            "learningGeneration" to candidate.generation.value.toString()
                        )
                    )
                    completed
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

    fun contains(id: LearningConsolidationId): Boolean = synchronized(lock) { entries.containsKey(id) }

    fun snapshot(): List<LearningConsolidationProposal> = snapshotEntries().map { it.proposal }

    fun snapshotEntries(): List<LearningConsolidationSnapshot> = synchronized(lock) {
        entries.values
            .map { entry -> LearningConsolidationSnapshot(entry.proposal, entry.generation) }
            .sortedWith(
                compareBy<LearningConsolidationSnapshot> { it.proposal.createdAt }
                    .thenBy { it.proposal.id.value }
            )
    }

    private fun conversionRejected(
        reference: LearningConsolidationReference,
        reason: LearningConsolidationConversionRejection,
        context: LogContext
    ): LearningConsolidationConversionClaimResult.Rejected {
        observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "LEARNING_CONSOLIDATION_CONVERSION_REJECTED",
            message = "learning consolidation candidate conversion rejected",
            context = context,
            metadata = mapOf(
                "learningConsolidationId" to reference.consolidationId.value,
                "learningConsolidationGeneration" to reference.generation.value.toString(),
                "rejectionReason" to reason.name.lowercase()
            )
        )
        return LearningConsolidationConversionClaimResult.Rejected(reason)
    }

    private fun metadata(
        proposal: LearningConsolidationProposal,
        generation: LearningConsolidationGeneration?
    ): Map<String, String> = buildMap {
        put("learningConsolidationId", proposal.id.value)
        generation?.let { put("learningConsolidationGeneration", it.value.toString()) }
        put("sourceCount", proposal.sources.size.toString())
        put("sourceMutationIds", proposal.sources.joinToString(",") { it.mutation.mutationId.value })
        put("createdAt", proposal.createdAt.toString())
    }
}
