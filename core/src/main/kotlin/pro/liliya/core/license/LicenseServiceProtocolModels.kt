package pro.liliya.core.license

@JvmInline
value class LicenseServiceProtocolVersion(val value: Long) {
    init {
        require(value > 0L) { "license service protocol version must be positive" }
    }

    override fun toString(): String = value.toString()
}

@JvmInline
value class LicenseServiceRequestId(val value: String) {
    init {
        require(value.isNotBlank()) { "license service request id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class LicenseServiceEnrollmentId(val value: String) {
    init {
        require(value.isNotBlank()) { "license service enrollment id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class LicenseServiceEvidenceProfile(val value: String) {
    init {
        require(value.isNotBlank()) { "license service evidence profile must not be blank" }
    }

    override fun toString(): String = value
}

enum class LicenseServiceOperation {
    ISSUE,
    REFRESH
}

enum class LicenseServiceEvidencePurpose {
    SECURITY_STATE
}

class LicenseServiceOpaquePayload private constructor(
    private val bytes: ByteArray
) {
    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is LicenseServiceOpaquePayload && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String =
        "LicenseServiceOpaquePayload(size=${bytes.size}, content=<redacted>)"

    companion object {
        fun of(bytes: ByteArray): LicenseServiceOpaquePayload {
            require(bytes.isNotEmpty()) { "license service payload must not be empty" }
            return LicenseServiceOpaquePayload(bytes.copyOf())
        }
    }
}

class LicenseServiceAuthenticationProof private constructor(
    private val bytes: ByteArray
) {
    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is LicenseServiceAuthenticationProof && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String =
        "LicenseServiceAuthenticationProof(size=${bytes.size}, value=<redacted>)"

    companion object {
        fun of(bytes: ByteArray): LicenseServiceAuthenticationProof {
            require(bytes.isNotEmpty()) { "license service authentication proof must not be empty" }
            return LicenseServiceAuthenticationProof(bytes.copyOf())
        }
    }
}

/**
 * Unverified protocol evidence only.
 *
 * Possession of this value does not mean that [payload] is authentic, current, replay-safe,
 * entitled, or authorized. A later verification boundary must authenticate the exact purpose,
 * evidence profile, signing key, payload and proof before any retained security state may advance.
 */
class LicenseServiceStateEnvelope(
    val protocolVersion: LicenseServiceProtocolVersion,
    val purpose: LicenseServiceEvidencePurpose,
    val profile: LicenseServiceEvidenceProfile,
    val signingKeyId: LicenseKeyId,
    val payload: LicenseServiceOpaquePayload,
    val proof: LicenseServiceAuthenticationProof
) {
    override fun toString(): String =
        "LicenseServiceStateEnvelope(protocolVersion=$protocolVersion, purpose=$purpose, " +
            "profile=$profile, signingKeyId=$signingKeyId, payload=<redacted>, proof=<redacted>)"
}

class LicenseServiceRequest(
    val protocolVersion: LicenseServiceProtocolVersion,
    val requestId: LicenseServiceRequestId,
    val operation: LicenseServiceOperation,
    val enrollmentId: LicenseServiceEnrollmentId? = null
) {
    override fun toString(): String =
        "LicenseServiceRequest(protocolVersion=$protocolVersion, requestId=$requestId, " +
            "operation=$operation, enrollmentId=$enrollmentId)"
}

/**
 * Structural service response only. Neither the entitlement envelope nor auxiliary state evidence
 * is accepted merely because it is present in this object.
 */
class LicenseServiceEntitlementResponse(
    val protocolVersion: LicenseServiceProtocolVersion,
    val requestId: LicenseServiceRequestId,
    val operation: LicenseServiceOperation,
    val entitlementEnvelope: LicenseSignedEnvelope,
    val serviceStateEnvelope: LicenseServiceStateEnvelope? = null
) {
    override fun toString(): String =
        "LicenseServiceEntitlementResponse(protocolVersion=$protocolVersion, requestId=$requestId, " +
            "operation=$operation, entitlementEnvelope=<redacted>, " +
            "serviceStateEnvelope=${serviceStateEnvelope?.let { "<redacted>" }})"
}
