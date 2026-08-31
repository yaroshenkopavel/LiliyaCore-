package pro.liliya.core.protectedmodel

import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Purpose-specific model-DEK resolution. A resolver may release a key only for the exact model and
 * DEK generation supplied by this protected-model open operation. This is not a generic key lookup.
 */
fun interface ProtectedModelDekResolver {
    fun resolveForProtectedModelOpen(
        model: ProtectedModelReference,
        dek: ModelDekReference
    ): SecretKey?
}

/**
 * Synchronous bounded plaintext handoff. The supplied bytes are cleared immediately after consume()
 * returns or throws; consumers must not retain the array as durable plaintext state.
 */
fun interface ProtectedModelPlaintextConsumer<T> {
    fun consume(model: ProtectedModelReference, plaintext: ByteArray): T
}

enum class ProtectedModelOpenFailure {
    PLAINTEXT_SIZE_OUT_OF_BOUNDS,
    PACKAGE_VERIFICATION_REJECTED,
    PACKAGE_VERIFICATION_FAILED,
    VERIFIED_MODEL_MISMATCH,
    MODEL_DEK_UNAVAILABLE,
    MODEL_DEK_REJECTED,
    AUTHENTICATED_DECRYPTION_FAILED,
    PLAINTEXT_SIZE_MISMATCH,
    CONSUMER_FAILED,
    PROVIDER_FAILED
}

sealed interface ProtectedModelOpenResult<out T> {
    data class Opened<T>(
        val model: ProtectedModelReference,
        val value: T
    ) : ProtectedModelOpenResult<T>

    data class Rejected(val reason: ProtectedModelOpenFailure) : ProtectedModelOpenResult<Nothing>

    data class Failed(
        val reason: ProtectedModelOpenFailure,
        val throwable: Throwable? = null
    ) : ProtectedModelOpenResult<Nothing> {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

/**
 * Authenticated protected-model open boundary.
 *
 * Order is deliberate: structural size bound -> package authenticity/integrity -> exact scoped DEK
 * resolution -> AES-256-GCM authentication/decryption -> exact plaintext-size check -> synchronous
 * consumer handoff. Successful open is still not License entitlement, Authority, or execution permission.
 */
class ProtectedModelPayloadLoader(
    private val verifier: ProtectedModelPackageVerifier,
    private val dekResolver: ProtectedModelDekResolver,
    private val maxPlaintextSizeBytes: Long
) {
    init {
        require(maxPlaintextSizeBytes > 0L) { "protected model plaintext bound must be positive" }
        require(maxPlaintextSizeBytes <= Int.MAX_VALUE.toLong()) {
            "protected model plaintext bound exceeds in-memory v0.1 limit"
        }
    }

    fun <T> open(
        envelope: ProtectedModelPackageEnvelope,
        ciphertext: ByteArray,
        consumer: ProtectedModelPlaintextConsumer<T>
    ): ProtectedModelOpenResult<T> {
        val manifest = envelope.manifest
        if (manifest.plaintextSizeBytes <= 0L ||
            manifest.plaintextSizeBytes > maxPlaintextSizeBytes ||
            manifest.plaintextSizeBytes > Int.MAX_VALUE.toLong()
        ) {
            return ProtectedModelOpenResult.Rejected(
                ProtectedModelOpenFailure.PLAINTEXT_SIZE_OUT_OF_BOUNDS
            )
        }

        when (val verification = verifier.verify(envelope, ciphertext)) {
            is ProtectedModelVerificationResult.Rejected ->
                return ProtectedModelOpenResult.Rejected(
                    ProtectedModelOpenFailure.PACKAGE_VERIFICATION_REJECTED
                )
            is ProtectedModelVerificationResult.Failed ->
                return ProtectedModelOpenResult.Failed(
                    ProtectedModelOpenFailure.PACKAGE_VERIFICATION_FAILED,
                    verification.throwable
                )
            is ProtectedModelVerificationResult.Verified -> {
                if (verification.model != manifest.model) {
                    return ProtectedModelOpenResult.Rejected(
                        ProtectedModelOpenFailure.VERIFIED_MODEL_MISMATCH
                    )
                }
            }
        }

        val key = try {
            dekResolver.resolveForProtectedModelOpen(manifest.model, manifest.modelDek)
        } catch (throwable: Throwable) {
            return ProtectedModelOpenResult.Failed(
                ProtectedModelOpenFailure.PROVIDER_FAILED,
                throwable
            )
        } ?: return ProtectedModelOpenResult.Rejected(
            ProtectedModelOpenFailure.MODEL_DEK_UNAVAILABLE
        )

        if (!key.algorithm.equals("AES", ignoreCase = true)) {
            return ProtectedModelOpenResult.Rejected(
                ProtectedModelOpenFailure.MODEL_DEK_REJECTED
            )
        }
        key.encoded?.let { encoded ->
            if (encoded.size != AES_256_KEY_SIZE_BYTES) {
                return ProtectedModelOpenResult.Rejected(
                    ProtectedModelOpenFailure.MODEL_DEK_REJECTED
                )
            }
        }

        var nonce: ByteArray? = null
        var authenticationTag: ByteArray? = null
        var cipherInput: ByteArray? = null
        var aad: ByteArray? = null
        var plaintext: ByteArray? = null
        return try {
            nonce = envelope.copyNonce()
            authenticationTag = envelope.copyAuthenticationTag()
            cipherInput = ByteArray(ciphertext.size + authenticationTag.size).also { combined ->
                ciphertext.copyInto(combined, destinationOffset = 0)
                authenticationTag.copyInto(combined, destinationOffset = ciphertext.size)
            }
            aad = ProtectedModelManifestCanonicalCodec.encode(manifest)

            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(manifest.encryptionProfile.authenticationTagSizeBits, nonce)
            )
            cipher.updateAAD(aad)
            plaintext = cipher.doFinal(cipherInput)

            if (plaintext.size.toLong() != manifest.plaintextSizeBytes) {
                return ProtectedModelOpenResult.Rejected(
                    ProtectedModelOpenFailure.PLAINTEXT_SIZE_MISMATCH
                )
            }

            val value = try {
                consumer.consume(manifest.model, plaintext)
            } catch (throwable: Throwable) {
                return ProtectedModelOpenResult.Failed(
                    ProtectedModelOpenFailure.CONSUMER_FAILED,
                    throwable
                )
            }
            ProtectedModelOpenResult.Opened(manifest.model, value)
        } catch (_: AEADBadTagException) {
            ProtectedModelOpenResult.Rejected(
                ProtectedModelOpenFailure.AUTHENTICATED_DECRYPTION_FAILED
            )
        } catch (throwable: GeneralSecurityException) {
            ProtectedModelOpenResult.Failed(
                ProtectedModelOpenFailure.PROVIDER_FAILED,
                throwable
            )
        } catch (throwable: Throwable) {
            ProtectedModelOpenResult.Failed(
                ProtectedModelOpenFailure.PROVIDER_FAILED,
                throwable
            )
        } finally {
            nonce?.fill(0)
            authenticationTag?.fill(0)
            cipherInput?.fill(0)
            aad?.fill(0)
            plaintext?.fill(0)
        }
    }

    private companion object {
        const val AES_256_KEY_SIZE_BYTES = 32
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
