package pro.liliya.core.cognitive

import java.time.Instant
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.decision.DecisionSnapshot
import pro.liliya.core.decision.DecisionInputReference
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.execution.ExecutionActionId
import pro.liliya.core.orchestration.OrchestrationComposition
import pro.liliya.core.orchestration.OrchestrationDecisionReference
import pro.liliya.core.orchestration.OrchestrationInstallResult
import pro.liliya.core.orchestration.OrchestrationIntent
import pro.liliya.core.orchestration.OrchestrationIntentId
import pro.liliya.core.orchestration.OrchestrationOwnership
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.reasoning.ReasoningComposition

fun interface CognitiveOrchestrationActionResolver {
    fun resolve(decision: DecisionSnapshot): ExecutionActionId?
}

class CognitiveOrchestrationProposalRequest(
    val turn: CognitiveTurnReference,
    val intentId: OrchestrationIntentId,
    val description: String,
    val createdAt: Instant
) {
    init { require(description.isNotBlank()) { "cognitive orchestration proposal description must not be blank" } }

    override fun toString(): String =
        "CognitiveOrchestrationProposalRequest(turn=$turn, intentId=$intentId, description=<redacted>, createdAt=$createdAt)"
}

enum class CognitiveOrchestrationProposalFailure {
    DEPENDENCIES_UNAVAILABLE,
    ACCEPTED_COGNITION_MISSING,
    ACCEPTED_COGNITION_MISMATCH,
    ACTION_UNRESOLVED,
    ACTION_RESOLUTION_FAILED,
    ORCHESTRATION_INSTALL_FAILED
}

sealed interface CognitiveOrchestrationProposalResult {
    data class Proposed(
        val ownership: OrchestrationOwnership
    ) : CognitiveOrchestrationProposalResult

    data object Stale : CognitiveOrchestrationProposalResult

    data class Rejected(
        val reason: CognitiveOrchestrationProposalFailure
    ) : CognitiveOrchestrationProposalResult
}

/**
 * Converts one exact accepted cognitive decision into an inert OrchestrationIntent.
 *
 * This bridge deliberately has no Authority or Execution dependency. Creating a proposal grants no
 * permission and performs no action. Any later execution must travel through the existing controlled
 * orchestration preflight, fresh Authority checks, and Execution boundary.
 */
