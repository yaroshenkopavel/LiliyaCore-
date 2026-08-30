package pro.liliya.core.agent

import pro.liliya.core.autonomy.AutonomyAttemptReference
import pro.liliya.core.autonomy.AutonomyDeliberationGeneration
import pro.liliya.core.autonomy.AutonomyDeliberationPreflight
import pro.liliya.core.autonomy.AutonomyDeliberationPreflightResult
import pro.liliya.core.autonomy.AutonomyDeliberationRequestId
import pro.liliya.core.autonomy.AutonomyDeliberationReadyEvidence
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

class AgentCoordinationDeliberationReadyEvidence internal constructor(
    val coordination: ExactAgentCoordinationReference,
    val attemptBindingGeneration: AgentCoordinationAttemptBindingGeneration,
    val participant: ExactAgentReference,
    val requestId: AutonomyDeliberationRequestId,
    val requestGeneration: AutonomyDeliberationGeneration,
    val attempt: AutonomyAttemptReference
) {
    override fun toString(): String =
        "AgentCoordinationDeliberationReadyEvidence(" +
            "coordination=$coordination, attemptBindingGeneration=$attemptBindingGeneration, " +
            "participant=$participant, requestId=$requestId, requestGeneration=$requestGeneration, " +
            "attempt=$attempt)"
}

sealed interface AgentCoordinationDeliberationPreflightResult {
    data class Ready(
        val evidence: AgentCoordinationDeliberationReadyEvidence
    ) : AgentCoordinationDeliberationPreflightResult

    data class Rejected(val reason: String) : AgentCoordinationDeliberationPreflightResult {
        init {
            require(reason.isNotBlank()) {
                "coordination deliberation preflight rejection reason must not be blank"
            }
        }
    }
}

internal fun interface AgentAutonomyDeliberationPreflightChecker {
    fun check(
        requestId: AutonomyDeliberationRequestId,
        generation: AutonomyDeliberationGeneration
    ): AutonomyDeliberationPreflightResult
}

/**
 * Evidence-only live preflight for one exact coordinated deliberation request.
 *
 * The exact Autonomy attempt is derived from the live deliberation request, resolved back to the
 * trusted coordination-attempt binding, and then checked against the current exact binding
 * generation plus fresh coordination participant/lifecycle governance. No Planning/Reasoning/
 * Decision, scheduling, Authority or Execution is performed and returned evidence is not permission.
 */
