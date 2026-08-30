package pro.liliya.core.agent

import pro.liliya.core.autonomy.AutonomyAttemptReference
import pro.liliya.core.autonomy.AutonomyDeliberationAttemptValidationResult
import pro.liliya.core.autonomy.AutonomyDeliberationCancellationResult
import pro.liliya.core.autonomy.ControlledAutonomyDeliberationGate
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

class AgentCoordinationParticipantAttempt(
    val participant: ExactAgentReference,
    val autonomy: ExactAutonomyReference,
    val attemptNumber: Int
) {
    init {
        require(attemptNumber > 0) { "coordination participant attempt number must be positive" }
    }

    fun exactAttempt(): AutonomyAttemptReference = AutonomyAttemptReference(
        proposalId = autonomy.proposalId,
        proposalGeneration = autonomy.generation,
        attemptNumber = attemptNumber
    )
}

class AgentCoordinationAttemptReceipt(
    val coordination: ExactAgentCoordinationReference,
    attempts: List<AgentCoordinationParticipantAttempt>,
    val attemptBindingGeneration: AgentCoordinationAttemptBindingGeneration
) {
    val attempts: List<AgentCoordinationParticipantAttempt> = attempts.toList()

    init {
        require(this.attempts.size >= 2) { "coordination attempt receipt requires at least two attempts" }
    }
}

sealed interface AgentCoordinationAttemptResult {
    data class Claimed(val receipt: AgentCoordinationAttemptReceipt) : AgentCoordinationAttemptResult

    data class Rejected(val reason: String) : AgentCoordinationAttemptResult {
        init { require(reason.isNotBlank()) { "coordination attempt rejection reason must not be blank" } }
    }

    data class Failed(val reason: String) : AgentCoordinationAttemptResult {
        init { require(reason.isNotBlank()) { "coordination attempt failure reason must not be blank" } }
    }
}

internal fun interface AgentCoordinationParticipantAttemptClaimer {
    fun claim(
        participant: ExactAgentReference,
        autonomy: ExactAutonomyReference
    ): AgentInitiativeAttemptResult
}

internal fun interface AgentCoordinationAttemptBindingInstaller {
    fun install(binding: AgentCoordinationAttemptBinding): AgentCoordinationAttemptBindingInstallResult
}

/**
 * Transactional bounded-attempt gate for one exact coordination work-set.
 *
 * The exact coordination, participant ACTIVE lifecycle and exact coordination-work binding are
 * freshly checked before the transaction, before each participant claim, after each participant
 * claim and once more before commit. A successful result is returned only after the complete exact
 * participant attempt-set has been committed into the trusted coordination-attempt binding store.
 *
 * If governance changes, a participant rejects, or the attempt-binding commit rejects after one or
 * more claims, every exact claimed Autonomy generation is invalidated before a normal rejection can
 * be returned. If an exact claimed attempt remains valid after compensation, failure is explicit and
 * CRITICAL-observable.
 *
 * The committed binding records provenance only. It is not permission and does not replace fresh
 * live validation at a future execution boundary. This gate creates no deliberation request,
 * schedules nothing and grants no Authority or Execution right.
 */
