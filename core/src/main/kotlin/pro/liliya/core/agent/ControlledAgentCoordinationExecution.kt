package pro.liliya.core.agent

import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.autonomy.AutonomyDeliberationGeneration
import pro.liliya.core.autonomy.AutonomyDeliberationRequestId
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.decision.DecisionInputReference
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.execution.ExecutionActionId
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.orchestration.ControlledOrchestrationExecution
import pro.liliya.core.orchestration.ControlledOrchestrationExecutionResult
import pro.liliya.core.orchestration.OrchestrationComposition
import pro.liliya.core.orchestration.OrchestrationExecutionPreflightRequest
import pro.liliya.core.orchestration.OrchestrationGeneration
import pro.liliya.core.orchestration.OrchestrationIntentId
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.reasoning.ReasoningGeneration

data class AgentCoordinationExecutionRequest(
    val deliberationRequestId: AutonomyDeliberationRequestId,
    val deliberationGeneration: AutonomyDeliberationGeneration,
    val planningProposalId: PlanningProposalId,
    val planningGeneration: PlanningGeneration,
    val reasoningArtifactId: ReasoningArtifactId,
    val reasoningGeneration: ReasoningGeneration,
    val decisionId: DecisionId,
    val decisionGeneration: DecisionGeneration,
    val orchestrationIntentId: OrchestrationIntentId,
    val orchestrationGeneration: OrchestrationGeneration,
    val principal: AuthorityPrincipal,
    val actionId: ExecutionActionId
)

sealed interface AgentCoordinationExecutionResult {
    data object Succeeded : AgentCoordinationExecutionResult

    data class Rejected(val reason: String) : AgentCoordinationExecutionResult {
        init { require(reason.isNotBlank()) { "coordination execution rejection reason must not be blank" } }
    }

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : AgentCoordinationExecutionResult {
        init { require(reason.isNotBlank()) { "coordination execution failure reason must not be blank" } }
    }
}

internal fun interface AgentCoordinationExecutionDelegate {
    fun execute(request: OrchestrationExecutionPreflightRequest): ControlledOrchestrationExecutionResult
}

/**
 * Final coordination-specific governance guard before frozen Controlled Orchestration execution.
 *
 * Coordination evidence and the exact Planning -> Reasoning -> Decision -> Orchestration chain are
 * revalidated immediately before delegation. Structural provenance is consistency evidence only;
 * it grants no permission. This layer creates no Authority grant and performs no Execution itself.
 * The delegated ControlledOrchestrationExecution performs fresh orchestration preflight, fresh
 * Authority authorization and the frozen Execution boundary independently.
 */