class ControlledAgentCoordinationDeliberationPreflight private constructor(
    private val foundation: FoundationComposition,
    private val attemptBindings: AgentCoordinationAttemptBindingComposition,
    private val coordinationPreflight: AgentCoordinationPreflightChecker,
    private val deliberationPreflight: AgentAutonomyDeliberationPreflightChecker
) {
    constructor(
        foundation: FoundationComposition,
        attemptBindings: AgentCoordinationAttemptBindingComposition,
        coordinationPreflight: ControlledAgentCoordinationPreflight,
        deliberationPreflight: AutonomyDeliberationPreflight
    ) : this(
        foundation = foundation,
        attemptBindings = attemptBindings,
        coordinationPreflight = AgentCoordinationPreflightChecker(coordinationPreflight::check),
        deliberationPreflight = AgentAutonomyDeliberationPreflightChecker(deliberationPreflight::check)
    )

    internal constructor(
        foundation: FoundationComposition,
        attemptBindings: AgentCoordinationAttemptBindingComposition,
        coordinationPreflight: AgentCoordinationPreflightChecker,
        deliberationPreflight: AgentAutonomyDeliberationPreflightChecker,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit
    ) : this(foundation, attemptBindings, coordinationPreflight, deliberationPreflight)

    fun check(
        requestId: AutonomyDeliberationRequestId,
        requestGeneration: AutonomyDeliberationGeneration
    ): AgentCoordinationDeliberationPreflightResult {
        val context = foundation.rootContext(
            operation = "preflightAgentCoordinationDeliberation",
            component = "AgentCoordination",
            metadata = mapOf(
                "autonomyDeliberationRequestId" to requestId.value,
                "autonomyDeliberationGeneration" to requestGeneration.value.toString()
            )
        )

        val autonomyEvidence = when (
            val checked = deliberationPreflight.check(requestId, requestGeneration)
        ) {
            is AutonomyDeliberationPreflightResult.Ready -> checked.evidence
            is AutonomyDeliberationPreflightResult.Rejected -> return reject(
                "autonomy deliberation preflight rejected: ${checked.reason}",
                context
            )
        }

        val attempt = exactAttempt(autonomyEvidence)
        val binding = attemptBindings.findByAttempt(attempt)
            ?: return reject("exact deliberation attempt is not coordination-bound", context)
        val bindingSnapshot = attemptBindings.inspect(binding.coordination)
            ?: return reject("coordination attempt binding is not live", context)
        if (bindingSnapshot.binding != binding) {
            return reject("coordination attempt binding changed during preflight", context)
        }

        val assignment = binding.assignments.singleOrNull { it.attempt == attempt }
            ?: return reject("coordination attempt binding does not uniquely own exact attempt", context)

        val coordinationEvidence = when (
            val checked = coordinationPreflight.check(
                AgentCoordinationPreflightRequest(
                    coordinationId = binding.coordination.id,
                    coordinationGeneration = binding.coordination.generation
                )
            )
        ) {
            is AgentCoordinationPreflightResult.Ready -> checked.evidence
            is AgentCoordinationPreflightResult.Rejected -> return reject(
                "coordination preflight rejected: ${checked.reason}",
                context
            )
        }

        if (
            coordinationEvidence.coordinationId != binding.coordination.id ||
            coordinationEvidence.coordinationGeneration != binding.coordination.generation ||
            coordinationEvidence.participants != binding.assignments.map { it.participant }
        ) {
            return reject("coordination evidence does not match exact attempt binding", context)
        }

        val confirmed = attemptBindings.inspect(binding.coordination)
            ?: return reject("coordination attempt binding changed before evidence commit", context)
        if (
            confirmed.generation != bindingSnapshot.generation ||
            confirmed.binding != bindingSnapshot.binding
        ) {
            return reject("coordination attempt binding changed before evidence commit", context)
        }

        val evidence = AgentCoordinationDeliberationReadyEvidence(
            coordination = binding.coordination,
            attemptBindingGeneration = confirmed.generation,
            participant = assignment.participant,
            requestId = requestId,
            requestGeneration = requestGeneration,
            attempt = attempt
        )
        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_COORDINATION_DELIBERATION_PREFLIGHT_READY",
            message = "coordination deliberation preflight ready",
            context = context,
            metadata = evidenceMetadata(evidence)
        )
        return AgentCoordinationDeliberationPreflightResult.Ready(evidence)
    }

    private fun exactAttempt(evidence: AutonomyDeliberationReadyEvidence): AutonomyAttemptReference =
        AutonomyAttemptReference(
            proposalId = evidence.attempt.proposal.id,
            proposalGeneration = evidence.attempt.generation,
            attemptNumber = evidence.attempt.attemptNumber
        )

    private fun reject(
        reason: String,
        context: pro.liliya.core.logging.LogContext
    ): AgentCoordinationDeliberationPreflightResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_COORDINATION_DELIBERATION_PREFLIGHT_REJECTED",
            message = reason,
            context = context,
            metadata = mapOf("rejectionReason" to reason)
        )
        return AgentCoordinationDeliberationPreflightResult.Rejected(reason)
    }

    private fun evidenceMetadata(
        evidence: AgentCoordinationDeliberationReadyEvidence
    ): Map<String, String> = mapOf(
        "agentCoordinationId" to evidence.coordination.id.value,
        "agentCoordinationGeneration" to evidence.coordination.generation.value.toString(),
        "attemptBindingGeneration" to evidence.attemptBindingGeneration.value.toString(),
        "participantAgentId" to evidence.participant.id.value,
        "participantAgentGeneration" to evidence.participant.generation.value.toString(),
        "autonomyDeliberationRequestId" to evidence.requestId.value,
        "autonomyDeliberationGeneration" to evidence.requestGeneration.value.toString(),
        "autonomyProposalId" to evidence.attempt.proposalId.value,
        "autonomyGeneration" to evidence.attempt.proposalGeneration.value.toString(),
        "autonomyAttemptNumber" to evidence.attempt.attemptNumber.toString()
    )
}
