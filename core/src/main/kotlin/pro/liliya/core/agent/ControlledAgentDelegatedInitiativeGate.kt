package pro.liliya.core.agent

import pro.liliya.core.autonomy.AutonomyDeliberationCancellationResult
import pro.liliya.core.autonomy.AutonomyGeneration
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.autonomy.ControlledAutonomyDeliberationGate
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

sealed interface AgentDelegatedInitiativeAttemptResult {
    data class Claimed(
        val attempt: AgentInitiativeAttemptResult.Claimed
    ) : AgentDelegatedInitiativeAttemptResult

    data class Rejected(val reason: String) : AgentDelegatedInitiativeAttemptResult {
        init {
            require(reason.isNotBlank()) { "delegated initiative attempt rejection reason must not be blank" }
        }
    }
}

/**
 * Fresh delegated-governance gate around one bounded child-Agent Autonomy attempt claim.
 *
 * The exact delegation↔Autonomy binding and exact parent/child ACTIVE delegation preflight are
 * checked before the claim and checked again immediately after it. The second validation closes
 * the mutable binding/delegation/lifecycle TOCTOU window around the frozen Agent/Autonomy claim.
 *
 * If governance changes after the claim, the claim is never returned to the caller and the exact
 * Autonomy generation is cancelled before a rejection is returned. This may consume one bounded
 * attempt in a race, but it cannot produce a valid downstream deliberation chain.
 *
 * This gate schedules nothing and grants no permission, Authority or Execution right.
 */
