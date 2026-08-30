package pro.liliya.core.license

import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

class LicensePolicyComposition(
    private val foundation: FoundationComposition,
    private val policy: LicensePolicy = LicensePolicy()
) {
    fun evaluate(
        verified: LicenseVerificationResult.Verified,
        request: LicensePolicyRequest,
        context: LicensePolicyContext
    ): LicenseDecision {
        val operationContext = foundation.rootContext(
            operation = "evaluateLicensePolicy",
            component = "License",
            metadata = mapOf(
                "licenseId" to verified.entitlement.id.value,
                "licenseProductId" to request.productId.value,
                "licenseFeature" to request.feature.value,
                "licenseVersion" to verified.entitlement.version.value.toString(),
                "licenseRevocationEpoch" to verified.entitlement.revocationEpoch.value.toString()
            )
        )
        val decision = policy.evaluate(verified, request, context)
        when (decision) {
            is LicenseDecision.Entitled -> foundation.observability.record(
                severity = DiagnosticSeverity.INFO,
                code = "LICENSE_POLICY_ENTITLED",
                message = "license policy entitled request",
                context = operationContext,
                metadata = mapOf(
                    "licenseId" to decision.receipt.licenseId.value,
                    "licenseProductId" to decision.receipt.productId.value,
                    "licenseFeature" to decision.receipt.feature.value,
                    "licenseVersion" to decision.receipt.licenseVersion.value.toString(),
                    "licenseRevocationEpoch" to decision.receipt.revocationEpoch.value.toString()
                )
            )

            is LicenseDecision.Denied -> foundation.observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "LICENSE_POLICY_DENIED",
                message = "license policy denied request",
                context = operationContext,
                metadata = mapOf("licenseDenialReason" to decision.reason.name.lowercase())
            )
        }
        return decision
    }
}
