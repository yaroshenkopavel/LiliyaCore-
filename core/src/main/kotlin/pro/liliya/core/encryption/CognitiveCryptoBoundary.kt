package pro.liliya.core.encryption

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/** Exact 256-bit cognitive DEK material. Secret bytes are detached and never rendered. */
class CognitiveDekMaterial(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.size == 32) { "cognitive DEK material must be exactly 32 bytes" }
    }

    fun copyBytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is CognitiveDekMaterial && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "CognitiveDekMaterial(<redacted:${value.size} bytes>)"
}

/** Plaintext payload wrapper used only at the bounded encryption/decryption boundary. */
class CognitivePlaintext(bytes: ByteArray) {
    private val value = bytes.copyOf()

    fun copyBytes(): ByteArray = value.copyOf()
    val size: Int get() = value.size

    override fun equals(other: Any?): Boolean =
        other is CognitivePlaintext && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "CognitivePlaintext(<redacted:${value.size} bytes>)"
}

class CognitiveNonce(
    profile: CognitiveEncryptionProfile,
    bytes: ByteArray
) {
    private val value = bytes.copyOf()

    init {
        require(value.size == profile.nonceSizeBytes) { "invalid cognitive nonce size" }
    }

    fun copyBytes(): ByteArray = value.copyOf()
    val size: Int get() = value.size

    override fun equals(other: Any?): Boolean =
        other is CognitiveNonce && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "CognitiveNonce(<redacted:${value.size} bytes>)"
}

/** Canonical authenticated structural binding. Bytes are detached and redacted. */
class CognitiveAssociatedData(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init { require(value.isNotEmpty()) { "cognitive associated data must not be empty" } }

    fun copyBytes(): ByteArray = value.copyOf()
    val size: Int get() = value.size

    override fun equals(other: Any?): Boolean =
        other is CognitiveAssociatedData && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "CognitiveAssociatedData(<redacted:${value.size} bytes>)"
}

/** Ciphertext/tag provider output. It is detached and never rendered as raw bytes. */
class CognitiveAeadSealedData(
    ciphertext: ByteArray,
    authenticationTag: ByteArray
) {
    private val ciphertextBytes = ciphertext.copyOf()
    private val tagBytes = authenticationTag.copyOf()

    init {
        require(tagBytes.isNotEmpty()) { "cognitive authentication tag must not be empty" }
    }

    fun copyCiphertext(): ByteArray = ciphertextBytes.copyOf()
    fun copyAuthenticationTag(): ByteArray = tagBytes.copyOf()

    override fun toString(): String =
        "CognitiveAeadSealedData(ciphertext=<redacted:${ciphertextBytes.size} bytes>, " +
            "authenticationTag=<redacted:${tagBytes.size} bytes>)"
}

/**
 * Narrow cognitive AEAD seam. This is intentionally not a generic crypto executor.
 * Implementations must honor the exact allowlisted profile and authenticate associatedData.
 */
interface CognitiveAeadProvider {
    fun seal(
        profile: CognitiveEncryptionProfile,
        dek: CognitiveDekMaterial,
        nonce: CognitiveNonce,
        associatedData: CognitiveAssociatedData,
        plaintext: CognitivePlaintext
    ): CognitiveEncryptionResult<CognitiveAeadSealedData>

    fun open(
        profile: CognitiveEncryptionProfile,
        dek: CognitiveDekMaterial,
        nonce: CognitiveNonce,
        associatedData: CognitiveAssociatedData,
        sealed: CognitiveAeadSealedData
    ): CognitiveEncryptionResult<CognitivePlaintext>
}

/** Production implementations must provide cryptographically secure, per-operation nonce generation. */
interface CognitiveNonceSource {
    fun next(profile: CognitiveEncryptionProfile): CognitiveEncryptionResult<CognitiveNonce>
}

/** Canonical, length-prefixed AAD encoding. No ambient clock/RNG/global mutable state is used. */
object CognitiveAssociatedDataEncoder {
    private const val FORMAT_VERSION = 1

    fun encode(
        envelopeVersion: CognitiveEnvelopeVersion,
        profile: CognitiveEncryptionProfile,
        binding: CognitivePayloadBinding
    ): CognitiveAssociatedData {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(FORMAT_VERSION)
            out.writeInt(envelopeVersion.value)
            writeString(out, profile.algorithm.name)
            out.writeInt(profile.keySizeBits)
            out.writeInt(profile.nonceSizeBytes)
            out.writeInt(profile.authenticationTagSizeBits)
            writeString(out, binding.storeId.value)
            writeString(out, binding.entityId.value)
            out.writeLong(binding.entityGeneration.value)
            writeString(out, binding.schemaId.value)
            out.writeInt(binding.schemaVersion.value)
            writeString(out, binding.dek.id.value)
            out.writeLong(binding.dek.generation.value)
        }
        return CognitiveAssociatedData(buffer.toByteArray())
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }
}
