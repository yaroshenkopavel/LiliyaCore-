package pro.liliya.core.agent

import java.time.Instant
import pro.liliya.core.autonomy.AutonomyDeliberationGeneration
import pro.liliya.core.autonomy.AutonomyDeliberationRequestId
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.planning.PlanningInstallResult
import pro.liliya.core.planning.PlanningOrigin
import pro.liliya.core.planning.PlanningOwnership
import pro.liliya.core.planning.PlanningProposal
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.planning.PlanningSourceId
import pro.liliya.core.planning.PlanningSourceReference
import pro.liliya.core.planning.PlanningStep

class AgentCoordinationPlanningRequest(
    val deliberationRequestId: AutonomyDeliberationRequestId,
    val deliberationGeneration: AutonomyDeliberationGeneration,
    val planningProposalId: PlanningProposalId,
    val goal: String,
    steps: List<PlanningStep>,
    val createdAt: Instant
) {
    val steps: List<PlanningStep> = steps.toList()

    init {
        require(goal.isNotBlank()) { "coordination planning goal must not be blank" }
        require(this.steps.isNotEmpty()) { "coordination planning requires at least one step" }
    }
}

sealed interface AgentCoordinationPlanningResult {
    data class Installed(
        val planning: PlanningOwnership,
        val readiness: AgentCoordinationDeliberationReadyEvidence
    ) : AgentCoordinationPlanningResult

    data class Rejected(val reason: String) : AgentCoordinationPlanningResult {
        init { require(reason.isNotBlank()) { "coordination planning rejection reason must not be blank" } }
    }

    data class Failed(val reason: String) : AgentCoordinationPlanningResult {
        init { require(reason.isNotBlank()) { "coordination planning failure reason must not be blank" } }
    }
}

internal fun interface AgentCoordinationDeliberationPreflightChecker {
    fun check(
        requestId: AutonomyDeliberationRequestId,
        generation: AutonomyDeliberationGeneration
    ): AgentCoordinationDeliberationPreflightResult
}

internal fun interface AgentCoordinationPlanningInstaller {
    fun install(proposal: PlanningProposal): PlanningInstallResult
}

/**
 * Controlled one-record bridge from exact live coordinated deliberation readiness into ordinary
 * frozen Planning data.
 *
 * Coordinated readiness is checked immediately before and after the Planning write. A governance
 * change after the write causes exact-generation rollback before normal rejection. Planning remains
 * data only: this bridge performs no Reasoning/Decision, scheduling, Authority or Execution.
 */
