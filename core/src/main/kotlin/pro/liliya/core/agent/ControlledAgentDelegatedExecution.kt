package pro.liliya.core.agent

import pro.liliya.core.autonomy.AutonomyDeliberationComposition
import pro.liliya.core.autonomy.ControlledAutonomyExecutionRequest
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

sealed interface ControlledAgentDelegatedExecutionResult {
    data object Succeeded : ControlledAgentDelegatedExecutionResult

    data class Rejected(val reason: String) : ControlledAgentDelegatedExecutionResult {
        init { require(reason.isNotBlank()) { "delegated agent execution rejection reason must not be blank" } }
    }

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : ControlledAgentDelegatedExecutionResult {
        init { require(reason.isNotBlank()) { "delegated agent execution failure reason must not be blank" } }
    }
}

internal fun interface AgentControlledExecutionDelegate {
    fun execute(request: ControlledAutonomyExecutionRequest): ControlledAgentExecutionResult
}

/**
 * Final delegation/binding/lifecycle guard before the already-frozen Controlled Agent execution
 * boundary.
 *
 * Exact Autonomy identity is derived from the exact live deliberation request, never supplied as
 * unrelated side data. That exact Autonomy generation resolves the structural delegated-work
 * binding, whose exact delegation generation and parent/child ACTIVE lifecycle are freshly checked
 * immediately before delegation to ControlledAgentExecution.
 *
 * This layer grants no permission and performs no Authority or Execution directly. The delegated
 * ControlledAgentExecution still revalidates child Agent provenance/lifecycle and then the frozen
 * Controlled Autonomy/Orchestration/Authority/Execution chain performs its own downstream checks.
 */
class ControlledAgentDelegatedExecution private constructor(
    private val foundation: FoundationComposition,
    private val deliberation: AutonomyDeliberationComposition,
    private val bindings: AgentDelegatedWorkBindingComposition,
    private val preflight: AgentDelegationPreflightChecker,
    private val delegate: AgentControlledExecutionDelegate
) {
    constructor(
        foundation: FoundationComposition,
        deliberation: AutonomyDeliberationComposition,
        bindings: AgentDelegatedWorkBindingComposition,
        preflight: ControlledAgentDelegationPreflight,
        controlledAgent: ControlledAgentExecution
    ) : this(
        foundation = foundation,
        deliberation = deliberation,
        bindings = bindings,
        preflight = AgentDelegationPreflightChecker(preflight::check),
        delegate = AgentControlledExecutionDelegate(controlledAgent::execute)
    )

    internal constructor(
        foundation: FoundationComposition,
        deliberation: AutonomyDeliberationComposition,
        bindings: AgentDelegatedWorkBindingComposition,
        preflight: AgentDelegationPreflightChecker,
        delegate: AgentControlledExecutionDelegate,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit
    ) : this(foundation, deliberation, bindings, preflight, delegate)

    fun execute(request: ControlledAutonomyExecutionRequest): ControlledAgentDelegatedExecutionResult {
        val deliberationSnapshot = deliberation.inspect(request.deliberationRequestId)
            ?: return reject(request, "autonomy deliberation request is not live")
        if (deliberationSnapshot.generation != request.deliberationGeneration) {
            return reject(request, "autonomy deliberation request generation is stale")
        }

        val autonomyReference = ExactAutonomyReference(
            proposalId = deliberationSnapshot.request.autonomy.proposalId,
            generation = deliberationSnapshot.request.autonomy.proposalGeneration
        )
        val binding = bindings.find(autonomyReference)
            ?: return reject(request, "delegated work binding is not live")

        val evidence = when (
            val checked = preflight.check(
                AgentDelegationPreflightRequest(
                    delegationId = binding.delegation.id,
                    delegationGeneration = binding.delegation.generation
                )
            )
        ) {
            is AgentDelegationPreflightResult.Ready -> checked.evidence
            is AgentDelegationPreflightResult.Rejected ->
                return reject(request, "delegation preflight rejected: ${checked.reason}")
        }

        if (!matches(binding, evidence)) {
            return reject(request, "delegated work binding does not match live delegation evidence")
        }
        if (bindings.find(autonomyReference) != binding) {
            return reject(request, "delegated work binding changed before execution")
        }

        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_DELEGATED_EXECUTION_GUARD_PASSED",
            message = "delegated execution governance passed",
            context = foundation.rootContext(
                operation = "executeAgentDelegatedWork",
                component = "AgentDelegation",
                metadata = metadata(request, binding)
            ),
            metadata = metadata(request, binding)
        )

        return when (val result = delegate.execute(request)) {
            ControlledAgentExecutionResult.Succeeded -> ControlledAgentDelegatedExecutionResult.Succeeded
            is ControlledAgentExecutionResult.Rejected ->
                ControlledAgentDelegatedExecutionResult.Rejected(result.reason)
            is ControlledAgentExecutionResult.Failed ->
                ControlledAgentDelegatedExecutionResult.Failed(result.reason, result.throwable)
        }
    }

    private fun matches(
        binding: AgentDelegatedWorkBinding,
        evidence: AgentDelegationReadyEvidence
    ): Boolean =
        evidence.delegationId == binding.delegation.id &&
            evidence.delegationGeneration == binding.delegation.generation &&
            evidence.child == binding.child

    private fun reject(
        request: ControlledAutonomyExecutionRequest,
        reason: String
    ): ControlledAgentDelegatedExecutionResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_DELEGATED_EXECUTION_REJECTED",
            message = reason,
            context = foundation.rootContext(
                operation = "executeAgentDelegatedWork",
                component = "AgentDelegation",
                metadata = requestMetadata(request)
            ),
            metadata = mapOf("rejectionReason" to reason)
        )
        return ControlledAgentDelegatedExecutionResult.Rejected(reason)
    }

    private fun requestMetadata(request: ControlledAutonomyExecutionRequest): Map<String, String> = mapOf(
        "autonomyDeliberationRequestId" to request.deliberationRequestId.value,
        "autonomyDeliberationGeneration" to request.deliberationGeneration.value.toString(),
        "planningProposalId" to request.planningProposalId.value,
        "planningGeneration" to request.planningGeneration.value.toString(),
        "reasoningArtifactId" to request.reasoningArtifactId.value,
        "reasoningGeneration" to request.reasoningGeneration.value.toString(),
        "decisionId" to request.decisionId.value,
        "decisionGeneration" to request.decisionGeneration.value.toString(),
        "orchestrationIntentId" to request.orchestrationIntentId.value,
        "orchestrationGeneration" to request.orchestrationGeneration.value.toString(),
        "actionId" to request.actionId.value
    )

    private fun metadata(
        request: ControlledAutonomyExecutionRequest,
        binding: AgentDelegatedWorkBinding
    ): Map<String, String> = requestMetadata(request) + mapOf(
        "agentDelegationId" to binding.delegation.id.value,
        "agentDelegationGeneration" to binding.delegation.generation.value.toString(),
        "childAgentId" to binding.child.id.value,
        "childAgentGeneration" to binding.child.generation.value.toString(),
        "autonomyProposalId" to binding.autonomy.proposalId.value,
        "autonomyGeneration" to binding.autonomy.generation.value.toString()
    )
}
