package pro.liliya.core.execution

import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.foundation.FoundationComposition

class ExecutionComposition(
    private val foundation: FoundationComposition,
    capabilityAuthority: CapabilityAuthorityComposition,
    executor: ExecutionExecutor,
    actionCapabilities: Map<ExecutionActionId, CapabilityId>
) {
    private val executionManager = ExecutionManager(
        authorizer = ExecutionAuthorizer { request, context ->
            capabilityAuthority.authorize(request, context)
        },
        executor = executor,
        actionCapabilities = actionCapabilities,
        observability = foundation.observability
    )

    fun execute(request: ExecutionRequest): ExecutionResult =
        executionManager.execute(
            request = request,
            context = foundation.rootContext(
                operation = "execute",
                component = "Execution",
                metadata = mapOf(
                    "principal" to request.principal.value,
                    "capabilityId" to request.capability.value,
                    "scope" to request.scope.value,
                    "actionId" to request.actionId.value
                )
            )
        )
}
