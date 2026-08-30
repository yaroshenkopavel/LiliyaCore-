package pro.liliya.core.agent

import java.time.Instant
import pro.liliya.core.autonomy.AutonomyDeliberationGeneration
import pro.liliya.core.autonomy.AutonomyDeliberationRequestId
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
import pro.liliya.core.reasoning.ReasoningOwnership
import pro.liliya.core.reasoning.ReasoningPremise
import pro.liliya.core.reasoning.ReasoningSourceId
import pro.liliya.core.reasoning.ReasoningSourceReference

class AgentCoordinationReasoningRequest(
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
        require(this.premises.isNotEmpty()) { "coordination reasoning requires at least one premise" }
        require(analysis.isNotBlank()) { "coordination reasoning analysis must not be blank" }
        require(conclusion.isNotBlank()) { "coordination reasoning conclusion must not be blank" }
    }
}

sealed interface AgentCoordinationReasoningResult {
    data class Installed(
        val reasoning: ReasoningOwnership,
        val readiness: AgentCoordinationDeliberationReadyEvidence
    ) : AgentCoordinationReasoningResult

    data class Rejected(val reason: String) : AgentCoordinationReasoningResult {
        init { require(reason.isNotBlank()) { "coordination reasoning rejection reason must not be blank" } }
    }

    data class Failed(val reason: String) : AgentCoordinationReasoningResult {
        init { require(reason.isNotBlank()) { "coordination reasoning failure reason must not be blank" } }
    }
}

internal fun interface AgentCoordinationReasoningInstaller {
    fun install(artifact: ReasoningArtifact): ReasoningInstallResult
}

/**
 * Controlled one-record bridge from exact coordinated Planning into ordinary frozen Reasoning data.
 *
 * The exact Planning generation and coordinated deliberation readiness are validated before the
 * Reasoning write and revalidated after it. Any governance/provenance change after the write causes
 * exact-generation compensation before normal rejection. Reasoning remains data only: this bridge
 * performs no Decision, scheduling, Authority or Execution.
 */
