package pro.liliya.core.autonomy

import java.time.Instant
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.decision.DecisionInputReference
import pro.liliya.core.decision.DecisionInstallResult
import pro.liliya.core.decision.DecisionOption
import pro.liliya.core.decision.DecisionOptionId
import pro.liliya.core.decision.DecisionRecord
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.reasoning.ReasoningGeneration

class AutonomyDecisionBridgeRequest(
    val deliberationRequestId: AutonomyDeliberationRequestId,
    val deliberationGeneration: AutonomyDeliberationGeneration,
    val planningProposalId: PlanningProposalId,
    val planningGeneration: PlanningGeneration,
    val reasoningArtifactId: ReasoningArtifactId,
    val reasoningGeneration: ReasoningGeneration,
    val decisionId: DecisionId,
    options: List<DecisionOption>,
    val selectedOptionId: DecisionOptionId,
    val rationale: String,
    val createdAt: Instant
) {
    val options: List<DecisionOption> = options.toList()

    init {
        require(this.options.isNotEmpty()) { "autonomy decision bridge requires at least one option" }
        require(rationale.isNotBlank()) { "autonomy decision rationale must not be blank" }
    }
}

sealed interface AutonomyDecisionBridgeResult {
    data class Installed(
        val decision: pro.liliya.core.decision.DecisionOwnership,
        val readiness: AutonomyDeliberationReadyEvidence
    ) : AutonomyDecisionBridgeResult

    data class Rejected(val reason: String) : AutonomyDecisionBridgeResult {
        init { require(reason.isNotBlank()) { "autonomy decision bridge rejection reason must not be blank" } }
    }
}

class AutonomyDecisionBridge(
    private val foundation: FoundationComposition,
    private val preflight: AutonomyDeliberationPreflight,
    private val planning: PlanningComposition,
    private val reasoning: ReasoningComposition,
    private val decisions: DecisionComposition
) {
    fun install(request: AutonomyDecisionBridgeRequest): AutonomyDecisionBridgeResult {
        val ready = when (
            val result = preflight.check(
                request.deliberationRequestId,
                request.deliberationGeneration
            )
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
        if (!planningMatchesReadiness(planningSnapshot.proposal.origin.sourceId.value,
                planningSnapshot.proposal.origin.sourceReference?.value, ready)) {
            return reject(request, "planning proposal provenance does not match autonomy deliberation")
        }

        val reasoningSnapshot = reasoning.inspect(request.reasoningArtifactId)
            ?: return reject(request, "reasoning artifact is not live")
        if (reasoningSnapshot.generation != request.reasoningGeneration) {
            return reject(request, "reasoning artifact generation is stale")
        }
        if (!reasoningMatchesPlanning(
                reasoningSnapshot.artifact.origin.sourceId.value,
                reasoningSnapshot.artifact.origin.sourceReference?.value,
                ready,
                request.planningProposalId,
                request.planningGeneration
            )) {
            return reject(request, "reasoning artifact provenance does not match autonomy planning")
        }

        val record = DecisionRecord(
            id = request.decisionId,
            inputs = listOf(
                DecisionInputReference.Planning(request.planningProposalId, request.planningGeneration),
                DecisionInputReference.Reasoning(request.reasoningArtifactId, request.reasoningGeneration)
            ),
            options = request.options,
            selectedOptionId = request.selectedOptionId,
            rationale = request.rationale,
            createdAt = request.createdAt
        )

        return when (val installed = decisions.install(record)) {
            is DecisionInstallResult.Installed -> {
                foundation.observability.record(
                    severity = DiagnosticSeverity.INFO,
                    code = "AUTONOMY_DECISION_BRIDGE_INSTALLED",
                    message = "autonomy deliberation installed decision record",
                    context = foundation.rootContext(
                        operation = "bridgeAutonomyDeliberationToDecision",
                        component = "Autonomy",
                        metadata = metadata(ready, request)
                    ),
                    metadata = metadata(ready, request)
                )
                AutonomyDecisionBridgeResult.Installed(installed.ownership, ready)
            }

            is DecisionInstallResult.Rejected -> reject(request, installed.reason)
        }
    }

    private fun planningMatchesReadiness(
        sourceId: String,
        sourceReference: String?,
        evidence: AutonomyDeliberationReadyEvidence
    ): Boolean =
        sourceId == "autonomy-deliberation" && sourceReference == planningReference(evidence)

    private fun reasoningMatchesPlanning(
        sourceId: String,
        sourceReference: String?,
        evidence: AutonomyDeliberationReadyEvidence,
        planningProposalId: PlanningProposalId,
        planningGeneration: PlanningGeneration
    ): Boolean =
        sourceId == "autonomy-planning" &&
            sourceReference == reasoningReference(evidence, planningProposalId, planningGeneration)

    private fun planningReference(evidence: AutonomyDeliberationReadyEvidence): String =
        "request=${evidence.request.id.value}@${evidence.requestGeneration.value};" +
            "proposal=${evidence.attempt.proposal.id.value}@${evidence.attempt.generation.value};" +
            "attempt=${evidence.attempt.attemptNumber}"

    private fun reasoningReference(
        evidence: AutonomyDeliberationReadyEvidence,
        planningProposalId: PlanningProposalId,
        planningGeneration: PlanningGeneration
    ): String =
        planningReference(evidence) + ";planning=${planningProposalId.value}@${planningGeneration.value}"

    private fun metadata(
        evidence: AutonomyDeliberationReadyEvidence,
        request: AutonomyDecisionBridgeRequest
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
        "selectedDecisionOptionId" to request.selectedOptionId.value,
        "decisionOptionCount" to request.options.size.toString()
    )

    private fun reject(
        request: AutonomyDecisionBridgeRequest,
        reason: String
    ): AutonomyDecisionBridgeResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AUTONOMY_DECISION_BRIDGE_REJECTED",
            message = reason,
            context = foundation.rootContext(
                operation = "bridgeAutonomyDeliberationToDecision",
                component = "Autonomy",
                metadata = mapOf(
                    "autonomyDeliberationRequestId" to request.deliberationRequestId.value,
                    "autonomyDeliberationGeneration" to request.deliberationGeneration.value.toString(),
                    "planningProposalId" to request.planningProposalId.value,
                    "planningGeneration" to request.planningGeneration.value.toString(),
                    "reasoningArtifactId" to request.reasoningArtifactId.value,
                    "reasoningGeneration" to request.reasoningGeneration.value.toString(),
                    "decisionId" to request.decisionId.value
                )
            )
        )
        return AutonomyDecisionBridgeResult.Rejected(reason)
    }
}
