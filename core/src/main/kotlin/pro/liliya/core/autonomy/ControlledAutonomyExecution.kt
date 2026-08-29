package pro.liliya.core.autonomy

import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.decision.DecisionInputReference
import pro.liliya.core.execution.ExecutionActionId
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

/**
 * Exact full-chain request for one controlled autonomy execution attempt.
 *
 * The request is provenance only. It grants no permission. Every call revalidates the live
 * autonomy deliberation and its exact cognitive/orchestration chain before delegating to the
 * already frozen ControlledOrchestrationExecution boundary, which performs fresh Authority and
 * Execution validation again.
 */
data class ControlledAutonomyExecutionRequest(
    val deliberationRequestId: AutonomyDeliberationRequestId,
    val deliberationGeneration: AutonomyDeliberationGeneration,
    val planningProposalId: PlanningProposalId,
    val planningGeneration: PlanningGeneration,
    val reasoningArtifactId: ReasoningArtifactId,
    val reasoningGeneration: ReasoningGeneration,
    val decisionId: pro.liliya.core.decision.DecisionId,
    val decisionGeneration: pro.liliya.core.decision.DecisionGeneration,
    val orchestrationIntentId: OrchestrationIntentId,
    val orchestrationGeneration: OrchestrationGeneration,
    val principal: AuthorityPrincipal,
    val actionId: ExecutionActionId
)

sealed interface ControlledAutonomyExecutionResult {
    data object Succeeded : ControlledAutonomyExecutionResult

    data class Rejected(val reason: String) : ControlledAutonomyExecutionResult {
        init { require(reason.isNotBlank()) { "autonomy execution rejection reason must not be blank" } }
    }

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : ControlledAutonomyExecutionResult {
        init { require(reason.isNotBlank()) { "autonomy execution failure reason must not be blank" } }
    }
}

class ControlledAutonomyExecution(
    private val preflight: AutonomyDeliberationPreflight,
    private val planning: PlanningComposition,
    private val reasoning: ReasoningComposition,
    private val decisions: DecisionComposition,
    private val orchestration: OrchestrationComposition,
    private val controlledOrchestration: ControlledOrchestrationExecution
) {
    fun execute(request: ControlledAutonomyExecutionRequest): ControlledAutonomyExecutionResult {
        val ready = when (
            val result = preflight.check(
                request.deliberationRequestId,
                request.deliberationGeneration
            )
        ) {
            is AutonomyDeliberationPreflightResult.Ready -> result.evidence
            is AutonomyDeliberationPreflightResult.Rejected ->
                return reject("autonomy deliberation preflight rejected: ${result.reason}")
        }

        val planningSnapshot = planning.inspect(request.planningProposalId)
            ?: return reject("planning proposal is not live")
        if (planningSnapshot.generation != request.planningGeneration) {
            return reject("planning proposal generation is stale")
        }
        if (
            planningSnapshot.proposal.origin.sourceId.value != "autonomy-deliberation" ||
            planningSnapshot.proposal.origin.sourceReference?.value != planningReference(ready)
        ) {
            return reject("planning proposal provenance does not match autonomy deliberation")
        }

        val reasoningSnapshot = reasoning.inspect(request.reasoningArtifactId)
            ?: return reject("reasoning artifact is not live")
        if (reasoningSnapshot.generation != request.reasoningGeneration) {
            return reject("reasoning artifact generation is stale")
        }
        if (
            reasoningSnapshot.artifact.origin.sourceId.value != "autonomy-planning" ||
            reasoningSnapshot.artifact.origin.sourceReference?.value != reasoningReference(ready, request)
        ) {
            return reject("reasoning artifact provenance does not match autonomy planning")
        }

        val decisionSnapshot = decisions.inspect(request.decisionId)
            ?: return reject("decision is not live")
        if (decisionSnapshot.generation != request.decisionGeneration) {
            return reject("decision generation is stale")
        }
        val expectedInputs = listOf(
            DecisionInputReference.Planning(request.planningProposalId, request.planningGeneration),
            DecisionInputReference.Reasoning(request.reasoningArtifactId, request.reasoningGeneration)
        )
        if (decisionSnapshot.decision.inputs != expectedInputs) {
            return reject("decision inputs do not match autonomy deliberation chain")
        }

        val orchestrationSnapshot = orchestration.inspect(request.orchestrationIntentId)
            ?: return reject("orchestration intent is not live")
        if (orchestrationSnapshot.generation != request.orchestrationGeneration) {
            return reject("orchestration intent generation is stale")
        }
        if (
            orchestrationSnapshot.intent.decision.decisionId != request.decisionId ||
            orchestrationSnapshot.intent.decision.generation != request.decisionGeneration ||
            orchestrationSnapshot.intent.decision.selectedOptionId != decisionSnapshot.decision.selectedOptionId
        ) {
            return reject("orchestration intent provenance does not match autonomy decision")
        }

        return when (
            val result = controlledOrchestration.execute(
                OrchestrationExecutionPreflightRequest(
                    intentId = request.orchestrationIntentId,
                    generation = request.orchestrationGeneration,
                    principal = request.principal,
                    actionId = request.actionId
                )
            )
        ) {
            ControlledOrchestrationExecutionResult.Succeeded -> ControlledAutonomyExecutionResult.Succeeded
            is ControlledOrchestrationExecutionResult.Rejected ->
                ControlledAutonomyExecutionResult.Rejected(result.reason)
            is ControlledOrchestrationExecutionResult.Failed ->
                ControlledAutonomyExecutionResult.Failed(result.reason, result.throwable)
        }
    }

    private fun planningReference(evidence: AutonomyDeliberationReadyEvidence): String =
        "request=${evidence.request.id.value}@${evidence.requestGeneration.value};" +
            "proposal=${evidence.attempt.proposal.id.value}@${evidence.attempt.generation.value};" +
            "attempt=${evidence.attempt.attemptNumber}"

    private fun reasoningReference(
        evidence: AutonomyDeliberationReadyEvidence,
        request: ControlledAutonomyExecutionRequest
    ): String = planningReference(evidence) +
        ";planning=${request.planningProposalId.value}@${request.planningGeneration.value}"

    private fun reject(reason: String): ControlledAutonomyExecutionResult.Rejected =
        ControlledAutonomyExecutionResult.Rejected(reason)
}
