package pro.liliya.core.autonomy

import java.time.Instant
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.decision.DecisionInputReference
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.orchestration.OrchestrationComposition
import pro.liliya.core.orchestration.OrchestrationDecisionReference
import pro.liliya.core.orchestration.OrchestrationInstallResult
import pro.liliya.core.orchestration.OrchestrationIntent
import pro.liliya.core.orchestration.OrchestrationIntentId
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.reasoning.ReasoningGeneration

class AutonomyOrchestrationBridgeRequest(
    val deliberationRequestId: AutonomyDeliberationRequestId,
    val deliberationGeneration: AutonomyDeliberationGeneration,
    val planningProposalId: PlanningProposalId,
    val planningGeneration: PlanningGeneration,
    val reasoningArtifactId: ReasoningArtifactId,
    val reasoningGeneration: ReasoningGeneration,
    val decisionId: DecisionId,
    val decisionGeneration: DecisionGeneration,
    val orchestrationIntentId: OrchestrationIntentId,
    val description: String,
    val createdAt: Instant
) {
    init { require(description.isNotBlank()) { "autonomy orchestration description must not be blank" } }
}

sealed interface AutonomyOrchestrationBridgeResult {
    data class Installed(
        val orchestration: pro.liliya.core.orchestration.OrchestrationOwnership,
        val readiness: AutonomyDeliberationReadyEvidence
    ) : AutonomyOrchestrationBridgeResult

    data class Rejected(val reason: String) : AutonomyOrchestrationBridgeResult {
        init { require(reason.isNotBlank()) { "autonomy orchestration bridge rejection reason must not be blank" } }
    }
}

