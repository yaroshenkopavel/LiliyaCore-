package pro.liliya.core.licensetransport

import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseVerificationResult

/** Builds REFRESH identity from a signature-verified license; caller owns policy acceptance. */
object LicenseRefreshRequestFactory {
    fun fromVerifiedLicense(
        verified: LicenseVerificationResult.Verified,
        requestId: LicenseServiceRequestId
    ): LicenseServiceTransportRequest = LicenseServiceTransportRequest(
        protocolVersion = LicenseServiceProtocolVersion(1),
        operation = LicenseServiceOperation.REFRESH,
        productId = verified.entitlement.productId,
        subjectReference = verified.entitlement.subject,
        requestId = requestId
    )
}
