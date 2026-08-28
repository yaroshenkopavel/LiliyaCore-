package pro.liliya.core.execution

import pro.liliya.core.authority.AuthorityDecision
import pro.liliya.core.authority.AuthorityManager
import pro.liliya.core.authority.AuthorityRequest
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal fun interface ExecutionAuthorizer {
    fun authorize(request: AuthorityRequest, context: LogContext): AuthorityDecision
}

class ExecutionManager internal constructor(
    private val authorizer: ExecutionAuthorizer,
    private val executor: ExecutionExecutor,
    actionCapabilities: Map<ExecutionActionId, CapabilityId>,
    private val observability: CoreObservability
) {
    constructor(
        authorityManager: AuthorityManager,
        executor: ExecutionExecutor,
        actionCapabilities: Map<ExecutionActionId, CapabilityId>,
        observability: CoreObservability
    ) : this(
        authorizer = ExecutionAuthorizer { request, context ->
            authorityManager.authorize(request, context)
        },
        executor = executor,
        actionCapabilities = actionCapabilities,
        observability = observability
    )

    private val actionCapabilities = actionCapabilities.toMap()

    fun execute(request: ExecutionRequest, context: LogContext): ExecutionResult {
        val requiredCapability = actionCapabilities[request.actionId]
        if (requiredCapability == null) {
            return reject(
                request = request,
                context = context,
                reason = "execution action ${request.actionId} is not registered"
            )
        }

        if (requiredCapability != request.capability) {
            return reject(
                request = request,
                context = context,
                reason = "execution action ${request.actionId} requires capability $requiredCapability"
            )
        }

        val authority = authorizer.authorize(
            AuthorityRequest(
                principal = request.principal,
                capability = requiredCapability,
                reason = request.reason,
                scope = request.scope
            ),
            context
        )

        if (authority is AuthorityDecision.Denied) {
            return reject(request, context, authority.reason)
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

    private fun reject(
        request: ExecutionRequest,
        context: LogContext,
        reason: String
    ): ExecutionResult.Rejected {
        val rejected = ExecutionResult.Rejected(reason)
        observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "EXECUTION_REJECTED",
            message = reason,
            context = context,
            metadata = metadata(request) + ("rejectionReason" to reason)
        )
        return rejected
    }

    private fun metadata(request: ExecutionRequest): Map<String, String> = mapOf(
        "principal" to request.principal.value,
        "capabilityId" to request.capability.value,
        "scope" to request.scope.value,
        "actionId" to request.actionId.value,
        "reason" to request.reason
    )
}