class ControlledAgentCoordinationPlanningBridge private constructor(
    private val foundation: FoundationComposition,
    private val preflight: AgentCoordinationDeliberationPreflightChecker,
    private val planning: PlanningComposition,
    private val installer: AgentCoordinationPlanningInstaller
) {
    constructor(
        foundation: FoundationComposition,
        preflight: ControlledAgentCoordinationDeliberationPreflight,
        planning: PlanningComposition
    ) : this(
        foundation = foundation,
        preflight = AgentCoordinationDeliberationPreflightChecker(preflight::check),
        planning = planning,
        installer = AgentCoordinationPlanningInstaller(planning::install)
    )

    internal constructor(
        foundation: FoundationComposition,
        preflight: AgentCoordinationDeliberationPreflightChecker,
        planning: PlanningComposition,
        installer: AgentCoordinationPlanningInstaller,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit
    ) : this(foundation, preflight, planning, installer)

    fun install(request: AgentCoordinationPlanningRequest): AgentCoordinationPlanningResult {
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

        val proposal = PlanningProposal(
            id = request.planningProposalId,
            origin = PlanningOrigin(
                sourceId = COORDINATED_DELIBERATION_SOURCE,
                sourceReference = PlanningSourceReference(structuralReference(initial))
            ),
            goal = request.goal,
            steps = request.steps,
            createdAt = request.createdAt
        )

        val ownership = when (val installed = installer.install(proposal)) {
            is PlanningInstallResult.Installed -> installed.ownership
            is PlanningInstallResult.Rejected -> return reject(request, installed.reason)
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
                "coordination changed after planning write: ${checked.reason}"
            )
        }

        if (!sameReadiness(initial, confirmed)) {
            return compensate(
                request,
                ownership,
                "coordination readiness changed after planning write"
            )
        }

        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_COORDINATION_PLANNING_INSTALLED",
            message = "coordinated deliberation installed planning proposal",
            context = foundation.rootContext(
                operation = "bridgeAgentCoordinationDeliberationToPlanning",
                component = "AgentCoordination",
                metadata = metadata(confirmed, proposal.id, ownership.generation.value)
            ),
            metadata = metadata(confirmed, proposal.id, ownership.generation.value)
        )
        return AgentCoordinationPlanningResult.Installed(ownership, confirmed)
    }

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
        request: AgentCoordinationPlanningRequest,
        ownership: PlanningOwnership,
        reason: String
    ): AgentCoordinationPlanningResult {
        if (!ownership.remove()) {
            val live = planning.inspect(ownership.proposal.id)
            if (live?.generation == ownership.generation) {
                foundation.observability.record(
                    severity = DiagnosticSeverity.CRITICAL,
                    code = "AGENT_COORDINATION_PLANNING_COMPENSATION_FAILED",
                    message = "coordinated planning compensation failed",
                    context = foundation.rootContext(
                        operation = "compensateAgentCoordinationPlanning",
                        component = "AgentCoordination",
                        metadata = mapOf(
                            "planningProposalId" to ownership.proposal.id.value,
                            "planningGeneration" to ownership.generation.value.toString()
                        )
                    ),
                    metadata = mapOf("failureReason" to reason)
                )
                return AgentCoordinationPlanningResult.Failed(
                    "coordinated planning compensation failed after: $reason"
                )
            }
        }

        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_COORDINATION_PLANNING_COMPENSATED",
            message = "coordinated planning write compensated",
            context = foundation.rootContext(
                operation = "compensateAgentCoordinationPlanning",
                component = "AgentCoordination",
                metadata = mapOf(
                    "planningProposalId" to ownership.proposal.id.value,
                    "planningGeneration" to ownership.generation.value.toString()
                )
            ),
            metadata = mapOf("compensationReason" to reason)
        )
        return AgentCoordinationPlanningResult.Rejected(reason)
    }

    private fun structuralReference(
        evidence: AgentCoordinationDeliberationReadyEvidence
    ): String =
        "coordination=${evidence.coordination.id.value}@${evidence.coordination.generation.value};" +
            "attemptBinding=${evidence.attemptBindingGeneration.value};" +
            "participant=${evidence.participant.id.value}@${evidence.participant.generation.value};" +
            "request=${evidence.requestId.value}@${evidence.requestGeneration.value};" +
            "proposal=${evidence.attempt.proposalId.value}@${evidence.attempt.proposalGeneration.value};" +
            "attempt=${evidence.attempt.attemptNumber}"

    private fun reject(
        request: AgentCoordinationPlanningRequest,
        reason: String
    ): AgentCoordinationPlanningResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_COORDINATION_PLANNING_REJECTED",
            message = reason,
            context = foundation.rootContext(
                operation = "bridgeAgentCoordinationDeliberationToPlanning",
                component = "AgentCoordination",
                metadata = mapOf(
                    "autonomyDeliberationRequestId" to request.deliberationRequestId.value,
                    "autonomyDeliberationGeneration" to request.deliberationGeneration.value.toString(),
                    "planningProposalId" to request.planningProposalId.value
                )
            )
        )
        return AgentCoordinationPlanningResult.Rejected(reason)
    }

    private fun metadata(
        evidence: AgentCoordinationDeliberationReadyEvidence,
        planningProposalId: PlanningProposalId,
        planningGeneration: Long
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
        "planningProposalId" to planningProposalId.value,
        "planningGeneration" to planningGeneration.toString()
    )

    private companion object {
        val COORDINATED_DELIBERATION_SOURCE = PlanningSourceId("agent-coordination-deliberation")
    }
}