class ControlledAgentCoordinationInitiativeGate private constructor(
    private val foundation: FoundationComposition,
    private val bindings: AgentCoordinationWorkBindingComposition,
    private val preflight: AgentCoordinationPreflightChecker,
    private val claimer: AgentCoordinationParticipantAttemptClaimer,
    private val autonomyGate: ControlledAutonomyDeliberationGate,
    private val attemptBindings: AgentCoordinationAttemptBindingInstaller
) {
    constructor(
        foundation: FoundationComposition,
        bindings: AgentCoordinationWorkBindingComposition,
        preflight: ControlledAgentCoordinationPreflight,
        agentGate: ControlledAgentInitiativeGate,
        autonomyGate: ControlledAutonomyDeliberationGate,
        attemptBindings: AgentCoordinationAttemptBindingComposition
    ) : this(
        foundation = foundation,
        bindings = bindings,
        preflight = AgentCoordinationPreflightChecker(preflight::check),
        claimer = AgentCoordinationParticipantAttemptClaimer { participant, autonomy ->
            agentGate.claimAttempt(
                agentId = participant.id,
                agentGeneration = participant.generation,
                autonomyProposalId = autonomy.proposalId,
                autonomyGeneration = autonomy.generation
            )
        },
        autonomyGate = autonomyGate,
        attemptBindings = AgentCoordinationAttemptBindingInstaller(attemptBindings::install)
    )

    internal constructor(
        foundation: FoundationComposition,
        bindings: AgentCoordinationWorkBindingComposition,
        preflight: AgentCoordinationPreflightChecker,
        claimer: AgentCoordinationParticipantAttemptClaimer,
        autonomyGate: ControlledAutonomyDeliberationGate,
        attemptBindings: AgentCoordinationAttemptBindingInstaller,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit
    ) : this(foundation, bindings, preflight, claimer, autonomyGate, attemptBindings)

    fun claimAttempts(
        coordinationId: AgentCoordinationId,
        coordinationGeneration: AgentCoordinationGeneration
    ): AgentCoordinationAttemptResult {
        val exactCoordination = ExactAgentCoordinationReference(
            id = coordinationId,
            generation = coordinationGeneration
        )
        val context = foundation.rootContext(
            operation = "claimAgentCoordinationAttempts",
            component = "AgentCoordination",
            metadata = coordinationMetadata(exactCoordination)
        )

        val binding = bindings.find(exactCoordination)
            ?: return reject("coordination work binding is not live", context)

        val initial = when (val checked = check(exactCoordination)) {
            is AgentCoordinationPreflightResult.Ready -> checked.evidence
            is AgentCoordinationPreflightResult.Rejected ->
                return reject("coordination preflight rejected: ${checked.reason}", context)
        }
        if (!matches(binding, initial)) {
            return reject("coordination work binding does not match live coordination evidence", context)
        }
        if (bindings.find(exactCoordination) != binding) {
            return reject("coordination work binding changed before attempt transaction", context)
        }

        val claimed = mutableListOf<AgentCoordinationParticipantAttempt>()
        binding.assignments.forEach { assignment ->
            val before = when (val checked = check(exactCoordination)) {
                is AgentCoordinationPreflightResult.Ready -> checked.evidence
                is AgentCoordinationPreflightResult.Rejected -> return invalidateAfterClaim(
                    exactCoordination,
                    claimed,
                    "coordination changed before participant claim: ${checked.reason}",
                    context
                )
            }
            if (!matches(binding, before) || bindings.find(exactCoordination) != binding) {
                return invalidateAfterClaim(
                    exactCoordination,
                    claimed,
                    "coordination governance changed before participant claim",
                    context
                )
            }

            val participantClaim = when (
                val result = claimer.claim(assignment.participant, assignment.autonomy)
            ) {
                is AgentInitiativeAttemptResult.Claimed -> result
                is AgentInitiativeAttemptResult.Rejected -> return invalidateAfterClaim(
                    exactCoordination,
                    claimed,
                    "participant attempt rejected: ${result.reason}",
                    context
                )
            }

            claimed += AgentCoordinationParticipantAttempt(
                participant = assignment.participant,
                autonomy = assignment.autonomy,
                attemptNumber = participantClaim.attempt.evidence.attemptNumber
            )

            val after = when (val checked = check(exactCoordination)) {
                is AgentCoordinationPreflightResult.Ready -> checked.evidence
                is AgentCoordinationPreflightResult.Rejected -> return invalidateAfterClaim(
                    exactCoordination,
                    claimed,
                    "coordination changed during participant claim: ${checked.reason}",
                    context
                )
            }
            if (!matches(binding, after) || bindings.find(exactCoordination) != binding) {
                return invalidateAfterClaim(
                    exactCoordination,
                    claimed,
                    "coordination governance changed during participant claim",
                    context
                )
            }
        }

        val confirmed = when (val checked = check(exactCoordination)) {
            is AgentCoordinationPreflightResult.Ready -> checked.evidence
            is AgentCoordinationPreflightResult.Rejected -> return invalidateAfterClaim(
                exactCoordination,
                claimed,
                "coordination changed after participant claims: ${checked.reason}",
                context
            )
        }
        if (!matches(binding, confirmed) || bindings.find(exactCoordination) != binding) {
            return invalidateAfterClaim(
                exactCoordination,
                claimed,
                "coordination governance changed before attempt binding commit",
                context
            )
        }

        val attemptBinding = AgentCoordinationAttemptBinding(
            coordination = exactCoordination,
            assignments = claimed.map { attempt ->
                AgentCoordinationAttemptAssignment(
                    participant = attempt.participant,
                    attempt = attempt.exactAttempt()
                )
            }
        )

        val bindingOwnership = when (val installed = attemptBindings.install(attemptBinding)) {
            is AgentCoordinationAttemptBindingInstallResult.Installed -> installed.ownership
            is AgentCoordinationAttemptBindingInstallResult.Rejected -> return invalidateAfterClaim(
                exactCoordination,
                claimed,
                "coordination attempt binding rejected: ${installed.reason}",
                context
            )
        }

        val receipt = AgentCoordinationAttemptReceipt(
            coordination = exactCoordination,
            attempts = claimed,
            attemptBindingGeneration = bindingOwnership.generation
        )
        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_COORDINATION_ATTEMPTS_CLAIMED",
            message = "coordination participant attempts claimed and structurally bound",
            context = context,
            metadata = receiptMetadata(receipt)
        )
        return AgentCoordinationAttemptResult.Claimed(receipt)
    }

    private fun check(
        coordination: ExactAgentCoordinationReference
    ): AgentCoordinationPreflightResult = preflight.check(
        AgentCoordinationPreflightRequest(
            coordinationId = coordination.id,
            coordinationGeneration = coordination.generation
        )
    )

    private fun matches(
        binding: AgentCoordinationWorkBinding,
        evidence: AgentCoordinationReadyEvidence
    ): Boolean =
        evidence.coordinationId == binding.coordination.id &&
            evidence.coordinationGeneration == binding.coordination.generation &&
            evidence.participants == binding.assignments.map { it.participant }

    private fun invalidateAfterClaim(
        coordination: ExactAgentCoordinationReference,
        claimed: List<AgentCoordinationParticipantAttempt>,
        reason: String,
        context: pro.liliya.core.logging.LogContext
    ): AgentCoordinationAttemptResult {
        var compensationFailed = false
        claimed.asReversed().forEach { attempt ->
            when (autonomyGate.cancel(attempt.autonomy.proposalId, attempt.autonomy.generation)) {
                AutonomyDeliberationCancellationResult.Cancelled -> Unit
                is AutonomyDeliberationCancellationResult.Rejected -> {
                    val validation = autonomyGate.validateAttempt(attempt.exactAttempt())
                    if (validation is AutonomyDeliberationAttemptValidationResult.Valid) {
                        compensationFailed = true
                    }
                }
            }
        }

        if (compensationFailed) {
            foundation.observability.record(
                severity = DiagnosticSeverity.CRITICAL,
                code = "AGENT_COORDINATION_ATTEMPT_COMPENSATION_FAILED",
                message = "coordination attempt compensation failed",
                context = context,
                metadata = coordinationMetadata(coordination) + mapOf(
                    "claimedCount" to claimed.size.toString(),
                    "failureReason" to reason
                )
            )
            return AgentCoordinationAttemptResult.Failed(
                "coordination attempt compensation failed after: $reason"
            )
        }

        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_COORDINATION_ATTEMPTS_COMPENSATED",
            message = "coordination participant attempts invalidated",
            context = context,
            metadata = coordinationMetadata(coordination) + mapOf(
                "claimedCount" to claimed.size.toString(),
                "compensationReason" to reason
            )
        )
        return AgentCoordinationAttemptResult.Rejected(reason)
    }

    private fun reject(
        reason: String,
        context: pro.liliya.core.logging.LogContext
    ): AgentCoordinationAttemptResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_COORDINATION_ATTEMPT_REJECTED",
            message = reason,
            context = context,
            metadata = mapOf("rejectionReason" to reason)
        )
        return AgentCoordinationAttemptResult.Rejected(reason)
    }

    private fun coordinationMetadata(
        coordination: ExactAgentCoordinationReference
    ): Map<String, String> = mapOf(
        "agentCoordinationId" to coordination.id.value,
        "agentCoordinationGeneration" to coordination.generation.value.toString()
    )

    private fun receiptMetadata(receipt: AgentCoordinationAttemptReceipt): Map<String, String> = buildMap {
        putAll(coordinationMetadata(receipt.coordination))
        put("attemptBindingGeneration", receipt.attemptBindingGeneration.value.toString())
        put("attemptCount", receipt.attempts.size.toString())
        receipt.attempts.forEachIndexed { index, attempt ->
            put("attempt${index}AgentId", attempt.participant.id.value)
            put("attempt${index}AgentGeneration", attempt.participant.generation.value.toString())
            put("attempt${index}AutonomyProposalId", attempt.autonomy.proposalId.value)
            put("attempt${index}AutonomyGeneration", attempt.autonomy.generation.value.toString())
            put("attempt${index}Number", attempt.attemptNumber.toString())
        }
    }
}
