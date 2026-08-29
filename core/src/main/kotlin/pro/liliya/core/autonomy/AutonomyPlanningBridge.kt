package pro.liliya.core.autonomy

import java.time.Instant
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.planning.PlanningInstallResult
import pro.liliya.core.planning.PlanningOrigin
import pro.liliya.core.planning.PlanningProposal
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.planning.PlanningSourceId
import pro.liliya.core.planning.PlanningSourceReference
import pro.liliya.core.planning.PlanningStep

class AutonomyPlanningBridgeRequest(
    val deliberationRequestId: AutonomyDeliberationRequestId,
    val deliberationGeneration: AutonomyDeliberationGeneration,
    val planningProposalId: PlanningProposalId,
    val goal: String,
    steps: List<PlanningStep>,
    val createdAt: Instant
) {
    val steps: List<PlanningStep> = steps.toList()

    init {
        require(goal.isNotBlank()) { "autonomy planning goal must not be blank" }
        require(this.steps.isNotEmpty()) { "autonomy planning request must contain at least one step" }
    }
}

sealed interface AutonomyPlanningBridgeResult {
    data class Installed(
        val planning: pro.liliya.core.planning.PlanningOwnership,
        val readiness: AutonomyDeliberationReadyEvidence
    ) : AutonomyPlanningBridgeResult

    data class Rejected(val reason: String) : AutonomyPlanningBridgeResult {
        init { require(reason.isNotBlank()) { "autonomy planning bridge rejection reason must not be blank" } }
    }
}

class AutonomyPlanningBridge(
    private val foundation: FoundationComposition,
    private val preflight: AutonomyDeliberationPreflight,
    private val planning: PlanningComposition
) {
    fun install(request: AutonomyPlanningBridgeRequest): AutonomyPlanningBridgeResult {
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

        val proposal = PlanningProposal(
            id = request.planningProposalId,
            origin = PlanningOrigin(
                sourceId = PlanningSourceId("autonomy-deliberation"),
                sourceReference = PlanningSourceReference(structuralReference(ready))
            ),
            goal = request.goal,
            steps = request.steps,
            createdAt = request.createdAt
        )

        return when (val installed = planning.install(proposal)) {
            is PlanningInstallResult.Installed -> {
                foundation.observability.record(
                    severity = DiagnosticSeverity.INFO,
                    code = "AUTONOMY_PLANNING_BRIDGE_INSTALLED",
                    message = "autonomy deliberation installed planning proposal",
                    context = foundation.rootContext(
                        operation = "bridgeAutonomyDeliberationToPlanning",
                        component = "Autonomy",
                        metadata = metadata(ready, proposal.id)
                    ),
                    metadata = metadata(ready, proposal.id)
                )
                AutonomyPlanningBridgeResult.Installed(installed.ownership, ready)
            }

            is PlanningInstallResult.Rejected -> reject(request, installed.reason)
        }
    }

    private fun structuralReference(evidence: AutonomyDeliberationReadyEvidence): String =
        "request=${evidence.request.id.value}@${evidence.requestGeneration.value};" +
            "proposal=${evidence.attempt.proposal.id.value}@${evidence.attempt.generation.value};" +
            "attempt=${evidence.attempt.attemptNumber}"

    private fun metadata(
        evidence: AutonomyDeliberationReadyEvidence,
        planningProposalId: PlanningProposalId
    ): Map<String, String> = mapOf(
        "autonomyDeliberationRequestId" to evidence.request.id.value,
        "autonomyDeliberationGeneration" to evidence.requestGeneration.value.toString(),
        "autonomyProposalId" to evidence.attempt.proposal.id.value,
        "autonomyGeneration" to evidence.attempt.generation.value.toString(),
        "autonomyAttemptNumber" to evidence.attempt.attemptNumber.toString(),
        "planningProposalId" to planningProposalId.value
    )

    private fun reject(
        request: AutonomyPlanningBridgeRequest,
        reason: String
    ): AutonomyPlanningBridgeResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AUTONOMY_PLANNING_BRIDGE_REJECTED",
            message = reason,
            context = foundation.rootContext(
                operation = "bridgeAutonomyDeliberationToPlanning",
                component = "Autonomy",
                metadata = mapOf(
                    "autonomyDeliberationRequestId" to request.deliberationRequestId.value,
                    "autonomyDeliberationGeneration" to request.deliberationGeneration.value.toString(),
                    "planningProposalId" to request.planningProposalId.value
                )
            )
        )
        return AutonomyPlanningBridgeResult.Rejected(reason)
    }
}
