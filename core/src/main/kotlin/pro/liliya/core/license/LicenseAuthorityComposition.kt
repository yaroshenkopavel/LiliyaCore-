package pro.liliya.core.license

import pro.liliya.core.authority.AuthorityDecision
import pro.liliya.core.authority.AuthorityManager
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityRequest
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

class LicenseAuthorityRequest(
    val principal: AuthorityPrincipal,
    val capability: CapabilityId,
    val scope: AuthorityScope = AuthorityScope.GLOBAL
)

sealed interface LicenseAuthorityDecision {
    data class LicenseDenied(val reason: LicenseDenialReason) : LicenseAuthorityDecision
    data object AuthorityDenied : LicenseAuthorityDecision
    data class Authorized(val licenseReceipt: LicenseDecisionReceipt) : LicenseAuthorityDecision
}

class LicenseAuthorityComposition(
    private val foundation: FoundationComposition,
    private val authorityManager: AuthorityManager,
    private val licensePolicy: LicensePolicy = LicensePolicy()
) {
    fun authorize(
        verified: LicenseVerificationResult.Verified,
        licenseRequest: LicensePolicyRequest,
        policyContext: LicensePolicyContext,
        authorityRequest: LicenseAuthorityRequest
    ): LicenseAuthorityDecision {
        val context = foundation.rootContext(
            operation = "authorizeLicensedCapability",
            component = "LicenseAuthority",
            metadata = mapOf(
                "licenseId" to verified.entitlement.id.value,
                "licenseProductId" to licenseRequest.productId.value,
                "licenseFeature" to licenseRequest.feature.value,
                "capabilityId" to authorityRequest.capability.value,
                "authorityScope" to authorityRequest.scope.value
            )
        )

        return when (val licenseDecision = licensePolicy.evaluate(verified, licenseRequest, policyContext)) {
            is LicenseDecision.Denied -> {
                foundation.observability.record(
                    severity = DiagnosticSeverity.WARNING,
                    code = "LICENSE_AUTHORITY_LICENSE_DENIED",
                    message = "licensed capability authorization denied by license policy",
                    context = context,
                    metadata = mapOf(
                        "licenseDenialReason" to licenseDecision.reason.name.lowercase()
                    )
                )
                LicenseAuthorityDecision.LicenseDenied(licenseDecision.reason)
            }

            is LicenseDecision.Entitled -> {
                val decision = authorityManager.authorize(
                    request = AuthorityRequest(
                        principal = authorityRequest.principal,
                        capability = authorityRequest.capability,
                        reason = "licensed protected operation",
                        scope = authorityRequest.scope
                    ),
                    context = context
                )
                when (decision) {
                    AuthorityDecision.Granted -> {
                        foundation.observability.record(
                            severity = DiagnosticSeverity.INFO,
                            code = "LICENSE_AUTHORITY_AUTHORIZED",
                            message = "licensed capability authorization granted",
                            context = context,
                            metadata = mapOf(
                                "licenseId" to licenseDecision.receipt.licenseId.value,
                                "licenseFeature" to licenseDecision.receipt.feature.value,
                                "capabilityId" to authorityRequest.capability.value,
                                "authorityScope" to authorityRequest.scope.value
                            )
                        )
                        LicenseAuthorityDecision.Authorized(licenseDecision.receipt)
                    }

                    is AuthorityDecision.Denied -> {
                        foundation.observability.record(
                            severity = DiagnosticSeverity.WARNING,
                            code = "LICENSE_AUTHORITY_AUTHORITY_DENIED",
                            message = "licensed capability authorization denied by authority",
                            context = context,
                            metadata = mapOf(
                                "capabilityId" to authorityRequest.capability.value,
                                "authorityScope" to authorityRequest.scope.value
                            )
                        )
                        LicenseAuthorityDecision.AuthorityDenied
                    }
                }
            }
        }
    }
}
