package pro.liliya.core.authority

import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

class AuthorityManager(
    private val policy: AuthorityPolicy,
    private val observability: CoreObservability
) {
    fun authorize(request: AuthorityRequest, context: LogContext): AuthorityDecision {
        val decision = policy.decide(request)
        when (decision) {
            AuthorityDecision.Granted -> observability.record(
                severity = DiagnosticSeverity.INFO,
                code = "AUTHORITY_GRANTED",
                message = "capability authority granted",
                context = context,
                metadata = metadata(request)
            )

            is AuthorityDecision.Denied -> observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "AUTHORITY_DENIED",
                message = decision.reason,
                context = context,
                metadata = metadata(request) + ("denialReason" to decision.reason)
            )
        }
        return decision
    }

    private fun metadata(request: AuthorityRequest): Map<String, String> = mapOf(
        "principal" to request.principal.value,
        "capabilityId" to request.capability.value,
        "scope" to request.scope.value,
        "reason" to request.reason
    )
}
