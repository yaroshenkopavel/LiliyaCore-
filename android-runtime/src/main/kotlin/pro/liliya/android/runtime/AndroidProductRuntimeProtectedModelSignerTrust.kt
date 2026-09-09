package pro.liliya.android.runtime

import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import pro.liliya.core.protectedmodel.ProtectedModelSignatureAlgorithm
import pro.liliya.core.protectedmodel.ProtectedModelSignerId
import pro.liliya.core.protectedmodel.ProtectedModelSignerResolver

class AndroidProductRuntimeProtectedModelSignerTrustKey(
    val signerId: String,
    material: ByteArray
) {
    private val materialBytes = material.copyOf()

    init {
        require(signerId.isNotBlank()) { "protected-model signer id must not be blank" }
        require(materialBytes.isNotEmpty()) {
            "protected-model signer key material must not be empty"
        }
    }

    fun copyMaterial(): ByteArray = materialBytes.copyOf()

    override fun toString(): String =
        "AndroidProductRuntimeProtectedModelSignerTrustKey(signerId=<redacted>, material=<redacted>)"
}

sealed interface AndroidProductRuntimeProtectedModelSignerTrustResult {
    data class Ready(
        val resolver: ProtectedModelSignerResolver
    ) : AndroidProductRuntimeProtectedModelSignerTrustResult

    data object Rejected : AndroidProductRuntimeProtectedModelSignerTrustResult
}

/**
 * Builds one exact immutable Ed25519 protected-model signer trust resolver.
 *
 * Signer Trust != Model DEK.
 * Signer Trust != Key Discovery.
 * Signer Trust != Network Fetch.
 * Signer Trust != Package Selection.
 */
object AndroidProductRuntimeProtectedModelSignerTrust {
    fun create(
        keys: List<AndroidProductRuntimeProtectedModelSignerTrustKey>
    ): AndroidProductRuntimeProtectedModelSignerTrustResult {
        if (keys.isEmpty()) {
            return AndroidProductRuntimeProtectedModelSignerTrustResult.Rejected
        }

        val parsed = LinkedHashMap<ProtectedModelSignerId, PublicKey>()
        return try {
            for (key in keys) {
                val id = ProtectedModelSignerId(key.signerId)
                if (parsed.containsKey(id)) {
                    return AndroidProductRuntimeProtectedModelSignerTrustResult.Rejected
                }

                val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                    X509EncodedKeySpec(key.copyMaterial())
                )
                parsed[id] = publicKey
            }

            val exact = parsed.toMap()
            AndroidProductRuntimeProtectedModelSignerTrustResult.Ready(
                ProtectedModelSignerResolver { signerId, algorithm ->
                    if (algorithm != ProtectedModelSignatureAlgorithm.ED25519) {
                        null
                    } else {
                        exact[signerId]
                    }
                }
            )
        } catch (_: Exception) {
            AndroidProductRuntimeProtectedModelSignerTrustResult.Rejected
        }
    }
}
