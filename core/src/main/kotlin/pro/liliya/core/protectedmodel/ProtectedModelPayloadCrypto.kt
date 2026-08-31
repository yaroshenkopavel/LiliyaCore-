package pro.liliya.core.protectedmodel

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ModelDekMaterial(material: ByteArray) {
    private val bytes = material.copyOf()

    init {
        require(bytes.size == 32) { "model DEK must be exactly 32 bytes" }
    }

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun toString(): String = "ModelDekMaterial(<redacted:${bytes.size} bytes>)"
}

fun interface ModelDekResolver {
    fun resolve(reference: ModelDekReference): ProtectedModelCryptoResult<ModelDekMaterial>
}

class ProtectedModelPlaintext internal constructor(bytes: ByteArray) {
    private val content = bytes.copyOf()

    val size: Int get() = content.size

    internal fun copyBytes(): ByteArray = content.copyOf()

    override fun toString(): String = "ProtectedModelPlaintext(<redacted:${content.size} bytes>)"
}

enum class ProtectedModelCryptoFailure {
    INVALID_REQUEST,
    MODEL_DEK_UNAVAILABLE,
    STALE_MODEL_DEK,
    AUTHENTICATION_FAILED,
    PLAINTEXT_SIZE_MISMATCH,
    PROVIDER_FAILED
}

sealed interface ProtectedModelCryptoResult<out T> {
    data class Success<T>(val value: T) : ProtectedModelCryptoResult<T>
    data class Rejected(val reason: ProtectedModelCryptoFailure) : ProtectedModelCryptoResult<Nothing>
    data class Failed(
        val reason: ProtectedModelCryptoFailure,
        val throwable: Throwable? = null
    ) : ProtectedModelCryptoResult<Nothing> {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

object ProtectedModelPayloadAssociatedData {
    fun encode(manifest: ProtectedModelManifest): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(AAD_VERSION)
                out.writeInt(manifest.formatVersion.value)
                writeString(out, manifest.model.packageId.value)
                out.writeLong(manifest.model.generation.value)
                writeString(out, manifest.profileId.value)
                out.writeLong(manifest.plaintextSizeBytes)
                out.writeLong(manifest.ciphertextSizeBytes)
                writeString(out, manifest.modelDek.id.value)
                out.writeLong(manifest.modelDek.generation.value)
                writeString(out, manifest.encryptionProfile.algorithm.name)
                out.writeInt(manifest.encryptionProfile.keySizeBits)
                out.writeInt(manifest.encryptionProfile.nonceSizeBytes)
                out.writeInt(manifest.encryptionProfile.authenticationTagSizeBits)
            }
        }.toByteArray()

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.encodeToByteArray()
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private const val AAD_VERSION = 1
}

/**
 * Purpose-specific protected-model payload opener. It authenticates one exact manifest/model/DEK binding.
 * It is not a generic decryptor and does not perform License, Authority or execution decisions.
 */
