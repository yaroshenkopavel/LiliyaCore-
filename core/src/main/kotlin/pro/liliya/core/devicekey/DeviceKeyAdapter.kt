package pro.liliya.core.devicekey

import java.time.Instant

/**
 * Platform boundary for device-key lifecycle operations.
 *
 * Implementations must return the actual properties observed from the platform.
 * They must not expose private key material through this API.
 */
interface DeviceKeyAdapter {
    fun create(
        request: DeviceKeyCreationRequest,
        createdAt: Instant
    ): DeviceKeyOperationResult<DeviceKeyState>

    fun resolve(id: DeviceKeyId): DeviceKeyOperationResult<DeviceKeyState>

    fun retire(id: DeviceKeyId): DeviceKeyOperationResult<Unit>
}

internal object DeviceKeyProfilePolicy {
    fun accepts(
        requested: DeviceKeyProfile,
        actualSecurityLevel: DeviceKeySecurityLevel,
        actualAlgorithm: DeviceKeyAlgorithm,
        actualCapabilities: Set<DeviceKeyCapability>
    ): Boolean {
        if (actualSecurityLevel == DeviceKeySecurityLevel.UNKNOWN) return false
        if (actualAlgorithm != requested.algorithm) return false
        if (!actualCapabilities.containsAll(requested.capabilities)) return false

        return when (requested.requestedSecurityLevel) {
            DeviceKeySecurityLevel.UNKNOWN -> false
            DeviceKeySecurityLevel.SOFTWARE ->
                actualSecurityLevel == DeviceKeySecurityLevel.SOFTWARE

            DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT -> when (requested.fallbackPolicy) {
                DeviceKeyFallbackPolicy.FAIL_CLOSED,
                DeviceKeyFallbackPolicy.ALLOW_TRUSTED_ENVIRONMENT ->
                    actualSecurityLevel == DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT ||
                        actualSecurityLevel == DeviceKeySecurityLevel.STRONGBOX

                DeviceKeyFallbackPolicy.ALLOW_SOFTWARE ->
                    actualSecurityLevel != DeviceKeySecurityLevel.UNKNOWN
            }

            DeviceKeySecurityLevel.STRONGBOX -> when (requested.fallbackPolicy) {
                DeviceKeyFallbackPolicy.FAIL_CLOSED ->
                    actualSecurityLevel == DeviceKeySecurityLevel.STRONGBOX

                DeviceKeyFallbackPolicy.ALLOW_TRUSTED_ENVIRONMENT ->
                    actualSecurityLevel == DeviceKeySecurityLevel.STRONGBOX ||
                        actualSecurityLevel == DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT

                DeviceKeyFallbackPolicy.ALLOW_SOFTWARE ->
                    actualSecurityLevel != DeviceKeySecurityLevel.UNKNOWN
            }
        }
    }
}
