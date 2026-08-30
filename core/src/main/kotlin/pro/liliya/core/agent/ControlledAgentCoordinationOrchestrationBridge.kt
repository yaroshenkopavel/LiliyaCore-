package pro.liliya.core.agent

import java.time.Instant
import pro.liliya.core.autonomy.AutonomyDeliberationGeneration
import pro.liliya.core.autonomy.AutonomyDeliberationRequestId
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
import pro.liliya.core.orchestration.OrchestrationOwnership
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.reasoning.ReasoningGeneration

class AgentCoordinationOrchestrationRequest(
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
    init {
        require(description.isNotBlank()) { "coordination orchestration description must not be blank" }
    }
}

sealed interface AgentCoordinationOrchestrationResult {
    data class Installed(
        val orchestration: OrchestrationOwnership,
        val readiness: AgentCoordinationDeliberationReadyEvidence
    ) : AgentCoordinationOrchestrationResult

    data class Rejected(val reason: String) : AgentCoordinationOrchestrationResult {
        init { require(reason.isNotBlank()) { "coordination orchestration rejection reason must not be blank" } }
    }

    data class Failed(val reason: String) : AgentCoordinationOrchestrationResult {
        init { require(reason.isNotBlank()) { "coordination orchestration failure reason must not be blank" } }
    }
}

internal fun interface AgentCoordinationOrchestrationInstaller {
    fun install(intent: OrchestrationIntent): OrchestrationInstallResult
}

/**
 * Controlled bridge from one exact coordinated Decision generation into ordinary frozen
 * Orchestration Intent data.
 *
 * Coordinated readiness plus exact Planning, Reasoning and Decision generations are validated
 * before the write and revalidated afterwards. Any post-write governance/provenance change causes
 * compensation of only the exact Orchestration generation created here. This bridge creates intent
 * data only and performs no authorization, permission grant, scheduler action or Execution.
 */
