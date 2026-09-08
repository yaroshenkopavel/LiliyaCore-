package pro.liliya.android.runtime

import pro.liliya.core.license.LicenseAuthorityComposition
import pro.liliya.core.license.LicenseAuthorityDecision
import pro.liliya.core.license.LicenseAuthorityRequest
import pro.liliya.core.license.LicenseDenialReason
import pro.liliya.core.license.LicenseDecisionReceipt
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicensePolicyRequest
import pro.liliya.core.license.LicenseVerificationResult

enum class AndroidProductRuntimeAdmissionFailure {
    AUTHORITY_DENIED,
    INTERNAL_FAILURE
}

sealed interface AndroidProductRuntimeAdmissionResult {
    data class LicenseDenied(
        val reason: LicenseDenialReason
    ) : AndroidProductRuntimeAdmissionResult

    data class Rejected(
        val reason: AndroidProductRuntimeAdmissionFailure
    ) : AndroidProductRuntimeAdmissionResult

    data class Admitted internal constructor(
        internal val ownership: AndroidProductRuntimeAdmissionOwnership,
        val licenseReceipt: LicenseDecisionReceipt
    ) : AndroidProductRuntimeAdmissionResult {
        override fun toString(): String =
            "AndroidProductRuntimeAdmissionResult.Admitted(" +
                "ownership=<redacted>,licenseReceipt=<redacted>)"
    }
}

/**
 * Opaque, process-local evidence that Product Runtime admission passed both existing License
 * policy and existing Authority authorization.
 *
 * Admission != License Ownership.
 * Admission != Authority Ownership.
 * Admission != Execution permission.
 */
class AndroidProductRuntimeAdmissionOwnership internal constructor() {
    override fun toString(): String = "AndroidProductRuntimeAdmissionOwnership(<redacted>)"
}

/**
 * Product-level admission boundary over the already-authoritative LicenseAuthorityComposition.
 *
 * This gate does not create License entitlements, Authority grants, capabilities or runtime state.
 */
object AndroidProductRuntimeAdmissionGate {

    fun admit(
        composition: LicenseAuthorityComposition,
        verified: LicenseVerificationResult.Verified,
        licenseRequest: LicensePolicyRequest,
        policyContext: LicensePolicyContext,
        authorityRequest: LicenseAuthorityRequest
    ): AndroidProductRuntimeAdmissionResult {
        val decision = try {
            composition.authorize(
                verified = verified,
                licenseRequest = licenseRequest,
                policyContext = policyContext,
                authorityRequest = authorityRequest
            )
        } catch (_: Exception) {
            return AndroidProductRuntimeAdmissionResult.Rejected(
                AndroidProductRuntimeAdmissionFailure.INTERNAL_FAILURE
            )
        }

        return when (decision) {
            is LicenseAuthorityDecision.LicenseDenied ->
                AndroidProductRuntimeAdmissionResult.LicenseDenied(decision.reason)

            LicenseAuthorityDecision.AuthorityDenied ->
                AndroidProductRuntimeAdmissionResult.Rejected(
                    AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED
                )

            is LicenseAuthorityDecision.Authorized ->
                AndroidProductRuntimeAdmissionResult.Admitted(
                    ownership = AndroidProductRuntimeAdmissionOwnership(),
                    licenseReceipt = decision.licenseReceipt
                )
        }
    }
}
