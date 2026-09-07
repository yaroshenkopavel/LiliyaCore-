package pro.liliya.core.licensetransport

import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceEnrollmentId
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseSubject

data class LicenseServiceTransportRequest(
    val protocolVersion: LicenseServiceProtocolVersion,
    val operation: LicenseServiceOperation,
    val productId: LicenseProductId,
    val subjectReference: LicenseSubject,
    val requestId: LicenseServiceRequestId,
    val enrollmentId: LicenseServiceEnrollmentId? = null
) {
    override fun toString(): String =
        "LicenseServiceTransportRequest(protocolVersion=$protocolVersion,operation=$operation," +
            "productId=$productId,subjectReference=<redacted>,requestId=<redacted>," +
            "enrollmentId=<redacted>)"
}

enum class LicenseRemoteServiceFailure {
    INVALID_REQUEST,
    UNSUPPORTED_PROTOCOL,
    AUTHENTICATION_REQUIRED,
    SUBJECT_NOT_ELIGIBLE,
    PRODUCT_NOT_ELIGIBLE,
    ENROLLMENT_REQUIRED,
    ENROLLMENT_REJECTED,
    DEVICE_PROOF_REJECTED,
    REFRESH_REJECTED,
    REPLAY_CONFLICT,
    REVOCATION_CONFLICT,
    IDEMPOTENCY_CONFLICT,
    SIGNING_KEY_UNAVAILABLE,
    ENTITLEMENT_SOURCE_UNAVAILABLE,
    INTERNAL_FAILURE
}

enum class LicenseClientTransportFailure {
    INVALID_LOCAL_REQUEST,
    CONNECT_FAILURE,
    TLS_FAILURE,
    TIMEOUT,
    CANCELLED,
    PROTOCOL_FAILURE,
    SERVICE_UNAVAILABLE
}

sealed interface LicenseClientTransportResult {
    data class Signed(val envelope: LicenseSignedEnvelope) : LicenseClientTransportResult
    data class ServiceRejected(val reason: LicenseRemoteServiceFailure) : LicenseClientTransportResult
    data class Failed(val reason: LicenseClientTransportFailure) : LicenseClientTransportResult
}