class ControlledAgentCoordinationOrchestrationBridge private constructor(
    private val foundation: FoundationComposition,
    private val preflight: AgentCoordinationDeliberationPreflightChecker,
    private val planning: PlanningComposition,
    private val reasoning: ReasoningComposition,
    private val decisions: DecisionComposition,
    private val orchestration: OrchestrationComposition,
    private val installer: AgentCoordinationOrchestrationInstaller
) {
    constructor(
        foundation: FoundationComposition,
        preflight: ControlledAgentCoordinationDeliberationPreflight,
        planning: PlanningComposition,
        reasoning: ReasoningComposition,
        decisions: DecisionComposition,
        orchestration: OrchestrationComposition
    ) : this(
        foundation = foundation,
        preflight = AgentCoordinationDeliberationPreflightChecker(preflight::check),
        planning = planning,
        reasoning = reasoning,
        decisions = decisions,
        orchestration = orchestration,
        installer = AgentCoordinationOrchestrationInstaller(orchestration::install)
    )

    internal constructor(
        foundation: FoundationComposition,
        preflight: AgentCoordinationDeliberationPreflightChecker,
        planning: PlanningComposition,
        reasoning: ReasoningComposition,
        decisions: DecisionComposition,
        orchestration: OrchestrationComposition,
        installer: AgentCoordinationOrchestrationInstaller,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit
    ) : this(foundation, preflight, planning, reasoning, decisions, orchestration, installer)

    fun install(request: AgentCoordinationOrchestrationRequest): AgentCoordinationOrchestrationResult {
        val initial = when (
            val checked = preflight.check(request.deliberationRequestId, request.deliberationGeneration)
        ) {
            is AgentCoordinationDeliberationPreflightResult.Ready -> checked.evidence
            is AgentCoordinationDeliberationPreflightResult.Rejected -> return reject(
                request,
                "coordination deliberation preflight rejected: ${checked.reason}"
            )
        }

        val planningSnapshot = planning.inspect(request.planningProposalId)
            ?: return reject(request, "planning proposal is not live")
        if (planningSnapshot.generation != request.planningGeneration) {
            return reject(request, "planning proposal generation is stale")
        }
        if (!planningMatchesReadiness(
                planningSnapshot.proposal.origin.sourceId.value,
                planningSnapshot.proposal.origin.sourceReference?.value,
                initial
            )) {
            return reject(request, "planning proposal provenance does not match coordinated deliberation")
        }

        val reasoningSnapshot = reasoning.inspect(request.reasoningArtifactId)
            ?: return reject(request, "reasoning artifact is not live")
        if (reasoningSnapshot.generation != request.reasoningGeneration) {
            return reject(request, "reasoning artifact generation is stale")
        }
        if (!reasoningMatchesPlanning(
                reasoningSnapshot.artifact.origin.sourceId.value,
                reasoningSnapshot.artifact.origin.sourceReference?.value,
                initial,
                request.planningProposalId,
                request.planningGeneration
            )) {
            return reject(request, "reasoning artifact provenance does not match coordinated planning")
        }

        val decisionSnapshot = decisions.inspect(request.decisionId)
            ?: return reject(request, "decision is not live")
        if (decisionSnapshot.generation != request.decisionGeneration) {
            return reject(request, "decision generation is stale")
        }
        if (!decisionMatchesInputs(decisionSnapshot.decision.inputs, request)) {
            return reject(request, "decision inputs do not match coordinated cognitive chain")
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

        val ownership = when (val installed = installer.install(intent)) {
            is OrchestrationInstallResult.Installed -> installed.ownership
            is OrchestrationInstallResult.Rejected -> return reject(request, installed.reason)
        }

        val confirmed = when (
            val checked = preflight.check(request.deliberationRequestId, request.deliberationGeneration)
        ) {
            is AgentCoordinationDeliberationPreflightResult.Ready -> checked.evidence
            is AgentCoordinationDeliberationPreflightResult.Rejected -> return compensate(
                request,
                ownership,
                "coordination changed after orchestration write: ${checked.reason}"
            )
        }

        if (!sameReadiness(initial, confirmed)) {
            return compensate(request, ownership, "coordination readiness changed after orchestration write")
        }

        val confirmedPlanning = planning.inspect(request.planningProposalId)
            ?: return compensate(request, ownership, "planning proposal removed after orchestration write")
        if (confirmedPlanning.generation != request.planningGeneration) {
            return compensate(request, ownership, "planning proposal generation changed after orchestration write")
        }
        if (!planningMatchesReadiness(
                confirmedPlanning.proposal.origin.sourceId.value,
                confirmedPlanning.proposal.origin.sourceReference?.value,
                confirmed
            )) {
            return compensate(request, ownership, "planning provenance changed after orchestration write")
        }

        val confirmedReasoning = reasoning.inspect(request.reasoningArtifactId)
            ?: return compensate(request, ownership, "reasoning artifact removed after orchestration write")
        if (confirmedReasoning.generation != request.reasoningGeneration) {
            return compensate(request, ownership, "reasoning artifact generation changed after orchestration write")
        }
        if (!reasoningMatchesPlanning(
                confirmedReasoning.artifact.origin.sourceId.value,
                confirmedReasoning.artifact.origin.sourceReference?.value,
                confirmed,
                request.planningProposalId,
                request.planningGeneration
            )) {
            return compensate(request, ownership, "reasoning provenance changed after orchestration write")
        }

        val confirmedDecision = decisions.inspect(request.decisionId)
            ?: return compensate(request, ownership, "decision removed after orchestration write")
        if (confirmedDecision.generation != request.decisionGeneration) {
            return compensate(request, ownership, "decision generation changed after orchestration write")
        }
        if (!decisionMatchesInputs(confirmedDecision.decision.inputs, request)) {
            return compensate(request, ownership, "decision inputs changed after orchestration write")
        }
        if (confirmedDecision.decision.selectedOptionId != ownership.intent.decision.selectedOptionId) {
            return compensate(request, ownership, "decision selection changed after orchestration write")
        }

        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_COORDINATION_ORCHESTRATION_INSTALLED",
            message = "coordinated decision installed orchestration intent",
            context = foundation.rootContext(
                operation = "bridgeAgentCoordinationDecisionToOrchestration",
                component = "AgentCoordination",
                metadata = metadata(confirmed, request, ownership)
            ),
            metadata = metadata(confirmed, request, ownership)
        )
        return AgentCoordinationOrchestrationResult.Installed(ownership, confirmed)
    }

    private fun decisionMatchesInputs(
        inputs: List<DecisionInputReference>,
        request: AgentCoordinationOrchestrationRequest
    ): Boolean = inputs == listOf(
        DecisionInputReference.Planning(request.planningProposalId, request.planningGeneration),
        DecisionInputReference.Reasoning(request.reasoningArtifactId, request.reasoningGeneration)
    )

    private fun planningMatchesReadiness(
        sourceId: String,
        sourceReference: String?,
        evidence: AgentCoordinationDeliberationReadyEvidence
    ): Boolean = sourceId == "agent-coordination-deliberation" && sourceReference == planningReference(evidence)

    private fun reasoningMatchesPlanning(
        sourceId: String,
        sourceReference: String?,
        evidence: AgentCoordinationDeliberationReadyEvidence,
        planningProposalId: PlanningProposalId,
        planningGeneration: PlanningGeneration
    ): Boolean = sourceId == "agent-coordination-planning" &&
        sourceReference == reasoningReference(evidence, planningProposalId, planningGeneration)

    private fun planningReference(evidence: AgentCoordinationDeliberationReadyEvidence): String =
        "coordination=${evidence.coordination.id.value}@${evidence.coordination.generation.value};" +
            "attemptBinding=${evidence.attemptBindingGeneration.value};" +
            "participant=${evidence.participant.id.value}@${evidence.participant.generation.value};" +
            "request=${evidence.requestId.value}@${evidence.requestGeneration.value};" +
            "proposal=${evidence.attempt.proposalId.value}@${evidence.attempt.proposalGeneration.value};" +
            "attempt=${evidence.attempt.attemptNumber}"

    private fun reasoningReference(
        evidence: AgentCoordinationDeliberationReadyEvidence,
        planningProposalId: PlanningProposalId,
        planningGeneration: PlanningGeneration
    ): String = planningReference(evidence) + ";planning=${planningProposalId.value}@${planningGeneration.value}"

    private fun sameReadiness(
        first: AgentCoordinationDeliberationReadyEvidence,
        second: AgentCoordinationDeliberationReadyEvidence
    ): Boolean = first.coordination == second.coordination &&
        first.attemptBindingGeneration == second.attemptBindingGeneration &&
        first.participant == second.participant &&
        first.requestId == second.requestId &&
        first.requestGeneration == second.requestGeneration &&
        first.attempt == second.attempt

    private fun compensate(
        request: AgentCoordinationOrchestrationRequest,
        ownership: OrchestrationOwnership,
        reason: String
    ): AgentCoordinationOrchestrationResult {
        if (!ownership.remove()) {
            val live = orchestration.inspect(ownership.intent.id)
            if (live?.generation == ownership.generation) {
                foundation.observability.record(
                    severity = DiagnosticSeverity.CRITICAL,
                    code = "AGENT_COORDINATION_ORCHESTRATION_COMPENSATION_FAILED",
                    message = "coordinated orchestration compensation failed",
                    context = foundation.rootContext(
                        operation = "compensateAgentCoordinationOrchestration",
                        component = "AgentCoordination",
                        metadata = mapOf(
                            "orchestrationIntentId" to ownership.intent.id.value,
                            "orchestrationGeneration" to ownership.generation.value.toString()
                        )
                    ),
                    metadata = mapOf("failureReason" to reason)
                )
                return AgentCoordinationOrchestrationResult.Failed(
                    "coordinated orchestration compensation failed after: $reason"
                )
            }
        }

        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_COORDINATION_ORCHESTRATION_COMPENSATED",
            message = "coordinated orchestration intent compensated",
            context = foundation.rootContext(
                operation = "compensateAgentCoordinationOrchestration",
                component = "AgentCoordination",
                metadata = mapOf(
                    "orchestrationIntentId" to ownership.intent.id.value,
                    "orchestrationGeneration" to ownership.generation.value.toString()
                )
            ),
            metadata = mapOf("compensationReason" to reason)
        )
        return AgentCoordinationOrchestrationResult.Rejected(reason)
    }

    private fun reject(
        request: AgentCoordinationOrchestrationRequest,
        reason: String
    ): AgentCoordinationOrchestrationResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_COORDINATION_ORCHESTRATION_REJECTED",
            message = reason,
            context = foundation.rootContext(
                operation = "bridgeAgentCoordinationDecisionToOrchestration",
                component = "AgentCoordination",
                metadata = mapOf(
                    "autonomyDeliberationRequestId" to request.deliberationRequestId.value,
                    "autonomyDeliberationGeneration" to request.deliberationGeneration.value.toString(),
                    "decisionId" to request.decisionId.value,
                    "decisionGeneration" to request.decisionGeneration.value.toString(),
                    "orchestrationIntentId" to request.orchestrationIntentId.value
                )
            )
        )
        return AgentCoordinationOrchestrationResult.Rejected(reason)
    }

    private fun metadata(
        evidence: AgentCoordinationDeliberationReadyEvidence,
        request: AgentCoordinationOrchestrationRequest,
        ownership: OrchestrationOwnership
    ): Map<String, String> = mapOf(
        "agentCoordinationId" to evidence.coordination.id.value,
        "agentCoordinationGeneration" to evidence.coordination.generation.value.toString(),
        "attemptBindingGeneration" to evidence.attemptBindingGeneration.value.toString(),
        "participantAgentId" to evidence.participant.id.value,
        "participantAgentGeneration" to evidence.participant.generation.value.toString(),
        "autonomyDeliberationRequestId" to evidence.requestId.value,
        "autonomyDeliberationGeneration" to evidence.requestGeneration.value.toString(),
        "autonomyProposalId" to evidence.attempt.proposalId.value,
        "autonomyGeneration" to evidence.attempt.proposalGeneration.value.toString(),
        "autonomyAttemptNumber" to evidence.attempt.attemptNumber.toString(),
        "planningProposalId" to request.planningProposalId.value,
        "planningGeneration" to request.planningGeneration.value.toString(),
        "reasoningArtifactId" to request.reasoningArtifactId.value,
        "reasoningGeneration" to request.reasoningGeneration.value.toString(),
        "decisionId" to request.decisionId.value,
        "decisionGeneration" to request.decisionGeneration.value.toString(),
        "selectedDecisionOptionId" to ownership.intent.decision.selectedOptionId.value,
        "orchestrationIntentId" to ownership.intent.id.value,
        "orchestrationGeneration" to ownership.generation.value.toString()
    )
}