class ControlledAgentCoordinationExecution private constructor(
    private val foundation: FoundationComposition,
    private val preflight: AgentCoordinationDeliberationPreflightChecker,
    private val planning: PlanningComposition,
    private val reasoning: ReasoningComposition,
    private val decisions: DecisionComposition,
    private val orchestration: OrchestrationComposition,
    private val delegate: AgentCoordinationExecutionDelegate
) {
    constructor(
        foundation: FoundationComposition,
        preflight: ControlledAgentCoordinationDeliberationPreflight,
        planning: PlanningComposition,
        reasoning: ReasoningComposition,
        decisions: DecisionComposition,
        orchestration: OrchestrationComposition,
        controlledOrchestration: ControlledOrchestrationExecution
    ) : this(
        foundation = foundation,
        preflight = AgentCoordinationDeliberationPreflightChecker(preflight::check),
        planning = planning,
        reasoning = reasoning,
        decisions = decisions,
        orchestration = orchestration,
        delegate = AgentCoordinationExecutionDelegate(controlledOrchestration::execute)
    )

    internal constructor(
        foundation: FoundationComposition,
        preflight: AgentCoordinationDeliberationPreflightChecker,
        planning: PlanningComposition,
        reasoning: ReasoningComposition,
        decisions: DecisionComposition,
        orchestration: OrchestrationComposition,
        delegate: AgentCoordinationExecutionDelegate,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit
    ) : this(foundation, preflight, planning, reasoning, decisions, orchestration, delegate)

    fun execute(request: AgentCoordinationExecutionRequest): AgentCoordinationExecutionResult {
        val initial = when (
            val checked = preflight.check(request.deliberationRequestId, request.deliberationGeneration)
        ) {
            is AgentCoordinationDeliberationPreflightResult.Ready -> checked.evidence
            is AgentCoordinationDeliberationPreflightResult.Rejected -> return reject(
                request,
                "coordination deliberation preflight rejected: ${checked.reason}"
            )
        }

        val chainError = validateChain(request, initial)
        if (chainError != null) return reject(request, chainError)

        val confirmed = when (
            val checked = preflight.check(request.deliberationRequestId, request.deliberationGeneration)
        ) {
            is AgentCoordinationDeliberationPreflightResult.Ready -> checked.evidence
            is AgentCoordinationDeliberationPreflightResult.Rejected -> return reject(
                request,
                "coordination changed before execution: ${checked.reason}"
            )
        }
        if (!sameReadiness(initial, confirmed)) {
            return reject(request, "coordination readiness changed before execution")
        }

        val confirmedChainError = validateChain(request, confirmed)
        if (confirmedChainError != null) {
            return reject(request, "cognitive/orchestration chain changed before execution: $confirmedChainError")
        }

        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_COORDINATION_EXECUTION_GUARD_PASSED",
            message = "coordinated execution governance passed",
            context = foundation.rootContext(
                operation = "executeAgentCoordination",
                component = "AgentCoordination",
                metadata = metadata(request, confirmed)
            ),
            metadata = metadata(request, confirmed)
        )

        return when (
            val result = delegate.execute(
                OrchestrationExecutionPreflightRequest(
                    intentId = request.orchestrationIntentId,
                    generation = request.orchestrationGeneration,
                    principal = request.principal,
                    actionId = request.actionId
                )
            )
        ) {
            ControlledOrchestrationExecutionResult.Succeeded -> AgentCoordinationExecutionResult.Succeeded
            is ControlledOrchestrationExecutionResult.Rejected ->
                AgentCoordinationExecutionResult.Rejected(result.reason)
            is ControlledOrchestrationExecutionResult.Failed ->
                AgentCoordinationExecutionResult.Failed(result.reason, result.throwable)
        }
    }

    private fun validateChain(
        request: AgentCoordinationExecutionRequest,
        evidence: AgentCoordinationDeliberationReadyEvidence
    ): String? {
        val planningSnapshot = planning.inspect(request.planningProposalId)
            ?: return "planning proposal is not live"
        if (planningSnapshot.generation != request.planningGeneration) {
            return "planning proposal generation is stale"
        }
        if (
            planningSnapshot.proposal.origin.sourceId.value != "agent-coordination-deliberation" ||
            planningSnapshot.proposal.origin.sourceReference?.value != planningReference(evidence)
        ) {
            return "planning proposal provenance does not match coordinated deliberation"
        }

        val reasoningSnapshot = reasoning.inspect(request.reasoningArtifactId)
            ?: return "reasoning artifact is not live"
        if (reasoningSnapshot.generation != request.reasoningGeneration) {
            return "reasoning artifact generation is stale"
        }
        if (
            reasoningSnapshot.artifact.origin.sourceId.value != "agent-coordination-planning" ||
            reasoningSnapshot.artifact.origin.sourceReference?.value != reasoningReference(evidence, request)
        ) {
            return "reasoning artifact provenance does not match coordinated planning"
        }

        val decisionSnapshot = decisions.inspect(request.decisionId)
            ?: return "decision is not live"
        if (decisionSnapshot.generation != request.decisionGeneration) {
            return "decision generation is stale"
        }
        val expectedInputs = listOf(
            DecisionInputReference.Planning(request.planningProposalId, request.planningGeneration),
            DecisionInputReference.Reasoning(request.reasoningArtifactId, request.reasoningGeneration)
        )
        if (decisionSnapshot.decision.inputs != expectedInputs) {
            return "decision inputs do not match coordinated cognitive chain"
        }

        val orchestrationSnapshot = orchestration.inspect(request.orchestrationIntentId)
            ?: return "orchestration intent is not live"
        if (orchestrationSnapshot.generation != request.orchestrationGeneration) {
            return "orchestration intent generation is stale"
        }
        if (
            orchestrationSnapshot.intent.decision.decisionId != request.decisionId ||
            orchestrationSnapshot.intent.decision.generation != request.decisionGeneration ||
            orchestrationSnapshot.intent.decision.selectedOptionId != decisionSnapshot.decision.selectedOptionId
        ) {
            return "orchestration intent provenance does not match coordinated decision"
        }

        return null
    }

    private fun planningReference(evidence: AgentCoordinationDeliberationReadyEvidence): String =
        "coordination=${evidence.coordination.id.value}@${evidence.coordination.generation.value};" +
            "attemptBinding=${evidence.attemptBindingGeneration.value};" +
            "participant=${evidence.participant.id.value}@${evidence.participant.generation.value};" +
            "request=${evidence.requestId.value}@${evidence.requestGeneration.value};" +
            "proposal=${evidence.attempt.proposalId.value}@${evidence.attempt.proposalGeneration.value};" +
            "attempt=${evidence.attempt.attemptNumber}"

    private fun reasoningReference(
        evidence: AgentCoordinationDeliberationReadyEvidence,
        request: AgentCoordinationExecutionRequest
    ): String = planningReference(evidence) +
        ";planning=${request.planningProposalId.value}@${request.planningGeneration.value}"

    private fun sameReadiness(
        first: AgentCoordinationDeliberationReadyEvidence,
        second: AgentCoordinationDeliberationReadyEvidence
    ): Boolean = first.coordination == second.coordination &&
        first.attemptBindingGeneration == second.attemptBindingGeneration &&
        first.participant == second.participant &&
        first.requestId == second.requestId &&
        first.requestGeneration == second.requestGeneration &&
        first.attempt == second.attempt

    private fun reject(
        request: AgentCoordinationExecutionRequest,
        reason: String
    ): AgentCoordinationExecutionResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_COORDINATION_EXECUTION_REJECTED",
            message = reason,
            context = foundation.rootContext(
                operation = "executeAgentCoordination",
                component = "AgentCoordination",
                metadata = requestMetadata(request)
            ),
            metadata = mapOf("rejectionReason" to reason)
        )
        return AgentCoordinationExecutionResult.Rejected(reason)
    }

    private fun requestMetadata(request: AgentCoordinationExecutionRequest): Map<String, String> = mapOf(
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
        "principal" to request.principal.value,
        "actionId" to request.actionId.value
    )

    private fun metadata(
        request: AgentCoordinationExecutionRequest,
        evidence: AgentCoordinationDeliberationReadyEvidence
    ): Map<String, String> = requestMetadata(request) + mapOf(
        "agentCoordinationId" to evidence.coordination.id.value,
        "agentCoordinationGeneration" to evidence.coordination.generation.value.toString(),
        "attemptBindingGeneration" to evidence.attemptBindingGeneration.value.toString(),
        "participantAgentId" to evidence.participant.id.value,
        "participantAgentGeneration" to evidence.participant.generation.value.toString(),
        "autonomyProposalId" to evidence.attempt.proposalId.value,
        "autonomyGeneration" to evidence.attempt.proposalGeneration.value.toString(),
        "autonomyAttemptNumber" to evidence.attempt.attemptNumber.toString()
    )
}