class ProtectedModelPayloadOpener(
    private val dekResolver: ModelDekResolver
) {
    fun open(
        envelope: ProtectedModelPackageEnvelope,
        ciphertext: ByteArray
    ): ProtectedModelCryptoResult<ProtectedModelPlaintext> {
        val manifest = envelope.manifest
        if (ciphertext.size.toLong() != manifest.ciphertextSizeBytes) {
            return ProtectedModelCryptoResult.Rejected(ProtectedModelCryptoFailure.INVALID_REQUEST)
        }
        if (manifest.encryptionProfile != ProtectedModelEncryptionProfile.AES_256_GCM) {
            return ProtectedModelCryptoResult.Rejected(ProtectedModelCryptoFailure.INVALID_REQUEST)
        }

        val resolved = dekResolver.resolve(manifest.modelDek)
        val material = when (resolved) {
            is ProtectedModelCryptoResult.Success -> resolved.value
            is ProtectedModelCryptoResult.Rejected -> return resolved
            is ProtectedModelCryptoResult.Failed -> return resolved
        }

        var keyBytes: ByteArray? = null
        var nonce: ByteArray? = null
        var tag: ByteArray? = null
        var aad: ByteArray? = null
        var combined: ByteArray? = null
        var plaintext: ByteArray? = null
        return try {
            keyBytes = material.copyBytes()
            nonce = envelope.copyNonce()
            tag = envelope.copyAuthenticationTag()
            aad = ProtectedModelPayloadAssociatedData.encode(manifest)
            combined = ByteArray(ciphertext.size + tag.size).also {
                ciphertext.copyInto(it, destinationOffset = 0)
                tag.copyInto(it, destinationOffset = ciphertext.size)
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(manifest.encryptionProfile.authenticationTagSizeBits, nonce)
            )
            cipher.updateAAD(aad)
            plaintext = cipher.doFinal(combined)

            if (plaintext.size.toLong() != manifest.plaintextSizeBytes) {
                ProtectedModelCryptoResult.Rejected(
                    ProtectedModelCryptoFailure.PLAINTEXT_SIZE_MISMATCH
                )
            } else {
                ProtectedModelCryptoResult.Success(ProtectedModelPlaintext(plaintext))
            }
        } catch (_: AEADBadTagException) {
            ProtectedModelCryptoResult.Rejected(ProtectedModelCryptoFailure.AUTHENTICATION_FAILED)
        } catch (throwable: Throwable) {
            ProtectedModelCryptoResult.Failed(
                ProtectedModelCryptoFailure.PROVIDER_FAILED,
                throwable
            )
        } finally {
            keyBytes?.fill(0)
            nonce?.fill(0)
            tag?.fill(0)
            aad?.fill(0)
            combined?.fill(0)
            plaintext?.fill(0)
        }
    }
}

sealed interface ProtectedModelLoaderResult<out T> {
    data class Loaded<T>(val value: T) : ProtectedModelLoaderResult<T>
    data class Rejected(val reason: ProtectedModelCryptoFailure) : ProtectedModelLoaderResult<Nothing>
    data class Failed(
        val reason: ProtectedModelCryptoFailure,
        val throwable: Throwable? = null
    ) : ProtectedModelLoaderResult<Nothing> {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

/**
 * Bounded plaintext handoff. The consumer receives one detached mutable buffer for the duration of the call;
 * that handoff buffer is cleared before this method returns.
 */
class ProtectedModelBoundedLoader(
    private val opener: ProtectedModelPayloadOpener,
    private val maxPlaintextSizeBytes: Long
) {
    init {
        require(maxPlaintextSizeBytes > 0L) { "maximum protected model plaintext size must be positive" }
    }

    fun <T> openAndUse(
        envelope: ProtectedModelPackageEnvelope,
        ciphertext: ByteArray,
        consumer: (ByteArray) -> T
    ): ProtectedModelLoaderResult<T> {
        val expectedSize = envelope.manifest.plaintextSizeBytes
        if (expectedSize <= 0L || expectedSize > maxPlaintextSizeBytes || expectedSize > Int.MAX_VALUE.toLong()) {
            return ProtectedModelLoaderResult.Rejected(ProtectedModelCryptoFailure.INVALID_REQUEST)
        }

        return when (val opened = opener.open(envelope, ciphertext)) {
            is ProtectedModelCryptoResult.Rejected -> ProtectedModelLoaderResult.Rejected(opened.reason)
            is ProtectedModelCryptoResult.Failed -> ProtectedModelLoaderResult.Failed(opened.reason, opened.throwable)
            is ProtectedModelCryptoResult.Success -> {
                val handoff = opened.value.copyBytes()
                try {
                    ProtectedModelLoaderResult.Loaded(consumer(handoff))
                } catch (throwable: Throwable) {
                    ProtectedModelLoaderResult.Failed(
                        ProtectedModelCryptoFailure.PROVIDER_FAILED,
                        throwable
                    )
                } finally {
                    handoff.fill(0)
                }
            }
        }
    }
}
