package pro.liliya.core.orchestration

import pro.liliya.core.execution.ExecutionComposition
import pro.liliya.core.execution.ExecutionRequest
import pro.liliya.core.execution.ExecutionResult

sealed interface ControlledOrchestrationExecutionResult {
    data object Succeeded : ControlledOrchestrationExecutionResult

    data class Rejected(val reason: String) : ControlledOrchestrationExecutionResult {
        init { require(reason.isNotBlank()) { "orchestration execution rejection reason must not be blank" } }
    }

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : ControlledOrchestrationExecutionResult {
        init { require(reason.isNotBlank()) { "orchestration execution failure reason must not be blank" } }
    }
}

/**
 * Controlled bridge from exact orchestration provenance into the frozen Execution boundary.
 *
 * Authorization evidence is never used as durable permission. Every call first performs a fresh
 * orchestration preflight + Authority decision, then delegates an ExecutionRequest to
 * ExecutionComposition, which independently revalidates action/capability mapping and performs
 * another fresh Authority decision immediately before the executor.
 */
class ControlledOrchestrationExecution(
    private val authorization: ControlledOrchestrationAuthorization,
    private val execution: ExecutionComposition
) {
    fun execute(
        request: OrchestrationExecutionPreflightRequest
    ): ControlledOrchestrationExecutionResult {
        val authorized = when (val result = authorization.authorize(request)) {
            is OrchestrationAuthorizationResult.Authorized -> result.evidence
            is OrchestrationAuthorizationResult.Rejected ->
                return ControlledOrchestrationExecutionResult.Rejected(
                    "authorization rejected: ${result.reason}"
                )
        }

        val authorityRequest = authorized.authorityRequest
        val executionRequest = ExecutionRequest(
            principal = authorityRequest.principal,
            capability = authorityRequest.capability,
            scope = authorityRequest.scope,
            actionId = authorized.preflight.request.actionId,
            reason = authorityRequest.reason
        )

        return when (val result = execution.execute(executionRequest)) {
            ExecutionResult.Succeeded -> ControlledOrchestrationExecutionResult.Succeeded
            is ExecutionResult.Rejected -> ControlledOrchestrationExecutionResult.Rejected(result.reason)
            is ExecutionResult.Failed -> ControlledOrchestrationExecutionResult.Failed(
                reason = result.reason,
                throwable = result.throwable
            )
        }
    }
}
