package pro.liliya.core.autonomy

import java.util.concurrent.ConcurrentHashMap
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

class AutonomyDeliberationAttemptEvidence internal constructor(
    val proposal: AutonomyProposal,
    val generation: AutonomyGeneration,
    val attemptNumber: Int
) {
    override fun toString(): String =
        "AutonomyDeliberationAttemptEvidence(" +
            "proposalId=${proposal.id}, generation=$generation, attemptNumber=$attemptNumber)"
}

sealed interface AutonomyDeliberationAttemptResult {
    data class Claimed(val evidence: AutonomyDeliberationAttemptEvidence) : AutonomyDeliberationAttemptResult
    data class Rejected(val reason: String) : AutonomyDeliberationAttemptResult {
        init { require(reason.isNotBlank()) { "autonomy deliberation rejection reason must not be blank" } }
    }
}

sealed interface AutonomyDeliberationAttemptValidationResult {
    data class Valid(val evidence: AutonomyDeliberationAttemptEvidence) : AutonomyDeliberationAttemptValidationResult
    data class Rejected(val reason: String) : AutonomyDeliberationAttemptValidationResult {
        init { require(reason.isNotBlank()) { "autonomy attempt validation rejection reason must not be blank" } }
    }
}

sealed interface AutonomyDeliberationCancellationResult {
    data object Cancelled : AutonomyDeliberationCancellationResult
    data class Rejected(val reason: String) : AutonomyDeliberationCancellationResult {
        init { require(reason.isNotBlank()) { "autonomy cancellation rejection reason must not be blank" } }
    }
}

