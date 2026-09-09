package pro.liliya.android.runtime

import java.time.Instant
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.license.LicenseAuthorityRequest
import pro.liliya.core.license.LicenseFeature
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicensePolicyRequest
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseReplaySequence
import pro.liliya.core.license.LicenseRevocationEpoch
import pro.liliya.core.license.LicenseSubject

data class AndroidProductRuntimeStartupAdmissionInput(
    val productId: String,
    val feature: String,
    val subject: String?,
    val now: Instant,
    val minimumRevocationEpoch: Long,
    val minimumReplaySequence: Long?,
    val suspiciousTimeOrReplayState: Boolean,
    val principal: String,
    val capability: String,
    val authorityScope: String
)

data class AndroidProductRuntimeStartupAdmissionContracts(
    val licenseRequest: LicensePolicyRequest,
    val policyContext: LicensePolicyContext,
    val authorityRequest: LicenseAuthorityRequest
)

sealed interface AndroidProductRuntimeStartupAdmissionInputResult {
    data class Ready(
        val contracts: AndroidProductRuntimeStartupAdmissionContracts
    ) : AndroidProductRuntimeStartupAdmissionInputResult

    data object Rejected : AndroidProductRuntimeStartupAdmissionInputResult
}

/**
 * Maps one explicit product/security admission input into existing license and authority contracts.
 *
 * Admission Input != License Policy.
 * Admission Input != Current-Time Source.
 * Admission Input != Replay-State Source.
 * Admission Input != Authority Grant Minting.
 */
object AndroidProductRuntimeStartupAdmissionInputFactory {
    fun create(
        input: AndroidProductRuntimeStartupAdmissionInput
    ): AndroidProductRuntimeStartupAdmissionInputResult =
        try {
            AndroidProductRuntimeStartupAdmissionInputResult.Ready(
                AndroidProductRuntimeStartupAdmissionContracts(
                    licenseRequest = LicensePolicyRequest(
                        productId = LicenseProductId(input.productId),
                        feature = LicenseFeature(input.feature),
                        subject = input.subject?.let(::LicenseSubject)
                    ),
                    policyContext = LicensePolicyContext(
                        now = input.now,
                        minimumRevocationEpoch =
                            LicenseRevocationEpoch(input.minimumRevocationEpoch),
                        minimumReplaySequence =
                            input.minimumReplaySequence?.let(::LicenseReplaySequence),
                        suspiciousTimeOrReplayState = input.suspiciousTimeOrReplayState
                    ),
                    authorityRequest = LicenseAuthorityRequest(
                        principal = AuthorityPrincipal(input.principal),
                        capability = CapabilityId(input.capability),
                        scope = AuthorityScope(input.authorityScope)
                    )
                )
            )
        } catch (_: IllegalArgumentException) {
            AndroidProductRuntimeStartupAdmissionInputResult.Rejected
        }
}
