package pro.liliya.core.authority

import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

class AuthorityDelegationManager(
    private val policy: AuthorityDelegationPolicy,
    private val observability: CoreObservability
) {
    fun delegate(
        request: AuthorityDelegationRequest,
        context: LogContext
    ): AuthorityDelegationDecision {
        val decision = policy.decide(request)
        when (decision) {
            is AuthorityDelegationDecision.Granted -> observability.record(
                severity = DiagnosticSeverity.INFO,
                code = "AUTHORITY_DELEGATION_GRANTED",
                message = "authority delegation granted",
                context = context,
                metadata = metadata(request) + mapOf(
                    "expiresAt" to (decision.grant.expiresAt?.toString() ?: "unbounded")
                )
            )

            is AuthorityDelegationDecision.Denied -> observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "AUTHORITY_DELEGATION_DENIED",
                message = decision.reason,
                context = context,
                metadata = metadata(request) + ("denialReason" to decision.reason)
            )
        }
        return decision
    }

    private fun metadata(request: AuthorityDelegationRequest): Map<String, String> = mapOf(
        "delegator" to request.delegator.value,
        "delegate" to request.delegate.value,
        "capabilityId" to request.capability.value,
        "scope" to request.scope.value,
        "reason" to request.reason
    )
}