internal class CognitiveOrchestrationProposalBridge(
    private val foundation: FoundationComposition,
    private val turns: CognitiveTurnRegistry,
    private val scope: CognitiveRuntimeScopeId,
    private val planning: PlanningComposition,
    private val reasoning: ReasoningComposition,
    private val decisions: DecisionComposition,
    private val orchestration: OrchestrationComposition,
    private val actionResolver: CognitiveOrchestrationActionResolver
) {
    fun propose(request: CognitiveOrchestrationProposalRequest): CognitiveOrchestrationProposalResult {
        if (turns.currentReference() != request.turn) {
            return CognitiveOrchestrationProposalResult.Stale
        }

        val receipt = turns.acceptedCognitionIfCurrent(request.turn)
            ?: return reject(request, CognitiveOrchestrationProposalFailure.ACCEPTED_COGNITION_MISSING)
        if (receipt.turn != request.turn) {
            return reject(request, CognitiveOrchestrationProposalFailure.ACCEPTED_COGNITION_MISMATCH)
        }

        val expectedSourceReference = CognitiveProvenance.turnToken(scope, request.turn).value

        val planningSnapshot = planning.inspect(receipt.planning.id)
            ?: return reject(request, CognitiveOrchestrationProposalFailure.ACCEPTED_COGNITION_MISMATCH)
        if (
            planningSnapshot.generation != receipt.planning.generation ||
            planningSnapshot.proposal.origin.sourceId.value != COGNITIVE_RUNTIME_SOURCE_ID ||
            planningSnapshot.proposal.origin.sourceReference?.value != expectedSourceReference
        ) {
            return reject(request, CognitiveOrchestrationProposalFailure.ACCEPTED_COGNITION_MISMATCH)
        }

        val reasoningSnapshot = reasoning.inspect(receipt.reasoning.id)
            ?: return reject(request, CognitiveOrchestrationProposalFailure.ACCEPTED_COGNITION_MISMATCH)
        if (
            reasoningSnapshot.generation != receipt.reasoning.generation ||
            reasoningSnapshot.artifact.origin.sourceId.value != COGNITIVE_RUNTIME_SOURCE_ID ||
            reasoningSnapshot.artifact.origin.sourceReference?.value != expectedSourceReference
        ) {
            return reject(request, CognitiveOrchestrationProposalFailure.ACCEPTED_COGNITION_MISMATCH)
        }

        val decisionSnapshot = decisions.inspect(receipt.decision.id)
            ?: return reject(request, CognitiveOrchestrationProposalFailure.ACCEPTED_COGNITION_MISMATCH)
        if (decisionSnapshot.generation != receipt.decision.generation) {
            return reject(request, CognitiveOrchestrationProposalFailure.ACCEPTED_COGNITION_MISMATCH)
        }
        val expectedInputs = listOf(
            DecisionInputReference.Planning(receipt.planning.id, receipt.planning.generation),
            DecisionInputReference.Reasoning(receipt.reasoning.id, receipt.reasoning.generation)
        )
        if (decisionSnapshot.decision.inputs != expectedInputs) {
            return reject(request, CognitiveOrchestrationProposalFailure.ACCEPTED_COGNITION_MISMATCH)
        }

        val actionId = try {
            actionResolver.resolve(decisionSnapshot)
        } catch (_: Exception) {
            return reject(request, CognitiveOrchestrationProposalFailure.ACTION_RESOLUTION_FAILED)
        } ?: return reject(request, CognitiveOrchestrationProposalFailure.ACTION_UNRESOLVED)

        val intent = OrchestrationIntent(
            id = request.intentId,
            decision = OrchestrationDecisionReference(
                decisionId = decisionSnapshot.decision.id,
                generation = decisionSnapshot.generation,
                selectedOptionId = decisionSnapshot.decision.selectedOptionId
            ),
            description = request.description,
            createdAt = request.createdAt,
            actionId = actionId
        )

        return when (val installed = orchestration.install(intent)) {
            is OrchestrationInstallResult.Installed -> {
                foundation.observability.record(
                    severity = DiagnosticSeverity.INFO,
                    code = "COGNITIVE_ORCHESTRATION_PROPOSAL_CREATED",
                    message = "cognitive orchestration proposal created",
                    context = foundation.rootContext(
                        operation = "proposeCognitiveOrchestration",
                        component = "CognitiveRuntime",
                        metadata = metadata(request, decisionSnapshot.decision.id.value, decisionSnapshot.generation.value, actionId)
                    ),
                    metadata = metadata(request, decisionSnapshot.decision.id.value, decisionSnapshot.generation.value, actionId)
                )
                CognitiveOrchestrationProposalResult.Proposed(installed.ownership)
            }

            is OrchestrationInstallResult.Rejected ->
                reject(request, CognitiveOrchestrationProposalFailure.ORCHESTRATION_INSTALL_FAILED)
        }
    }

    private fun reject(
        request: CognitiveOrchestrationProposalRequest,
        reason: CognitiveOrchestrationProposalFailure
    ): CognitiveOrchestrationProposalResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "COGNITIVE_ORCHESTRATION_PROPOSAL_REJECTED",
            message = "cognitive orchestration proposal rejected",
            context = foundation.rootContext(
                operation = "proposeCognitiveOrchestration",
                component = "CognitiveRuntime",
                metadata = mapOf(
                    "cognitiveTurnGeneration" to request.turn.generation.value.toString(),
                    "orchestrationIntentId" to request.intentId.value,
                    "rejectionReason" to reason.name
                )
            ),
            metadata = mapOf("rejectionReason" to reason.name)
        )
        return CognitiveOrchestrationProposalResult.Rejected(reason)
    }

    private fun metadata(
        request: CognitiveOrchestrationProposalRequest,
        decisionId: String,
        decisionGeneration: Long,
        actionId: ExecutionActionId
    ): Map<String, String> = mapOf(
        "cognitiveTurnGeneration" to request.turn.generation.value.toString(),
        "decisionId" to decisionId,
        "decisionGeneration" to decisionGeneration.toString(),
        "orchestrationIntentId" to request.intentId.value,
        "actionId" to actionId.value,
        "createdAt" to request.createdAt.toString()
    )
}