class ControlledAutonomyDeliberationGate(
    private val foundation: FoundationComposition,
    private val autonomy: AutonomyComposition
) {
    private data class ExactProposalKey(
        val id: AutonomyProposalId,
        val generation: AutonomyGeneration
    )

    private data class AttemptState(
        var attempts: Int = 0,
        var cancelled: Boolean = false
    )

    private val lock = Any()
    private val states = ConcurrentHashMap<ExactProposalKey, AttemptState>()

    fun claimAttempt(
        id: AutonomyProposalId,
        generation: AutonomyGeneration
    ): AutonomyDeliberationAttemptResult = synchronized(lock) {
        val snapshot = autonomy.inspect(id)
            ?: return@synchronized rejectAttempt(id, generation, "autonomy proposal is not live")
        if (snapshot.generation != generation) {
            return@synchronized rejectAttempt(id, generation, "autonomy proposal generation is stale")
        }

        val key = ExactProposalKey(id, generation)
        val state = states.computeIfAbsent(key) { AttemptState() }
        if (state.cancelled) {
            return@synchronized rejectAttempt(id, generation, "autonomy proposal deliberation is cancelled")
        }
        if (state.attempts >= snapshot.proposal.budget.maxAttempts) {
            return@synchronized rejectAttempt(id, generation, "autonomy proposal attempt budget is exhausted")
        }

        state.attempts += 1
        val evidence = AutonomyDeliberationAttemptEvidence(
            proposal = snapshot.proposal,
            generation = generation,
            attemptNumber = state.attempts
        )
        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AUTONOMY_DELIBERATION_ATTEMPT_CLAIMED",
            message = "autonomy deliberation attempt claimed",
            context = foundation.rootContext(
                operation = "claimAutonomyDeliberationAttempt",
                component = "Autonomy",
                metadata = structuralMetadata(evidence)
            ),
            metadata = structuralMetadata(evidence)
        )
        AutonomyDeliberationAttemptResult.Claimed(evidence)
    }

    fun validateAttempt(
        reference: AutonomyAttemptReference
    ): AutonomyDeliberationAttemptValidationResult = synchronized(lock) {
        val snapshot = autonomy.inspect(reference.proposalId)
            ?: return@synchronized rejectValidation(reference, "autonomy proposal is not live")
        if (snapshot.generation != reference.proposalGeneration) {
            return@synchronized rejectValidation(reference, "autonomy proposal generation is stale")
        }

        val state = states[ExactProposalKey(reference.proposalId, reference.proposalGeneration)]
            ?: return@synchronized rejectValidation(reference, "autonomy deliberation attempt was not claimed")
        if (state.cancelled) {
            return@synchronized rejectValidation(reference, "autonomy proposal deliberation is cancelled")
        }
        if (reference.attemptNumber > state.attempts) {
            return@synchronized rejectValidation(reference, "autonomy deliberation attempt was not claimed")
        }
        if (reference.attemptNumber > snapshot.proposal.budget.maxAttempts) {
            return@synchronized rejectValidation(reference, "autonomy deliberation attempt exceeds proposal budget")
        }

        val evidence = AutonomyDeliberationAttemptEvidence(
            proposal = snapshot.proposal,
            generation = reference.proposalGeneration,
            attemptNumber = reference.attemptNumber
        )
        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AUTONOMY_DELIBERATION_ATTEMPT_VALIDATED",
            message = "autonomy deliberation attempt validated",
            context = foundation.rootContext(
                operation = "validateAutonomyDeliberationAttempt",
                component = "Autonomy",
                metadata = structuralMetadata(evidence)
            ),
            metadata = structuralMetadata(evidence)
        )
        AutonomyDeliberationAttemptValidationResult.Valid(evidence)
    }

    fun cancel(
        id: AutonomyProposalId,
        generation: AutonomyGeneration
    ): AutonomyDeliberationCancellationResult = synchronized(lock) {
        val snapshot = autonomy.inspect(id)
            ?: return@synchronized rejectCancellation(id, generation, "autonomy proposal is not live")
        if (snapshot.generation != generation) {
            return@synchronized rejectCancellation(id, generation, "autonomy proposal generation is stale")
        }

        val key = ExactProposalKey(id, generation)
        val state = states.computeIfAbsent(key) { AttemptState() }
        state.cancelled = true
        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AUTONOMY_DELIBERATION_CANCELLED",
            message = "autonomy deliberation cancelled",
            context = foundation.rootContext(
                operation = "cancelAutonomyDeliberation",
                component = "Autonomy",
                metadata = mapOf(
                    "autonomyProposalId" to id.value,
                    "autonomyGeneration" to generation.value.toString()
                )
            )
        )
        AutonomyDeliberationCancellationResult.Cancelled
    }

    private fun rejectAttempt(
        id: AutonomyProposalId,
        generation: AutonomyGeneration,
        reason: String
    ): AutonomyDeliberationAttemptResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AUTONOMY_DELIBERATION_ATTEMPT_REJECTED",
            message = reason,
            context = foundation.rootContext(
                operation = "claimAutonomyDeliberationAttempt",
                component = "Autonomy",
                metadata = mapOf(
                    "autonomyProposalId" to id.value,
                    "autonomyGeneration" to generation.value.toString()
                )
            )
        )
        return AutonomyDeliberationAttemptResult.Rejected(reason)
    }

    private fun rejectValidation(
        reference: AutonomyAttemptReference,
        reason: String
    ): AutonomyDeliberationAttemptValidationResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AUTONOMY_DELIBERATION_ATTEMPT_VALIDATION_REJECTED",
            message = reason,
            context = foundation.rootContext(
                operation = "validateAutonomyDeliberationAttempt",
                component = "Autonomy",
                metadata = mapOf(
                    "autonomyProposalId" to reference.proposalId.value,
                    "autonomyGeneration" to reference.proposalGeneration.value.toString(),
                    "autonomyAttemptNumber" to reference.attemptNumber.toString()
                )
            )
        )
        return AutonomyDeliberationAttemptValidationResult.Rejected(reason)
    }

    private fun rejectCancellation(
        id: AutonomyProposalId,
        generation: AutonomyGeneration,
        reason: String
    ): AutonomyDeliberationCancellationResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AUTONOMY_DELIBERATION_CANCELLATION_REJECTED",
            message = reason,
            context = foundation.rootContext(
                operation = "cancelAutonomyDeliberation",
                component = "Autonomy",
                metadata = mapOf(
                    "autonomyProposalId" to id.value,
                    "autonomyGeneration" to generation.value.toString()
                )
            )
        )
        return AutonomyDeliberationCancellationResult.Rejected(reason)
    }

    private fun structuralMetadata(
        evidence: AutonomyDeliberationAttemptEvidence
    ): Map<String, String> = mapOf(
        "autonomyProposalId" to evidence.proposal.id.value,
        "autonomyGeneration" to evidence.generation.value.toString(),
        "autonomyAttemptNumber" to evidence.attemptNumber.toString(),
        "autonomyMaxAttempts" to evidence.proposal.budget.maxAttempts.toString(),
        "autonomyPriority" to evidence.proposal.priority.name
    )
}
