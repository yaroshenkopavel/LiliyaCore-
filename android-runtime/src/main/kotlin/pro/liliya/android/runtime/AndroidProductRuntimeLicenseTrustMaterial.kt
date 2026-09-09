package pro.liliya.android.runtime

import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseTrustedVerificationKey

class AndroidProductRuntimeLicenseTrustKey(
    val keyId: String,
    material: ByteArray
) {
    private val materialBytes = material.copyOf()

    init {
        require(keyId.isNotBlank()) { "license trust key id must not be blank" }
        require(materialBytes.isNotEmpty()) { "license trust key material must not be empty" }
    }

    fun copyMaterial(): ByteArray = materialBytes.copyOf()

    override fun toString(): String =
        "AndroidProductRuntimeLicenseTrustKey(keyId=<redacted>, material=<redacted>)"
}

sealed interface AndroidProductRuntimeLicenseTrustMaterialResult {
    data class Ready(
        val resolver: LicenseTrustedKeyResolver
    ) : AndroidProductRuntimeLicenseTrustMaterialResult

    data object Rejected : AndroidProductRuntimeLicenseTrustMaterialResult
}

/**
 * Builds one exact immutable entitlement-license trust resolver from explicit product-owned keys.
 *
 * License Trust Material != License Service State Trust.
 * License Trust Material != Key Discovery.
 * License Trust Material != Network Fetch.
 * License Trust Material != Signing Authority.
 */
object AndroidProductRuntimeLicenseTrustMaterial {
    private val algorithm = LicenseAlgorithm("ECDSA-P256-SHA256")

    fun create(
        keys: List<AndroidProductRuntimeLicenseTrustKey>
    ): AndroidProductRuntimeLicenseTrustMaterialResult {
        if (keys.isEmpty()) return AndroidProductRuntimeLicenseTrustMaterialResult.Rejected

        val resolved = LinkedHashMap<LicenseKeyId, LicenseTrustedVerificationKey>()
        return try {
            for (key in keys) {
                val id = LicenseKeyId(key.keyId)
                if (resolved.containsKey(id)) {
                    return AndroidProductRuntimeLicenseTrustMaterialResult.Rejected
                }
                resolved[id] = LicenseTrustedVerificationKey.of(
                    keyId = id,
                    algorithm = algorithm,
                    material = key.copyMaterial()
                )
            }

            val exact = resolved.toMap()
            AndroidProductRuntimeLicenseTrustMaterialResult.Ready(
                LicenseTrustedKeyResolver { keyId -> exact[keyId] }
            )
        } catch (_: IllegalArgumentException) {
            AndroidProductRuntimeLicenseTrustMaterialResult.Rejected
        }
    }
}
