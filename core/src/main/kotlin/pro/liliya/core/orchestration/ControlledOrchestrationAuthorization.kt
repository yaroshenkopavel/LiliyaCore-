package pro.liliya.core.orchestration

import pro.liliya.core.authority.AuthorityDecision
import pro.liliya.core.authority.AuthorityRequest
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.execution.ExecutionActionId
import pro.liliya.core.foundation.FoundationComposition

/**
 * Fresh authorization evidence for one exact orchestration preflight.
 * It is evidence of one authorization decision only and is never durable permission.
 */
class OrchestrationAuthorizationEvidence internal constructor(
    val preflight: OrchestrationExecutionPreflightEvidence,
    val authorityRequest: AuthorityRequest
) {
    override fun toString(): String =
        "OrchestrationAuthorizationEvidence(" +
            "intentId=${preflight.request.intentId}, generation=${preflight.request.generation}, " +
            "decision=${preflight.decision}, principal=${authorityRequest.principal}, " +
            "actionId=${preflight.request.actionId}, capability=${authorityRequest.capability}, " +
            "scope=${authorityRequest.scope})"
}

sealed interface OrchestrationAuthorizationResult {
    data class Authorized(val evidence: OrchestrationAuthorizationEvidence) : OrchestrationAuthorizationResult

    data class Rejected(val reason: String) : OrchestrationAuthorizationResult {
        init { require(reason.isNotBlank()) { "orchestration authorization rejection reason must not be blank" } }
    }
}

class ControlledOrchestrationAuthorization(
    private val foundation: FoundationComposition,
    private val preflight: OrchestrationExecutionPreflight,
    private val capabilityAuthority: CapabilityAuthorityComposition,
    executionActionCapabilities: Map<ExecutionActionId, CapabilityId>
) {
    private val executionActionCapabilities = executionActionCapabilities.toMap()

    fun authorize(
        request: OrchestrationExecutionPreflightRequest
    ): OrchestrationAuthorizationResult {
        val context = foundation.rootContext(
            operation = "authorizeOrchestrationExecution",
            component = "Orchestration",
            metadata = mapOf(
                "orchestrationIntentId" to request.intentId.value,
                "orchestrationGeneration" to request.generation.value.toString(),
                "principal" to request.principal.value,
                "actionId" to request.actionId.value
            )
        )

        val ready = when (val result = preflight.check(request)) {
            is OrchestrationExecutionPreflightResult.Ready -> result.evidence
            is OrchestrationExecutionPreflightResult.Rejected ->
                return reject("preflight rejected: ${result.reason}", context)
        }

        val executionCapability = executionActionCapabilities[request.actionId]
            ?: return reject("execution action is not registered in execution capability mapping", context)

        if (executionCapability != ready.requiredCapability) {
            return reject("preflight capability does not match execution capability mapping", context)
        }

        val authorityRequest = AuthorityRequest(
            principal = request.principal,
            capability = executionCapability,
            scope = ready.requiredScope,
            reason = structuralReason(ready)
        )

        return when (val decision = capabilityAuthority.authorize(authorityRequest, context)) {
            AuthorityDecision.Granted -> {
                foundation.observability.record(
                    severity = DiagnosticSeverity.INFO,
                    code = "ORCHESTRATION_AUTHORIZATION_GRANTED",
                    message = "orchestration authorization granted",
                    context = context,
                    metadata = structuralMetadata(ready)
                )
                OrchestrationAuthorizationResult.Authorized(
                    OrchestrationAuthorizationEvidence(
                        preflight = ready,
                        authorityRequest = authorityRequest
                    )
                )
            }

            is AuthorityDecision.Denied -> reject(decision.reason, context)
        }
    }

    private fun structuralReason(evidence: OrchestrationExecutionPreflightEvidence): String =
        "orchestration intent ${evidence.request.intentId.value} generation ${evidence.request.generation.value} " +
            "for decision ${evidence.decision.decisionId.value} generation ${evidence.decision.generation.value} " +
            "action ${evidence.request.actionId.value}"

    private fun structuralMetadata(
        evidence: OrchestrationExecutionPreflightEvidence
    ): Map<String, String> = mapOf(
        "orchestrationIntentId" to evidence.request.intentId.value,
        "orchestrationGeneration" to evidence.request.generation.value.toString(),
        "decisionId" to evidence.decision.decisionId.value,
        "decisionGeneration" to evidence.decision.generation.value.toString(),
        "selectedDecisionOptionId" to evidence.decision.selectedOptionId.value,
        "principal" to evidence.request.principal.value,
        "actionId" to evidence.request.actionId.value,
        "requiredCapabilityId" to evidence.requiredCapability.value,
        "requiredScope" to evidence.requiredScope.value
    )

    private fun reject(
        reason: String,
        context: pro.liliya.core.logging.LogContext
    ): OrchestrationAuthorizationResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "ORCHESTRATION_AUTHORIZATION_REJECTED",
            message = reason,
            context = context,
            metadata = mapOf("rejectionReason" to reason)
        )
        return OrchestrationAuthorizationResult.Rejected(reason)
    }
}