class AutonomyOrchestrationBridge(
    private val foundation: FoundationComposition,
    private val preflight: AutonomyDeliberationPreflight,
    private val planning: PlanningComposition,
    private val reasoning: ReasoningComposition,
    private val decisions: DecisionComposition,
    private val orchestration: OrchestrationComposition
) {
    fun install(request: AutonomyOrchestrationBridgeRequest): AutonomyOrchestrationBridgeResult {
        val ready = when (
            val result = preflight.check(request.deliberationRequestId, request.deliberationGeneration)
        ) {
            is AutonomyDeliberationPreflightResult.Ready -> result.evidence
            is AutonomyDeliberationPreflightResult.Rejected ->
                return reject(request, "deliberation preflight rejected: ${result.reason}")
        }

        val planningSnapshot = planning.inspect(request.planningProposalId)
            ?: return reject(request, "planning proposal is not live")
        if (planningSnapshot.generation != request.planningGeneration) {
            return reject(request, "planning proposal generation is stale")
        }
        if (planningSnapshot.proposal.origin.sourceId.value != "autonomy-deliberation" ||
            planningSnapshot.proposal.origin.sourceReference?.value != planningReference(ready)) {
            return reject(request, "planning proposal provenance does not match autonomy deliberation")
        }

        val reasoningSnapshot = reasoning.inspect(request.reasoningArtifactId)
            ?: return reject(request, "reasoning artifact is not live")
        if (reasoningSnapshot.generation != request.reasoningGeneration) {
            return reject(request, "reasoning artifact generation is stale")
        }
        if (reasoningSnapshot.artifact.origin.sourceId.value != "autonomy-planning" ||
            reasoningSnapshot.artifact.origin.sourceReference?.value != reasoningReference(ready, request)) {
            return reject(request, "reasoning artifact provenance does not match autonomy planning")
        }

        val decisionSnapshot = decisions.inspect(request.decisionId)
            ?: return reject(request, "decision is not live")
        if (decisionSnapshot.generation != request.decisionGeneration) {
            return reject(request, "decision generation is stale")
        }
        val expectedInputs = listOf(
            DecisionInputReference.Planning(request.planningProposalId, request.planningGeneration),
            DecisionInputReference.Reasoning(request.reasoningArtifactId, request.reasoningGeneration)
        )
        if (decisionSnapshot.decision.inputs != expectedInputs) {
            return reject(request, "decision inputs do not match autonomy deliberation chain")
        }

        val intent = OrchestrationIntent(
            id = request.orchestrationIntentId,
            decision = OrchestrationDecisionReference(
                decisionId = decisionSnapshot.decision.id,
                generation = decisionSnapshot.generation,
                selectedOptionId = decisionSnapshot.decision.selectedOptionId
            ),
            description = request.description,
            createdAt = request.createdAt
        )

        return when (val installed = orchestration.install(intent)) {
            is OrchestrationInstallResult.Installed -> {
                foundation.observability.record(
                    severity = DiagnosticSeverity.INFO,
                    code = "AUTONOMY_ORCHESTRATION_BRIDGE_INSTALLED",
                    message = "autonomy decision installed orchestration intent",
                    context = foundation.rootContext(
                        operation = "bridgeAutonomyDecisionToOrchestration",
                        component = "Autonomy",
                        metadata = metadata(ready, request, decisionSnapshot.decision.selectedOptionId.value)
                    ),
                    metadata = metadata(ready, request, decisionSnapshot.decision.selectedOptionId.value)
                )
                AutonomyOrchestrationBridgeResult.Installed(installed.ownership, ready)
            }

            is OrchestrationInstallResult.Rejected -> reject(request, installed.reason)
        }
    }

    private fun planningReference(evidence: AutonomyDeliberationReadyEvidence): String =
        "request=${evidence.request.id.value}@${evidence.requestGeneration.value};" +
            "proposal=${evidence.attempt.proposal.id.value}@${evidence.attempt.generation.value};" +
            "attempt=${evidence.attempt.attemptNumber}"

    private fun reasoningReference(
        evidence: AutonomyDeliberationReadyEvidence,
        request: AutonomyOrchestrationBridgeRequest
    ): String = planningReference(evidence) +
        ";planning=${request.planningProposalId.value}@${request.planningGeneration.value}"

    private fun metadata(
        evidence: AutonomyDeliberationReadyEvidence,
        request: AutonomyOrchestrationBridgeRequest,
        selectedOptionId: String
    ): Map<String, String> = mapOf(
        "autonomyDeliberationRequestId" to evidence.request.id.value,
        "autonomyDeliberationGeneration" to evidence.requestGeneration.value.toString(),
        "autonomyProposalId" to evidence.attempt.proposal.id.value,
        "autonomyGeneration" to evidence.attempt.generation.value.toString(),
        "autonomyAttemptNumber" to evidence.attempt.attemptNumber.toString(),
        "planningProposalId" to request.planningProposalId.value,
        "planningGeneration" to request.planningGeneration.value.toString(),
        "reasoningArtifactId" to request.reasoningArtifactId.value,
        "reasoningGeneration" to request.reasoningGeneration.value.toString(),
        "decisionId" to request.decisionId.value,
        "decisionGeneration" to request.decisionGeneration.value.toString(),
        "selectedDecisionOptionId" to selectedOptionId,
        "orchestrationIntentId" to request.orchestrationIntentId.value
    )

    private fun reject(
        request: AutonomyOrchestrationBridgeRequest,
        reason: String
    ): AutonomyOrchestrationBridgeResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AUTONOMY_ORCHESTRATION_BRIDGE_REJECTED",
            message = reason,
            context = foundation.rootContext(
                operation = "bridgeAutonomyDecisionToOrchestration",
                component = "Autonomy",
                metadata = mapOf(
                    "autonomyDeliberationRequestId" to request.deliberationRequestId.value,
                    "autonomyDeliberationGeneration" to request.deliberationGeneration.value.toString(),
                    "decisionId" to request.decisionId.value,
                    "decisionGeneration" to request.decisionGeneration.value.toString(),
                    "orchestrationIntentId" to request.orchestrationIntentId.value
                )
            )
        )
        return AutonomyOrchestrationBridgeResult.Rejected(reason)
    }
}
