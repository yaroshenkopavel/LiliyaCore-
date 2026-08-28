package pro.liliya.core.execution

import pro.liliya.core.authority.AuthorityDecision
import pro.liliya.core.authority.AuthorityManager
import pro.liliya.core.authority.AuthorityRequest
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

class ExecutionManager(
    private val authorityManager: AuthorityManager,
    private val executor: ExecutionExecutor,
    private val observability: CoreObservability
) {
    fun execute(request: ExecutionRequest, context: LogContext): ExecutionResult {
        val authority = authorityManager.authorize(
            AuthorityRequest(
                principal = request.principal,
                capability = request.capability,
                reason = request.reason,
                scope = request.scope
            ),
            context
        )

        if (authority is AuthorityDecision.Denied) {
            val rejected = ExecutionResult.Rejected(authority.reason)
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "EXECUTION_REJECTED",
                message = authority.reason,
                context = context,
                metadata = metadata(request) + ("rejectionReason" to authority.reason)
            )
            return rejected
        }

        val result = try {
            executor.execute(request, context)
        } catch (failure: Exception) {
            ExecutionResult.Failed(
                reason = failure.message?.takeIf { it.isNotBlank() } ?: "executor threw an exception",
                throwable = failure
            )
        }

        when (result) {
            ExecutionResult.Succeeded -> observability.record(
                severity = DiagnosticSeverity.INFO,
                code = "EXECUTION_SUCCEEDED",
                message = "execution succeeded",
                context = context,
                metadata = metadata(request)
            )

            is ExecutionResult.Rejected -> observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "EXECUTION_REJECTED",
                message = result.reason,
                context = context,
                metadata = metadata(request) + ("rejectionReason" to result.reason)
            )

            is ExecutionResult.Failed -> observability.record(
                severity = DiagnosticSeverity.ERROR,
                code = "EXECUTION_FAILED",
                message = result.reason,
                context = context,
                metadata = metadata(request) + ("failureReason" to result.reason),
                throwable = result.throwable
            )
        }

        return result
    }

    private fun metadata(request: ExecutionRequest): Map<String, String> = mapOf(
        "principal" to request.principal.value,
        "capabilityId" to request.capability.value,
        "scope" to request.scope.value,
        "actionId" to request.actionId.value,
        "reason" to request.reason
    )
}