class ControlledAgentDelegatedInitiativeGate private constructor(
    private val foundation: FoundationComposition,
    private val bindings: AgentDelegatedWorkBindingComposition,
    private val preflight: AgentDelegationPreflightChecker,
    private val agentGate: ControlledAgentInitiativeGate,
    private val autonomyGate: ControlledAutonomyDeliberationGate
) {
    constructor(
        foundation: FoundationComposition,
        bindings: AgentDelegatedWorkBindingComposition,
        preflight: ControlledAgentDelegationPreflight,
        agentGate: ControlledAgentInitiativeGate,
        autonomyGate: ControlledAutonomyDeliberationGate
    ) : this(
        foundation = foundation,
        bindings = bindings,
        preflight = AgentDelegationPreflightChecker(preflight::check),
        agentGate = agentGate,
        autonomyGate = autonomyGate
    )

    internal constructor(
        foundation: FoundationComposition,
        bindings: AgentDelegatedWorkBindingComposition,
        preflight: AgentDelegationPreflightChecker,
        agentGate: ControlledAgentInitiativeGate,
        autonomyGate: ControlledAutonomyDeliberationGate,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit
    ) : this(foundation, bindings, preflight, agentGate, autonomyGate)

    fun claimAttempt(
        autonomyProposalId: AutonomyProposalId,
        autonomyGeneration: AutonomyGeneration
    ): AgentDelegatedInitiativeAttemptResult {
        val autonomy = ExactAutonomyReference(autonomyProposalId, autonomyGeneration)
        val context = foundation.rootContext(
            operation = "claimAgentDelegatedInitiativeAttempt",
            component = "AgentDelegation",
            metadata = autonomyMetadata(autonomy)
        )

        val binding = bindings.find(autonomy)
            ?: return reject("delegated work binding is not live", context)

        val initial = when (val checked = check(binding)) {
            is AgentDelegationPreflightResult.Ready -> checked.evidence
            is AgentDelegationPreflightResult.Rejected ->
                return reject("delegation preflight rejected: ${checked.reason}", context)
        }
        if (!matches(binding, initial)) {
            return reject("delegated work binding does not match live delegation evidence", context)
        }
        if (bindings.find(autonomy) != binding) {
            return reject("delegated work binding changed before attempt claim", context)
        }

        val claimed = when (
            val result = agentGate.claimAttempt(
                agentId = binding.child.id,
                agentGeneration = binding.child.generation,
                autonomyProposalId = autonomyProposalId,
                autonomyGeneration = autonomyGeneration
            )
        ) {
            is AgentInitiativeAttemptResult.Claimed -> result
            is AgentInitiativeAttemptResult.Rejected ->
                return reject("child agent attempt rejected: ${result.reason}", context)
        }

        val currentBinding = bindings.find(autonomy)
        if (currentBinding != binding) {
            return cancelAfterClaim(
                autonomy = autonomy,
                context = context,
                reason = "delegated work binding changed during attempt claim"
            )
        }

        val confirmed = when (val checked = check(binding)) {
            is AgentDelegationPreflightResult.Ready -> checked.evidence
            is AgentDelegationPreflightResult.Rejected ->
                return cancelAfterClaim(
                    autonomy = autonomy,
                    context = context,
                    reason = "delegation changed during attempt claim: ${checked.reason}"
                )
        }
        if (!matches(binding, confirmed)) {
            return cancelAfterClaim(
                autonomy = autonomy,
                context = context,
                reason = "delegation evidence changed during attempt claim"
            )
        }

        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_DELEGATED_INITIATIVE_ATTEMPT_CLAIMED",
            message = "delegated initiative attempt claimed",
            context = context,
            metadata = bindingMetadata(binding) + mapOf(
                "autonomyAttemptNumber" to claimed.attempt.evidence.attemptNumber.toString()
            )
        )
        return AgentDelegatedInitiativeAttemptResult.Claimed(claimed)
    }

    private fun check(binding: AgentDelegatedWorkBinding): AgentDelegationPreflightResult =
        preflight.check(
            AgentDelegationPreflightRequest(
                delegationId = binding.delegation.id,
                delegationGeneration = binding.delegation.generation
            )
        )

    private fun matches(
        binding: AgentDelegatedWorkBinding,
        evidence: AgentDelegationReadyEvidence
    ): Boolean =
        evidence.delegationId == binding.delegation.id &&
            evidence.delegationGeneration == binding.delegation.generation &&
            evidence.child == binding.child

    private fun cancelAfterClaim(
        autonomy: ExactAutonomyReference,
        context: pro.liliya.core.logging.LogContext,
        reason: String
    ): AgentDelegatedInitiativeAttemptResult.Rejected {
        val cancellation = autonomyGate.cancel(autonomy.proposalId, autonomy.generation)
        val cancellationState = when (cancellation) {
            AutonomyDeliberationCancellationResult.Cancelled -> "cancelled"
            is AutonomyDeliberationCancellationResult.Rejected -> "already_not_cancellable"
        }
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_DELEGATED_INITIATIVE_ATTEMPT_COMPENSATED",
            message = "delegated initiative attempt invalidated after claim",
            context = context,
            metadata = autonomyMetadata(autonomy) + mapOf(
                "compensationState" to cancellationState,
                "compensationReason" to reason
            )
        )
        return AgentDelegatedInitiativeAttemptResult.Rejected(reason)
    }

    private fun reject(
        reason: String,
        context: pro.liliya.core.logging.LogContext
    ): AgentDelegatedInitiativeAttemptResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_DELEGATED_INITIATIVE_ATTEMPT_REJECTED",
            message = reason,
            context = context,
            metadata = mapOf("rejectionReason" to reason)
        )
        return AgentDelegatedInitiativeAttemptResult.Rejected(reason)
    }

    private fun autonomyMetadata(autonomy: ExactAutonomyReference): Map<String, String> = mapOf(
        "autonomyProposalId" to autonomy.proposalId.value,
        "autonomyGeneration" to autonomy.generation.value.toString()
    )

    private fun bindingMetadata(binding: AgentDelegatedWorkBinding): Map<String, String> = mapOf(
        "agentDelegationId" to binding.delegation.id.value,
        "agentDelegationGeneration" to binding.delegation.generation.value.toString(),
        "childAgentId" to binding.child.id.value,
        "childAgentGeneration" to binding.child.generation.value.toString(),
        "autonomyProposalId" to binding.autonomy.proposalId.value,
        "autonomyGeneration" to binding.autonomy.generation.value.toString()
    )
}
