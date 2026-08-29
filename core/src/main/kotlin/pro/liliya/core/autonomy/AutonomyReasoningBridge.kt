package pro.liliya.core.autonomy

import java.time.Instant
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifact
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.reasoning.ReasoningInstallResult
import pro.liliya.core.reasoning.ReasoningOrigin
import pro.liliya.core.reasoning.ReasoningPremise
import pro.liliya.core.reasoning.ReasoningSourceId
import pro.liliya.core.reasoning.ReasoningSourceReference

class AutonomyReasoningBridgeRequest(
    val deliberationRequestId: AutonomyDeliberationRequestId,
    val deliberationGeneration: AutonomyDeliberationGeneration,
    val planningProposalId: PlanningProposalId,
    val planningGeneration: PlanningGeneration,
    val reasoningArtifactId: ReasoningArtifactId,
    premises: List<ReasoningPremise>,
    val analysis: String,
    val conclusion: String,
    val createdAt: Instant
) {
    val premises: List<ReasoningPremise> = premises.toList()

    init {
        require(this.premises.isNotEmpty()) { "autonomy reasoning bridge requires at least one premise" }
        require(analysis.isNotBlank()) { "autonomy reasoning analysis must not be blank" }
        require(conclusion.isNotBlank()) { "autonomy reasoning conclusion must not be blank" }
    }
}

sealed interface AutonomyReasoningBridgeResult {
    data class Installed(
        val reasoning: pro.liliya.core.reasoning.ReasoningOwnership,
        val readiness: AutonomyDeliberationReadyEvidence
    ) : AutonomyReasoningBridgeResult

    data class Rejected(val reason: String) : AutonomyReasoningBridgeResult {
        init { require(reason.isNotBlank()) { "autonomy reasoning bridge rejection reason must not be blank" } }
    }
}

class AutonomyReasoningBridge(
    private val foundation: FoundationComposition,
    private val preflight: AutonomyDeliberationPreflight,
    private val planning: PlanningComposition,
    private val reasoning: ReasoningComposition
) {
    fun install(request: AutonomyReasoningBridgeRequest): AutonomyReasoningBridgeResult {
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

        val artifact = ReasoningArtifact(
            id = request.reasoningArtifactId,
            origin = ReasoningOrigin(
                sourceId = ReasoningSourceId("autonomy-planning"),
                sourceReference = ReasoningSourceReference(
                    structuralReference(ready, request.planningProposalId, request.planningGeneration)
                )
            ),
            premises = request.premises,
            analysis = request.analysis,
            conclusion = request.conclusion,
            createdAt = request.createdAt
        )

        return when (val installed = reasoning.install(artifact)) {
            is ReasoningInstallResult.Installed -> {
                foundation.observability.record(
                    severity = DiagnosticSeverity.INFO,
                    code = "AUTONOMY_REASONING_BRIDGE_INSTALLED",
                    message = "autonomy planning installed reasoning artifact",
                    context = foundation.rootContext(
                        operation = "bridgeAutonomyPlanningToReasoning",
                        component = "Autonomy",
                        metadata = metadata(ready, request.planningProposalId, request.planningGeneration, artifact.id)
                    ),
                    metadata = metadata(ready, request.planningProposalId, request.planningGeneration, artifact.id)
                )
                AutonomyReasoningBridgeResult.Installed(installed.ownership, ready)
            }

            is ReasoningInstallResult.Rejected -> reject(request, installed.reason)
        }
    }

    private fun planningMatchesReadiness(
        sourceId: String,
        sourceReference: String?,
        evidence: AutonomyDeliberationReadyEvidence
    ): Boolean =
        sourceId == "autonomy-deliberation" &&
            sourceReference == planningReference(evidence)

    private fun planningReference(evidence: AutonomyDeliberationReadyEvidence): String =
        "request=${evidence.request.id.value}@${evidence.requestGeneration.value};" +
            "proposal=${evidence.attempt.proposal.id.value}@${evidence.attempt.generation.value};" +
            "attempt=${evidence.attempt.attemptNumber}"

    private fun structuralReference(
        evidence: AutonomyDeliberationReadyEvidence,
        planningProposalId: PlanningProposalId,
        planningGeneration: PlanningGeneration
    ): String =
        planningReference(evidence) +
            ";planning=${planningProposalId.value}@${planningGeneration.value}"

    private fun metadata(
        evidence: AutonomyDeliberationReadyEvidence,
        planningProposalId: PlanningProposalId,
        planningGeneration: PlanningGeneration,
        reasoningArtifactId: ReasoningArtifactId
    ): Map<String, String> = mapOf(
        "autonomyDeliberationRequestId" to evidence.request.id.value,
        "autonomyDeliberationGeneration" to evidence.requestGeneration.value.toString(),
        "autonomyProposalId" to evidence.attempt.proposal.id.value,
        "autonomyGeneration" to evidence.attempt.generation.value.toString(),
        "autonomyAttemptNumber" to evidence.attempt.attemptNumber.toString(),
        "planningProposalId" to planningProposalId.value,
        "planningGeneration" to planningGeneration.value.toString(),
        "reasoningArtifactId" to reasoningArtifactId.value
    )

    private fun reject(
        request: AutonomyReasoningBridgeRequest,
        reason: String
    ): AutonomyReasoningBridgeResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AUTONOMY_REASONING_BRIDGE_REJECTED",
            message = reason,
            context = foundation.rootContext(
                operation = "bridgeAutonomyPlanningToReasoning",
                component = "Autonomy",
                metadata = mapOf(
                    "autonomyDeliberationRequestId" to request.deliberationRequestId.value,
                    "autonomyDeliberationGeneration" to request.deliberationGeneration.value.toString(),
                    "planningProposalId" to request.planningProposalId.value,
                    "planningGeneration" to request.planningGeneration.value.toString(),
                    "reasoningArtifactId" to request.reasoningArtifactId.value
                )
            )
        )
        return AutonomyReasoningBridgeResult.Rejected(reason)
    }
}
