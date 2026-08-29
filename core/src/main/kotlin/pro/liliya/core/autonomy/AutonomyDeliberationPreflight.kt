package pro.liliya.core.autonomy

import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

class AutonomyDeliberationReadyEvidence internal constructor(
    val request: AutonomyDeliberationRequest,
    val requestGeneration: AutonomyDeliberationGeneration,
    val attempt: AutonomyDeliberationAttemptEvidence
) {
    override fun toString(): String =
        "AutonomyDeliberationReadyEvidence(" +
            "requestId=${request.id}, requestGeneration=$requestGeneration, " +
            "proposalId=${attempt.proposal.id}, proposalGeneration=${attempt.generation}, " +
            "attemptNumber=${attempt.attemptNumber})"
}

sealed interface AutonomyDeliberationPreflightResult {
    data class Ready(val evidence: AutonomyDeliberationReadyEvidence) : AutonomyDeliberationPreflightResult
    data class Rejected(val reason: String) : AutonomyDeliberationPreflightResult {
        init { require(reason.isNotBlank()) { "autonomy deliberation preflight rejection reason must not be blank" } }
    }
}

class AutonomyDeliberationPreflight(
    private val foundation: FoundationComposition,
    private val deliberation: AutonomyDeliberationComposition,
    private val gate: ControlledAutonomyDeliberationGate
) {
    fun check(
        requestId: AutonomyDeliberationRequestId,
        generation: AutonomyDeliberationGeneration
    ): AutonomyDeliberationPreflightResult {
        val snapshot = deliberation.inspect(requestId)
            ?: return reject(requestId, generation, "autonomy deliberation request is not live")
        if (snapshot.generation != generation) {
            return reject(requestId, generation, "autonomy deliberation request generation is stale")
        }

        val attempt = when (val result = gate.validateAttempt(snapshot.request.autonomy)) {
            is AutonomyDeliberationAttemptValidationResult.Valid -> result.evidence
            is AutonomyDeliberationAttemptValidationResult.Rejected ->
                return reject(requestId, generation, result.reason)
        }

        val evidence = AutonomyDeliberationReadyEvidence(
            request = snapshot.request,
            requestGeneration = generation,
            attempt = attempt
        )
        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AUTONOMY_DELIBERATION_PREFLIGHT_READY",
            message = "autonomy deliberation preflight ready",
            context = foundation.rootContext(
                operation = "preflightAutonomyDeliberation",
                component = "Autonomy",
                metadata = metadata(evidence)
            ),
            metadata = metadata(evidence)
        )
        return AutonomyDeliberationPreflightResult.Ready(evidence)
    }

    private fun reject(
        requestId: AutonomyDeliberationRequestId,
        generation: AutonomyDeliberationGeneration,
        reason: String
    ): AutonomyDeliberationPreflightResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AUTONOMY_DELIBERATION_PREFLIGHT_REJECTED",
            message = reason,
            context = foundation.rootContext(
                operation = "preflightAutonomyDeliberation",
                component = "Autonomy",
                metadata = mapOf(
                    "autonomyDeliberationRequestId" to requestId.value,
                    "autonomyDeliberationGeneration" to generation.value.toString()
                )
            )
        )
        return AutonomyDeliberationPreflightResult.Rejected(reason)
    }

    private fun metadata(evidence: AutonomyDeliberationReadyEvidence): Map<String, String> = mapOf(
        "autonomyDeliberationRequestId" to evidence.request.id.value,
        "autonomyDeliberationGeneration" to evidence.requestGeneration.value.toString(),
        "autonomyProposalId" to evidence.attempt.proposal.id.value,
        "autonomyGeneration" to evidence.attempt.generation.value.toString(),
        "autonomyAttemptNumber" to evidence.attempt.attemptNumber.toString(),
        "autonomyPriority" to evidence.attempt.proposal.priority.name,
        "autonomyMaxAttempts" to evidence.attempt.proposal.budget.maxAttempts.toString()
    )
}
