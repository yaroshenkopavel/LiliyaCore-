package pro.liliya.android.devicekey

import android.content.Context
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import pro.liliya.core.devicekey.AndroidKeystorePlatformResult
import pro.liliya.core.devicekey.DeviceKeyAlgorithm
import pro.liliya.core.devicekey.DeviceKeyCapability
import pro.liliya.core.devicekey.DeviceKeyCreationRequest
import pro.liliya.core.devicekey.DeviceKeyFailureCategory
import pro.liliya.core.devicekey.DeviceKeyId
import pro.liliya.core.devicekey.DeviceKeyProfile
import pro.liliya.core.devicekey.DeviceKeySecurityLevel

data class AndroidActivationDeviceBinding(
    val installationId: String,
    val deviceKeyFingerprint: String
) {
    init {
        require(installationId.isNotBlank())
        require(deviceKeyFingerprint.isNotBlank())
    }

    override fun toString(): String =
        "AndroidActivationDeviceBinding(installationId=<redacted>," +
            "deviceKeyFingerprint=<redacted>)"
}

sealed interface AndroidActivationDeviceBindingResult {
    data class Ready(val binding: AndroidActivationDeviceBinding) :
        AndroidActivationDeviceBindingResult

    data class Rejected(val reason: String) :
        AndroidActivationDeviceBindingResult

    data object MalformedLocalState : AndroidActivationDeviceBindingResult
}

class AndroidActivationDeviceBindingProvider(
    context: Context
) {
    private val appContext = context.applicationContext
    private val platform = AndroidSystemKeystorePlatform(appContext)
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )

    fun loadOrCreate(): AndroidActivationDeviceBindingResult {
        val installationId = loadOrCreateInstallationId()
            ?: return AndroidActivationDeviceBindingResult.MalformedLocalState

        val descriptor = when (val inspected = platform.inspect(KEY_ID)) {
            is AndroidKeystorePlatformResult.Success -> inspected.value
            is AndroidKeystorePlatformResult.Rejected -> {
                if (inspected.category != DeviceKeyFailureCategory.KEY_MISSING) {
                    return AndroidActivationDeviceBindingResult.Rejected(
                        inspected.category.name
                    )
                }
                when (
                    val generated = platform.generate(
                        request = DeviceKeyCreationRequest(KEY_ID, PROFILE),
                        createdAt = Instant.now()
                    )
                ) {
                    is AndroidKeystorePlatformResult.Success -> generated.value
                    is AndroidKeystorePlatformResult.Rejected ->
                        return AndroidActivationDeviceBindingResult.Rejected(
                            generated.category.name
                        )
                    is AndroidKeystorePlatformResult.Failed ->
                        return AndroidActivationDeviceBindingResult.Rejected(
                            generated.category.name
                        )
                }
            }
            is AndroidKeystorePlatformResult.Failed ->
                return AndroidActivationDeviceBindingResult.Rejected(
                    inspected.category.name
                )
        }

        val reference = descriptor.platformReference.value
        if (reference.isBlank()) {
            return AndroidActivationDeviceBindingResult.MalformedLocalState
        }

        return AndroidActivationDeviceBindingResult.Ready(
            AndroidActivationDeviceBinding(
                installationId = installationId,
                deviceKeyFingerprint = reference
            )
        )
    }

    private fun loadOrCreateInstallationId(): String? {
        preferences.getString(INSTALLATION_ID_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val generated = try {
            "installation-v1:" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } finally {
            bytes.fill(0)
        }

        synchronized(this) {
            preferences.getString(INSTALLATION_ID_KEY, null)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }

            if (!preferences.edit().putString(INSTALLATION_ID_KEY, generated).commit()) {
                return null
            }
        }
        return generated
    }

    private companion object {
        const val PREFERENCES = "liliya-activation-device-binding-v1"
        const val INSTALLATION_ID_KEY = "installation-id"
        val KEY_ID = DeviceKeyId("activation-device-key-v1")
        val PROFILE = DeviceKeyProfile(
            algorithm = DeviceKeyAlgorithm("EC-P256-SHA256"),
            requestedSecurityLevel = DeviceKeySecurityLevel.SOFTWARE,
            capabilities = setOf(DeviceKeyCapability.SIGN_CHALLENGE)
        )
    }
}
