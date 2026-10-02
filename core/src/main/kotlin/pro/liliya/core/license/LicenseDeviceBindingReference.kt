package pro.liliya.core.license

import java.security.MessageDigest
import java.util.Base64

object LicenseDeviceBindingReferenceFactory {
    fun create(
        installationId: String,
        deviceKeyFingerprint: String
    ): LicenseDeviceBindingReference {
        require(installationId.isNotBlank())
        require(deviceKeyFingerprint.isNotBlank())

        val payload = buildString {
            append(installationId)
            append('\u0000')
            append(deviceKeyFingerprint)
        }.toByteArray(Charsets.UTF_8)

        val hash = try {
            MessageDigest.getInstance("SHA-256").digest(payload)
        } finally {
            payload.fill(0)
        }

        return try {
            LicenseDeviceBindingReference(
                "device-binding-v1:" +
                    Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
            )
        } finally {
            hash.fill(0)
        }
    }
}