class ControlledAgentCoordinationReasoningBridge private constructor(
    private val foundation: FoundationComposition,
    private val preflight: AgentCoordinationDeliberationPreflightChecker,
    private val planning: PlanningComposition,
    private val reasoning: ReasoningComposition,
    private val installer: AgentCoordinationReasoningInstaller
) {
    constructor(
        foundation: FoundationComposition,
        preflight: ControlledAgentCoordinationDeliberationPreflight,
        planning: PlanningComposition,
        reasoning: ReasoningComposition
    ) : this(
        foundation = foundation,
        preflight = AgentCoordinationDeliberationPreflightChecker(preflight::check),
        planning = planning,
        reasoning = reasoning,
        installer = AgentCoordinationReasoningInstaller(reasoning::install)
    )

    internal constructor(
        foundation: FoundationComposition,
        preflight: AgentCoordinationDeliberationPreflightChecker,
        planning: PlanningComposition,
        reasoning: ReasoningComposition,
        installer: AgentCoordinationReasoningInstaller,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit
    ) : this(foundation, preflight, planning, reasoning, installer)

    fun install(request: AgentCoordinationReasoningRequest): AgentCoordinationReasoningResult {
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
        if (!planningMatchesReadiness(planningSnapshot.proposal.origin.sourceId.value,
                planningSnapshot.proposal.origin.sourceReference?.value, initial)) {
            return reject(request, "planning proposal provenance does not match coordinated deliberation")
        }

        val artifact = ReasoningArtifact(
            id = request.reasoningArtifactId,
            origin = ReasoningOrigin(
                sourceId = COORDINATED_PLANNING_SOURCE,
                sourceReference = ReasoningSourceReference(
                    reasoningReference(initial, request.planningProposalId, request.planningGeneration)
                )
            ),
            premises = request.premises,
            analysis = request.analysis,
            conclusion = request.conclusion,
            createdAt = request.createdAt
        )

        val ownership = when (val installed = installer.install(artifact)) {
            is ReasoningInstallResult.Installed -> installed.ownership
            is ReasoningInstallResult.Rejected -> return reject(request, installed.reason)
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
                "coordination changed after reasoning write: ${checked.reason}"
            )
        }

        if (!sameReadiness(initial, confirmed)) {
            return compensate(
                request,
                ownership,
                "coordination readiness changed after reasoning write"
            )
        }

        val confirmedPlanning = planning.inspect(request.planningProposalId)
            ?: return compensate(request, ownership, "planning proposal removed after reasoning write")
        if (confirmedPlanning.generation != request.planningGeneration) {
            return compensate(request, ownership, "planning proposal generation changed after reasoning write")
        }
        if (!planningMatchesReadiness(
                confirmedPlanning.proposal.origin.sourceId.value,
                confirmedPlanning.proposal.origin.sourceReference?.value,
                confirmed
            )) {
            return compensate(request, ownership, "planning provenance changed after reasoning write")
        }

        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_COORDINATION_REASONING_INSTALLED",
            message = "coordinated planning installed reasoning artifact",
            context = foundation.rootContext(
                operation = "bridgeAgentCoordinationPlanningToReasoning",
                component = "AgentCoordination",
                metadata = metadata(confirmed, request, ownership.generation.value)
            ),
            metadata = metadata(confirmed, request, ownership.generation.value)
        )
        return AgentCoordinationReasoningResult.Installed(ownership, confirmed)
    }

    private fun planningMatchesReadiness(
        sourceId: String,
        sourceReference: String?,
        evidence: AgentCoordinationDeliberationReadyEvidence
    ): Boolean =
        sourceId == "agent-coordination-deliberation" &&
            sourceReference == planningReference(evidence)

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
        planningReference(evidence) +
            ";planning=${planningProposalId.value}@${planningGeneration.value}"

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
        request: AgentCoordinationReasoningRequest,
        ownership: ReasoningOwnership,
        reason: String
    ): AgentCoordinationReasoningResult {
        if (!ownership.remove()) {
            val live = reasoning.inspect(ownership.artifact.id)
            if (live?.generation == ownership.generation) {
                foundation.observability.record(
                    severity = DiagnosticSeverity.CRITICAL,
                    code = "AGENT_COORDINATION_REASONING_COMPENSATION_FAILED",
                    message = "coordinated reasoning compensation failed",
                    context = foundation.rootContext(
                        operation = "compensateAgentCoordinationReasoning",
                        component = "AgentCoordination",
                        metadata = mapOf(
                            "reasoningArtifactId" to ownership.artifact.id.value,
                            "reasoningGeneration" to ownership.generation.value.toString()
                        )
                    ),
                    metadata = mapOf("failureReason" to reason)
                )
                return AgentCoordinationReasoningResult.Failed(
                    "coordinated reasoning compensation failed after: $reason"
                )
            }
        }

        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_COORDINATION_REASONING_COMPENSATED",
            message = "coordinated reasoning write compensated",
            context = foundation.rootContext(
                operation = "compensateAgentCoordinationReasoning",
                component = "AgentCoordination",
                metadata = mapOf(
                    "reasoningArtifactId" to ownership.artifact.id.value,
                    "reasoningGeneration" to ownership.generation.value.toString()
                )
            ),
            metadata = mapOf("compensationReason" to reason)
        )
        return AgentCoordinationReasoningResult.Rejected(reason)
    }

    private fun reject(
        request: AgentCoordinationReasoningRequest,
        reason: String
    ): AgentCoordinationReasoningResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_COORDINATION_REASONING_REJECTED",
            message = reason,
            context = foundation.rootContext(
                operation = "bridgeAgentCoordinationPlanningToReasoning",
                component = "AgentCoordination",
                metadata = mapOf(
                    "autonomyDeliberationRequestId" to request.deliberationRequestId.value,
                    "autonomyDeliberationGeneration" to request.deliberationGeneration.value.toString(),
                    "planningProposalId" to request.planningProposalId.value,
                    "planningGeneration" to request.planningGeneration.value.toString(),
                    "reasoningArtifactId" to request.reasoningArtifactId.value
                )
            )
        )
        return AgentCoordinationReasoningResult.Rejected(reason)
    }

    private fun metadata(
        evidence: AgentCoordinationDeliberationReadyEvidence,
        request: AgentCoordinationReasoningRequest,
        reasoningGeneration: Long
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
        "reasoningGeneration" to reasoningGeneration.toString()
    )

    private companion object {
        val COORDINATED_PLANNING_SOURCE = ReasoningSourceId("agent-coordination-planning")
    }
}
