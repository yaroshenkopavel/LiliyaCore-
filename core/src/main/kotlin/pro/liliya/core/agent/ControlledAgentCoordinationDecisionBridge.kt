package pro.liliya.core.agent

import java.time.Instant
import pro.liliya.core.autonomy.AutonomyDeliberationGeneration
import pro.liliya.core.autonomy.AutonomyDeliberationRequestId
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.decision.DecisionInputReference
import pro.liliya.core.decision.DecisionInstallResult
import pro.liliya.core.decision.DecisionOption
import pro.liliya.core.decision.DecisionOptionId
import pro.liliya.core.decision.DecisionOwnership
import pro.liliya.core.decision.DecisionRecord
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.reasoning.ReasoningGeneration

class AgentCoordinationDecisionRequest(
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
        require(this.options.isNotEmpty()) { "coordination decision requires at least one option" }
        require(rationale.isNotBlank()) { "coordination decision rationale must not be blank" }
    }
}

sealed interface AgentCoordinationDecisionResult {
    data class Installed(
        val decision: DecisionOwnership,
        val readiness: AgentCoordinationDeliberationReadyEvidence
    ) : AgentCoordinationDecisionResult

    data class Rejected(val reason: String) : AgentCoordinationDecisionResult {
        init { require(reason.isNotBlank()) { "coordination decision rejection reason must not be blank" } }
    }

    data class Failed(val reason: String) : AgentCoordinationDecisionResult {
        init { require(reason.isNotBlank()) { "coordination decision failure reason must not be blank" } }
    }
}

internal fun interface AgentCoordinationDecisionInstaller {
    fun install(decision: DecisionRecord): DecisionInstallResult
}

/**
 * Controlled one-record bridge from exact coordinated Reasoning into ordinary frozen Decision data.
 *
 * Exact coordinated deliberation readiness, Planning generation/provenance and Reasoning
 * generation/provenance are validated before the Decision write and revalidated afterwards.
 * A post-write governance/provenance change compensates only the exact Decision generation created
 * by this bridge. Decision remains data only: this bridge performs no Orchestration, scheduling,
 * permission, Authority or Execution.
 */
