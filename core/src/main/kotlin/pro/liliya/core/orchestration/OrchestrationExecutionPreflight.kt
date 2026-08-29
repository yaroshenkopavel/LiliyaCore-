package pro.liliya.core.orchestration

import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.execution.ExecutionActionId
import pro.liliya.core.foundation.FoundationComposition

/** Trusted structural policy for one execution action. It grants no permission. */
data class OrchestrationActionPolicy(
    val capability: CapabilityId,
    val scope: AuthorityScope
)

data class OrchestrationExecutionPreflightRequest(
    val intentId: OrchestrationIntentId,
    val generation: OrchestrationGeneration,
    val principal: AuthorityPrincipal,
    val actionId: ExecutionActionId
)

/**
 * Evidence that exact structural provenance was live at preflight time.
 * This is not an Authority decision and must never be treated as durable permission.
 */
class OrchestrationExecutionPreflightEvidence internal constructor(
    val request: OrchestrationExecutionPreflightRequest,
    val decision: OrchestrationDecisionReference,
    val requiredCapability: CapabilityId,
    val requiredScope: AuthorityScope
) {
    override fun toString(): String =
        "OrchestrationExecutionPreflightEvidence(" +
            "intentId=${request.intentId}, generation=${request.generation}, " +
            "decision=$decision, principal=${request.principal}, actionId=${request.actionId}, " +
            "requiredCapability=$requiredCapability, requiredScope=$requiredScope)"
}

sealed interface OrchestrationExecutionPreflightResult {
    data class Ready(val evidence: OrchestrationExecutionPreflightEvidence) : OrchestrationExecutionPreflightResult
    data class Rejected(val reason: String) : OrchestrationExecutionPreflightResult {
        init { require(reason.isNotBlank()) { "preflight rejection reason must not be blank" } }
    }
}

class OrchestrationExecutionPreflight(
    private val foundation: FoundationComposition,
    private val orchestration: OrchestrationComposition,
    private val decisions: DecisionComposition,
    actionPolicies: Map<ExecutionActionId, OrchestrationActionPolicy>
) {
    private val actionPolicies = actionPolicies.toMap()

    fun check(request: OrchestrationExecutionPreflightRequest): OrchestrationExecutionPreflightResult {
        val context = foundation.rootContext(
            operation = "preflightOrchestrationExecution",
            component = "Orchestration",
            metadata = mapOf(
                "orchestrationIntentId" to request.intentId.value,
                "orchestrationGeneration" to request.generation.value.toString(),
                "principal" to request.principal.value,
                "actionId" to request.actionId.value
            )
        )

        val intentSnapshot = orchestration.inspect(request.intentId)
            ?: return reject("orchestration intent is not present", context)

        if (intentSnapshot.generation != request.generation) {
            return reject("orchestration intent generation is stale", context)
        }

        val decisionReference = intentSnapshot.intent.decision
        val decisionSnapshot = decisions.inspect(decisionReference.decisionId)
            ?: return reject("referenced decision is not present", context)

        if (decisionSnapshot.generation != decisionReference.generation) {
            return reject("referenced decision generation is stale", context)
        }

        if (decisionSnapshot.decision.selectedOptionId != decisionReference.selectedOptionId) {
            return reject("referenced selected decision option does not match", context)
        }

        val policy = actionPolicies[request.actionId]
            ?: return reject("orchestration execution action is not registered", context)

        val evidence = OrchestrationExecutionPreflightEvidence(
            request = request,
            decision = decisionReference,
            requiredCapability = policy.capability,
            requiredScope = policy.scope
        )

        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "ORCHESTRATION_EXECUTION_PREFLIGHT_READY",
            message = "orchestration execution preflight ready",
            context = context,
            metadata = mapOf(
                "decisionId" to decisionReference.decisionId.value,
                "decisionGeneration" to decisionReference.generation.value.toString(),
                "selectedDecisionOptionId" to decisionReference.selectedOptionId.value,
                "requiredCapabilityId" to policy.capability.value,
                "requiredScope" to policy.scope.value
            )
        )
        return OrchestrationExecutionPreflightResult.Ready(evidence)
    }

    private fun reject(
        reason: String,
        context: pro.liliya.core.logging.LogContext
    ): OrchestrationExecutionPreflightResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "ORCHESTRATION_EXECUTION_PREFLIGHT_REJECTED",
            message = reason,
            context = context,
            metadata = mapOf("rejectionReason" to reason)
        )
        return OrchestrationExecutionPreflightResult.Rejected(reason)
    }
}
