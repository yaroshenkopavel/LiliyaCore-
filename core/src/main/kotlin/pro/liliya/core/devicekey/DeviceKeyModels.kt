package pro.liliya.core.devicekey

import java.time.Instant

@JvmInline
value class DeviceKeyId(val value: String) {
    init { require(value.isNotBlank()) { "device key id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class DeviceKeyGeneration(val value: Long) {
    init { require(value > 0L) { "device key generation must be positive" } }
    override fun toString(): String = value.toString()
}

@JvmInline
value class DeviceKeyAlgorithm(val value: String) {
    init { require(value.isNotBlank()) { "device key algorithm must not be blank" } }
    override fun toString(): String = value
}

/** Opaque platform key-instance evidence. It is structural identity evidence, never permission. */
@JvmInline
value class DeviceKeyPlatformReference(val value: String) {
    init { require(value.isNotBlank()) { "device key platform reference must not be blank" } }
    override fun toString(): String = "DeviceKeyPlatformReference([redacted])"
}

enum class DeviceKeySecurityLevel {
    UNKNOWN,
    SOFTWARE,
    TRUSTED_ENVIRONMENT,
    STRONGBOX;

    val hardwareBacked: Boolean
        get() = this == TRUSTED_ENVIRONMENT || this == STRONGBOX
}

enum class DeviceKeyCapability {
    SIGN_CHALLENGE,
    UNWRAP_WRAPPED_KEY
}

/**
 * Requested security level is a minimum accepted level for this exact operation.
 * A lower level is never selected by a fallback flag. If a stronger request is unavailable,
 * the caller must issue a new explicit request for the lower level.
 */
data class DeviceKeyProfile(
    val algorithm: DeviceKeyAlgorithm,
    val requestedSecurityLevel: DeviceKeySecurityLevel,
    val capabilities: Set<DeviceKeyCapability>
) {
    init {
        require(requestedSecurityLevel != DeviceKeySecurityLevel.UNKNOWN) {
            "requested device key security level must be explicit"
        }
        require(capabilities.isNotEmpty()) { "device key capabilities must not be empty" }
    }
}

data class DeviceKeyCreationRequest(
    val id: DeviceKeyId,
    val profile: DeviceKeyProfile
)

data class DeviceKeyState(
    val id: DeviceKeyId,
    val algorithm: DeviceKeyAlgorithm,
    val securityLevel: DeviceKeySecurityLevel,
    val capabilities: Set<DeviceKeyCapability>,
    val createdAt: Instant,
    val platformReference: DeviceKeyPlatformReference? = null
) {
    init {
        require(securityLevel != DeviceKeySecurityLevel.UNKNOWN) {
            "ready device key security level must be known"
        }
        require(capabilities.isNotEmpty()) { "ready device key capabilities must not be empty" }
    }

    override fun toString(): String =
        "DeviceKeyState(id=$id, algorithm=$algorithm, securityLevel=$securityLevel, " +
            "capabilities=${capabilities.sortedBy { it.name }}, createdAt=$createdAt, " +
            "platformReference=$platformReference)"
}

data class DeviceKeySnapshot(
    val state: DeviceKeyState,
    val generation: DeviceKeyGeneration
)

enum class DeviceKeyFailureCategory {
    INVALID_REQUEST,
    UNSUPPORTED_PROFILE,
    REQUIRED_SECURITY_LEVEL_UNAVAILABLE,
    KEY_MISSING,
    KEY_INVALIDATED,
    KEY_TEMPORARILY_UNAVAILABLE,
    MALFORMED_METADATA,
    PLATFORM_REJECTED,
    AUTHENTICATION_REQUIRED,
    STALE_OWNERSHIP,
    CLEANUP_FAILED
}

sealed interface DeviceKeyOperationResult<out T> {
    data class Success<T>(val value: T) : DeviceKeyOperationResult<T>
    data class Rejected(val category: DeviceKeyFailureCategory) : DeviceKeyOperationResult<Nothing>
    data class Failed(
        val category: DeviceKeyFailureCategory,
        val throwable: Throwable? = null
    ) : DeviceKeyOperationResult<Nothing> {
        override fun toString(): String =
            "Failed(category=$category, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}
