package pro.liliya.core.closedloop

import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.autonomy.ControlledAutonomyExecution
import pro.liliya.core.autonomy.ControlledAutonomyExecutionRequest
import pro.liliya.core.autonomy.ControlledAutonomyExecutionResult

fun interface ClosedLoopControlledExecutionPort {
    fun execute(request: ControlledAutonomyExecutionRequest): ControlledAutonomyExecutionResult
}

sealed interface GovernedClosedLoopActionResult {
    data class Succeeded(
        val action: ClosedLoopActionAttemptReference
    ) : GovernedClosedLoopActionResult

    data class Rejected(val reason: String) : GovernedClosedLoopActionResult {
        init { require(reason.isNotBlank()) { "closed-loop action rejection reason must not be blank" } }
    }

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : GovernedClosedLoopActionResult {
        init { require(reason.isNotBlank()) { "closed-loop action failure reason must not be blank" } }
    }
}

/**
 * Stage X execution adapter.
 *
 * This class owns no Authority and performs no action itself. Every invocation delegates to the
 * already-established ControlledAutonomyExecution boundary, which revalidates live provenance,
 * fresh Authority and controlled Execution. AuthorityPrincipal is deliberately an ephemeral
 * method argument and is never returned or stored in Stage X lifecycle records.
 */
class GovernedClosedLoopActionGateway(
    private val controlledExecution: ClosedLoopControlledExecutionPort
) {
    constructor(controlledExecution: ControlledAutonomyExecution) : this(
        ClosedLoopControlledExecutionPort { request -> controlledExecution.execute(request) }
    )

    fun execute(
        action: ClosedLoopActionAttemptReference,
        principal: AuthorityPrincipal
    ): GovernedClosedLoopActionResult {
        val request = ControlledAutonomyExecutionRequest(
            deliberationRequestId = action.deliberationRequestId,
            deliberationGeneration = action.deliberationGeneration,
            planningProposalId = action.planningProposalId,
            planningGeneration = action.planningGeneration,
            reasoningArtifactId = action.reasoningArtifactId,
            reasoningGeneration = action.reasoningGeneration,
            decisionId = action.decisionId,
            decisionGeneration = action.decisionGeneration,
            orchestrationIntentId = action.orchestrationIntentId,
            orchestrationGeneration = action.orchestrationGeneration,
            principal = principal,
            actionId = action.actionId
        )

        return when (val result = controlledExecution.execute(request)) {
            ControlledAutonomyExecutionResult.Succeeded ->
                GovernedClosedLoopActionResult.Succeeded(action)
            is ControlledAutonomyExecutionResult.Rejected ->
                GovernedClosedLoopActionResult.Rejected(result.reason)
            is ControlledAutonomyExecutionResult.Failed ->
                GovernedClosedLoopActionResult.Failed(result.reason, result.throwable)
        }
    }
}
