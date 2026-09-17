package pro.liliya.core.autonomy

import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityRequest
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityId

/**
 * Identity-only provenance carried across the Goal -> Proposal -> Authority boundary.
 *
 * No objective text, trigger text, memory content, reasoning content or model output can be stored
 * here. The identity is evidence for an Authority request; it is not a grant or execution permit.
 */
data class AutonomyGoalAuthorityIdentity(
    val goalId: AutonomyGoalId,
    val proposalId: AutonomyGoalProposalId,
    val scope: AutonomyGoalScope,
    val actionClass: AutonomyActionClass
)

enum class AutonomyGoalAuthorityRequestFailure {
    ACTION_CLASS_NOT_MAPPED
}

sealed interface AutonomyGoalAuthorityRequestResult {
    data class Created(
        val identity: AutonomyGoalAuthorityIdentity,
        val request: AuthorityRequest
    ) : AutonomyGoalAuthorityRequestResult

    data class Rejected(
        val failure: AutonomyGoalAuthorityRequestFailure
    ) : AutonomyGoalAuthorityRequestResult
}

/**
 * Converts a deliberation selection into an identity-only request for the existing Authority
 * boundary. This factory never authorizes the request and never executes the proposal.
 */
class AutonomyGoalAuthorityRequestFactory(
    capabilityByActionClass: Map<AutonomyActionClass, CapabilityId>
) {
    private val capabilities = capabilityByActionClass.toMap()

    init {
        require(capabilities.isNotEmpty()) { "autonomy Authority mapping must not be empty" }
    }

    fun create(
        selected: AutonomyGoalDeliberationResult.Selected,
        principal: AuthorityPrincipal
    ): AutonomyGoalAuthorityRequestResult {
        val proposal = selected.proposal
        val capability = capabilities[proposal.actionClass]
            ?: return AutonomyGoalAuthorityRequestResult.Rejected(
                AutonomyGoalAuthorityRequestFailure.ACTION_CLASS_NOT_MAPPED
            )
        val identity = AutonomyGoalAuthorityIdentity(
            goalId = proposal.goalId,
            proposalId = proposal.id,
            scope = proposal.scope,
            actionClass = proposal.actionClass
        )
        val request = AuthorityRequest(
            principal = principal,
            capability = capability,
            reason = structuralReason(identity),
            scope = AuthorityScope(identity.scope.value)
        )
        return AutonomyGoalAuthorityRequestResult.Created(identity, request)
    }

    private fun structuralReason(identity: AutonomyGoalAuthorityIdentity): String =
        "bounded-autonomy:" +
            "goal=${identity.goalId.value};" +
            "proposal=${identity.proposalId.value};" +
            "actionClass=${identity.actionClass.value}"
}
