package pro.liliya.android.runtime

import java.time.Instant
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.license.LicenseAuthorityRequest
import pro.liliya.core.license.LicenseFeature
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicensePolicyRequest
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseReplaySequence
import pro.liliya.core.license.LicenseRevocationEpoch
import pro.liliya.core.license.LicenseSubject

data class AndroidProductRuntimeStartupAuthorityGrantChoice(
    val capabilityId: String,
    val providerId: String,
    val scope: String
)

data class AndroidProductRuntimeStartupAdmissionChoice(
    val productId: String,
    val feature: String,
    val subject: String?,
    val now: Instant,
    val minimumRevocationEpoch: Long,
    val minimumReplaySequence: Long?,
    val suspiciousTimeOrReplayState: Boolean,
    val principal: String,
    val admissionCapabilityId: String,
    val admissionScope: String,
    val grants: List<AndroidProductRuntimeStartupAuthorityGrantChoice>
)

sealed interface AndroidProductRuntimeStartupAdmissionChoiceResult {
    data class Ready(
        val licenseRequest: LicensePolicyRequest,
        val policyContext: LicensePolicyContext,
        val authorityRequest: LicenseAuthorityRequest,
        val authorityPlan: AndroidProductRuntimeStartupAuthorityPlan,
        val principal: AuthorityPrincipal
    ) : AndroidProductRuntimeStartupAdmissionChoiceResult

    data object Rejected : AndroidProductRuntimeStartupAdmissionChoiceResult
}

/**
 * Maps one explicit product startup-admission choice into existing License and Capability Authority
 * contracts without inventing product IDs, features, capabilities, scopes or grants.
 *
 * Admission Choice != License Policy.
 * Admission Choice != Capability Discovery.
 * Admission Choice != Grant Minting Policy.
 */
object AndroidProductRuntimeStartupAdmissionChoiceFactory {
    fun create(
        choice: AndroidProductRuntimeStartupAdmissionChoice
    ): AndroidProductRuntimeStartupAdmissionChoiceResult {
        if (choice.grants.isEmpty()) {
            return AndroidProductRuntimeStartupAdmissionChoiceResult.Rejected
        }

        return try {
            val principal = AuthorityPrincipal(choice.principal)
            val admissionCapability = CapabilityId(choice.admissionCapabilityId)
            val admissionScope = AuthorityScope(choice.admissionScope)

            val descriptors = linkedMapOf<CapabilityId, CapabilityDescriptor>()
            val grants = mutableListOf<DirectAuthorityGrant>()
            var admissionGrantPresent = false

            choice.grants.forEach { grantChoice ->
                val capability = CapabilityId(grantChoice.capabilityId)
                val provider = CapabilityProviderId(grantChoice.providerId)
                val scope = AuthorityScope(grantChoice.scope)
                val existing = descriptors[capability]
                if (existing != null && existing.providerId != provider) {
                    return AndroidProductRuntimeStartupAdmissionChoiceResult.Rejected
                }
                descriptors[capability] = existing ?: CapabilityDescriptor(
                    id = capability,
                    providerId = provider
                )
                grants += DirectAuthorityGrant(
                    principal = principal,
                    capability = capability,
                    scope = scope
                )
                if (capability == admissionCapability && scope == admissionScope) {
                    admissionGrantPresent = true
                }
            }

            if (!admissionGrantPresent) {
                return AndroidProductRuntimeStartupAdmissionChoiceResult.Rejected
            }

            AndroidProductRuntimeStartupAdmissionChoiceResult.Ready(
                licenseRequest = LicensePolicyRequest(
                    productId = LicenseProductId(choice.productId),
                    feature = LicenseFeature(choice.feature),
                    subject = choice.subject?.let(::LicenseSubject)
                ),
                policyContext = LicensePolicyContext(
                    now = choice.now,
                    minimumRevocationEpoch = LicenseRevocationEpoch(
                        choice.minimumRevocationEpoch
                    ),
                    minimumReplaySequence = choice.minimumReplaySequence?.let(
                        ::LicenseReplaySequence
                    ),
                    suspiciousTimeOrReplayState = choice.suspiciousTimeOrReplayState
                ),
                authorityRequest = LicenseAuthorityRequest(
                    principal = principal,
                    capability = admissionCapability,
                    scope = admissionScope
                ),
                authorityPlan = AndroidProductRuntimeStartupAuthorityPlan(
                    capabilities = descriptors.values.toList(),
                    directGrants = grants.toList()
                ),
                principal = principal
            )
        } catch (_: IllegalArgumentException) {
            AndroidProductRuntimeStartupAdmissionChoiceResult.Rejected
        }
    }
}