class ControlledAgentCoordinationDecisionBridge private constructor(
    private val foundation: FoundationComposition,
    private val preflight: AgentCoordinationDeliberationPreflightChecker,
    private val planning: PlanningComposition,
    private val reasoning: ReasoningComposition,
    private val decisions: DecisionComposition,
    private val installer: AgentCoordinationDecisionInstaller
) {
    constructor(
        foundation: FoundationComposition,
        preflight: ControlledAgentCoordinationDeliberationPreflight,
        planning: PlanningComposition,
        reasoning: ReasoningComposition,
        decisions: DecisionComposition
    ) : this(
        foundation = foundation,
        preflight = AgentCoordinationDeliberationPreflightChecker(preflight::check),
        planning = planning,
        reasoning = reasoning,
        decisions = decisions,
        installer = AgentCoordinationDecisionInstaller(decisions::install)
    )

    internal constructor(
        foundation: FoundationComposition,
        preflight: AgentCoordinationDeliberationPreflightChecker,
        planning: PlanningComposition,
        reasoning: ReasoningComposition,
        decisions: DecisionComposition,
        installer: AgentCoordinationDecisionInstaller,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit
    ) : this(foundation, preflight, planning, reasoning, decisions, installer)

    fun install(request: AgentCoordinationDecisionRequest): AgentCoordinationDecisionResult {
        val initial = when (
            val checked = preflight.check(
                request.deliberationRequestId,
                request.deliberationGeneration
            )
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

        val ownership = when (val installed = installer.install(record)) {
            is DecisionInstallResult.Installed -> installed.ownership
            is DecisionInstallResult.Rejected -> return reject(request, installed.reason)
        }

        val confirmed = when (
            val checked = preflight.check(
                request.deliberationRequestId,
                request.deliberationGeneration
            )
        ) {
            is AgentCoordinationDeliberationPreflightResult.Ready -> checked.evidence
            is AgentCoordinationDeliberationPreflightResult.Rejected -> return compensate(
                request,
                ownership,
                "coordination changed after decision write: ${checked.reason}"
            )
        }

        if (!sameReadiness(initial, confirmed)) {
            return compensate(request, ownership, "coordination readiness changed after decision write")
        }

        val confirmedPlanning = planning.inspect(request.planningProposalId)
            ?: return compensate(request, ownership, "planning proposal removed after decision write")
        if (confirmedPlanning.generation != request.planningGeneration) {
            return compensate(request, ownership, "planning proposal generation changed after decision write")
        }
        if (!planningMatchesReadiness(
                confirmedPlanning.proposal.origin.sourceId.value,
                confirmedPlanning.proposal.origin.sourceReference?.value,
                confirmed
            )) {
            return compensate(request, ownership, "planning provenance changed after decision write")
        }

        val confirmedReasoning = reasoning.inspect(request.reasoningArtifactId)
            ?: return compensate(request, ownership, "reasoning artifact removed after decision write")
        if (confirmedReasoning.generation != request.reasoningGeneration) {
            return compensate(request, ownership, "reasoning artifact generation changed after decision write")
        }
        if (!reasoningMatchesPlanning(
                confirmedReasoning.artifact.origin.sourceId.value,
                confirmedReasoning.artifact.origin.sourceReference?.value,
                confirmed,
                request.planningProposalId,
                request.planningGeneration
            )) {
            return compensate(request, ownership, "reasoning provenance changed after decision write")
        }

        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_COORDINATION_DECISION_INSTALLED",
            message = "coordinated reasoning installed decision record",
            context = foundation.rootContext(
                operation = "bridgeAgentCoordinationReasoningToDecision",
                component = "AgentCoordination",
                metadata = metadata(confirmed, request, ownership.generation.value)
            ),
            metadata = metadata(confirmed, request, ownership.generation.value)
        )
        return AgentCoordinationDecisionResult.Installed(ownership, confirmed)
    }

    private fun planningMatchesReadiness(
        sourceId: String,
        sourceReference: String?,
        evidence: AgentCoordinationDeliberationReadyEvidence
    ): Boolean =
        sourceId == "agent-coordination-deliberation" && sourceReference == planningReference(evidence)

    private fun reasoningMatchesPlanning(
        sourceId: String,
        sourceReference: String?,
        evidence: AgentCoordinationDeliberationReadyEvidence,
        planningProposalId: PlanningProposalId,
        planningGeneration: PlanningGeneration
    ): Boolean =
        sourceId == "agent-coordination-planning" &&
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
    ): String =
        planningReference(evidence) + ";planning=${planningProposalId.value}@${planningGeneration.value}"

    private fun sameReadiness(
        first: AgentCoordinationDeliberationReadyEvidence,
        second: AgentCoordinationDeliberationReadyEvidence
    ): Boolean =
        first.coordination == second.coordination &&
            first.attemptBindingGeneration == second.attemptBindingGeneration &&
            first.participant == second.participant &&
            first.requestId == second.requestId &&
            first.requestGeneration == second.requestGeneration &&
            first.attempt == second.attempt

    private fun compensate(
        request: AgentCoordinationDecisionRequest,
        ownership: DecisionOwnership,
        reason: String
    ): AgentCoordinationDecisionResult {
        if (!ownership.remove()) {
            val live = decisions.inspect(ownership.decision.id)
            if (live?.generation == ownership.generation) {
                foundation.observability.record(
                    severity = DiagnosticSeverity.CRITICAL,
                    code = "AGENT_COORDINATION_DECISION_COMPENSATION_FAILED",
                    message = "coordinated decision compensation failed",
                    context = foundation.rootContext(
                        operation = "compensateAgentCoordinationDecision",
                        component = "AgentCoordination",
                        metadata = mapOf(
                            "decisionId" to ownership.decision.id.value,
                            "decisionGeneration" to ownership.generation.value.toString()
                        )
                    ),
                    metadata = mapOf("failureReason" to reason)
                )
                return AgentCoordinationDecisionResult.Failed(
                    "coordinated decision compensation failed after: $reason"
                )
            }
        }

        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_COORDINATION_DECISION_COMPENSATED",
            message = "coordinated decision write compensated",
            context = foundation.rootContext(
                operation = "compensateAgentCoordinationDecision",
                component = "AgentCoordination",
                metadata = mapOf(
                    "decisionId" to ownership.decision.id.value,
                    "decisionGeneration" to ownership.generation.value.toString()
                )
            ),
            metadata = mapOf("compensationReason" to reason)
        )
        return AgentCoordinationDecisionResult.Rejected(reason)
    }

    private fun reject(
        request: AgentCoordinationDecisionRequest,
        reason: String
    ): AgentCoordinationDecisionResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_COORDINATION_DECISION_REJECTED",
            message = reason,
            context = foundation.rootContext(
                operation = "bridgeAgentCoordinationReasoningToDecision",
                component = "AgentCoordination",
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
        return AgentCoordinationDecisionResult.Rejected(reason)
    }

    private fun metadata(
        evidence: AgentCoordinationDeliberationReadyEvidence,
        request: AgentCoordinationDecisionRequest,
        decisionGeneration: Long
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
        "decisionGeneration" to decisionGeneration.toString(),
        "selectedDecisionOptionId" to request.selectedOptionId.value,
        "decisionOptionCount" to request.options.size.toString()
    )
}
