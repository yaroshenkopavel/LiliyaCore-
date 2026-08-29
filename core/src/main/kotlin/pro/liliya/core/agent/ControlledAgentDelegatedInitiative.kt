package pro.liliya.core.agent

import java.time.Instant
import pro.liliya.core.autonomy.AutonomyBudget
import pro.liliya.core.autonomy.AutonomyOwnership
import pro.liliya.core.autonomy.AutonomyPriority
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.foundation.FoundationComposition

data class AgentDelegatedInitiativeRequest(
    val delegationId: AgentDelegationId,
    val delegationGeneration: AgentDelegationGeneration,
    val autonomyProposalId: AutonomyProposalId,
    val objective: String,
    val triggerDescription: String,
    val priority: AutonomyPriority,
    val budget: AutonomyBudget,
    val createdAt: Instant
) {
    init {
        require(objective.isNotBlank()) { "delegated initiative objective must not be blank" }
        require(triggerDescription.isNotBlank()) { "delegated initiative trigger description must not be blank" }
    }
}

interface AgentDelegatedInitiativeOwnership {
    val autonomy: AutonomyOwnership
    val binding: AgentDelegatedWorkBindingOwnership
}

sealed interface AgentDelegatedInitiativeResult {
    data class Created(
        val ownership: AgentDelegatedInitiativeOwnership
    ) : AgentDelegatedInitiativeResult

    data class Rejected(val reason: String) : AgentDelegatedInitiativeResult {
        init { require(reason.isNotBlank()) { "delegated initiative rejection reason must not be blank" } }
    }
}

/**
 * Compensated creation boundary for delegation-originated Agent work.
 *
 * The bridge revalidates exact delegation + parent/child ACTIVE lifecycle before creation, creates
 * an ordinary child Agent initiative through the already-frozen Agent boundary, revalidates the
 * delegation again, and finally installs an exact delegation↔Autonomy binding. If post-create
 * revalidation or binding fails, the exact newly-created Autonomy ownership is removed before a
 * rejection is returned.
 *
 * The binding remains structural evidence only and grants no permission or execution right.
 */
class ControlledAgentDelegatedInitiative(
    private val foundation: FoundationComposition,
    private val preflight: ControlledAgentDelegationPreflight,
    private val childInitiative: ControlledAgentInitiative,
    private val bindings: AgentDelegatedWorkBindingComposition
) {
    fun create(request: AgentDelegatedInitiativeRequest): AgentDelegatedInitiativeResult {
        val initial = when (
            val checked = preflight.check(
                AgentDelegationPreflightRequest(
                    delegationId = request.delegationId,
                    delegationGeneration = request.delegationGeneration
                )
            )
        ) {
            is AgentDelegationPreflightResult.Ready -> checked.evidence
            is AgentDelegationPreflightResult.Rejected ->
                return AgentDelegatedInitiativeResult.Rejected(
                    "delegation preflight rejected: ${checked.reason}"
                )
        }

        val created = when (
            val result = childInitiative.create(
                AgentInitiativeRequest(
                    agentId = initial.child.id,
                    agentGeneration = initial.child.generation,
                    autonomyProposalId = request.autonomyProposalId,
                    objective = request.objective,
                    triggerDescription = request.triggerDescription,
                    priority = request.priority,
                    budget = request.budget,
                    createdAt = request.createdAt
                )
            )
        ) {
            is AgentInitiativeResult.Created -> result.ownership
            is AgentInitiativeResult.Rejected ->
                return AgentDelegatedInitiativeResult.Rejected(
                    "child initiative rejected: ${result.reason}"
                )
        }

        val confirmed = when (
            val checked = preflight.check(
                AgentDelegationPreflightRequest(
                    delegationId = request.delegationId,
                    delegationGeneration = request.delegationGeneration
                )
            )
        ) {
            is AgentDelegationPreflightResult.Ready -> checked.evidence
            is AgentDelegationPreflightResult.Rejected -> {
                created.remove()
                return AgentDelegatedInitiativeResult.Rejected(
                    "delegation changed during initiative creation: ${checked.reason}"
                )
            }
        }

        if (
            confirmed.delegationId != initial.delegationId ||
            confirmed.delegationGeneration != initial.delegationGeneration ||
            confirmed.parent != initial.parent ||
            confirmed.child != initial.child
        ) {
            created.remove()
            return AgentDelegatedInitiativeResult.Rejected(
                "delegation evidence changed during initiative creation"
            )
        }

        val binding = AgentDelegatedWorkBinding(
            delegation = ExactAgentDelegationReference(
                id = confirmed.delegationId,
                generation = confirmed.delegationGeneration
            ),
            child = confirmed.child,
            autonomy = ExactAutonomyReference(
                proposalId = created.proposal.id,
                generation = created.generation
            )
        )

        return when (val installed = bindings.install(binding)) {
            is AgentDelegatedWorkBindingInstallResult.Installed -> {
                foundation.observability.record(
                    severity = pro.liliya.core.diagnostics.DiagnosticSeverity.INFO,
                    code = "AGENT_DELEGATED_INITIATIVE_CREATED",
                    message = "delegated initiative created and structurally bound",
                    context = foundation.rootContext(
                        operation = "createAgentDelegatedInitiative",
                        component = "AgentDelegation",
                        metadata = metadata(binding)
                    ),
                    metadata = metadata(binding)
                )
                AgentDelegatedInitiativeResult.Created(
                    ownership = object : AgentDelegatedInitiativeOwnership {
                        override val autonomy: AutonomyOwnership = created
                        override val binding: AgentDelegatedWorkBindingOwnership = installed.ownership
                    }
                )
            }

            is AgentDelegatedWorkBindingInstallResult.Rejected -> {
                created.remove()
                AgentDelegatedInitiativeResult.Rejected(
                    "delegated work binding rejected: ${installed.reason}"
                )
            }
        }
    }

    private fun metadata(binding: AgentDelegatedWorkBinding): Map<String, String> = mapOf(
        "agentDelegationId" to binding.delegation.id.value,
        "agentDelegationGeneration" to binding.delegation.generation.value.toString(),
        "childAgentId" to binding.child.id.value,
        "childAgentGeneration" to binding.child.generation.value.toString(),
        "autonomyProposalId" to binding.autonomy.proposalId.value,
        "autonomyGeneration" to binding.autonomy.generation.value.toString()
    )
}
