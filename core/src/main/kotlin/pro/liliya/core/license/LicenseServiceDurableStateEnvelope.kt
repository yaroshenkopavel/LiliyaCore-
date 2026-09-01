package pro.liliya.core.license

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets

private const val DURABLE_MAX_ID_BYTES = 4_096
private const val DURABLE_MAX_CIPHERTEXT_BYTES = 1_048_576
private const val DURABLE_MAX_ENVELOPE_BYTES = DURABLE_MAX_CIPHERTEXT_BYTES + 16_384

private fun durableUtf8Length(value: String, maximum: Int): Int? {
    if (value.isEmpty()) return null
    var size = 0
    var index = 0
    while (index < value.length) {
        val ch = value[index]
        val added = when {
            ch.code <= 0x7F -> 1
            ch.code <= 0x7FF -> 2
            Character.isHighSurrogate(ch) -> {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return null
                index += 1
                4
            }
            Character.isLowSurrogate(ch) -> return null
            else -> 3
        }
        if (size > maximum - added) return null
        size += added
        index += 1
    }
    return size
}

@JvmInline
value class LicenseServiceDurableStateEnvelopeVersion(val value: Int) {
    init {
        require(value > 0) { "license service durable state envelope version must be positive" }
    }
}

enum class LicenseServiceDurableStatePurpose {
    LICENSE_SERVICE_SECURITY_STATE
}

enum class LicenseServiceDurableStateEncryptionAlgorithm {
    AES_256_GCM
}

data class LicenseServiceDurableStateEncryptionProfile(
    val algorithm: LicenseServiceDurableStateEncryptionAlgorithm,
    val keySizeBits: Int,
    val nonceSizeBytes: Int,
    val authenticationTagSizeBits: Int
) {
    init {
        require(
            algorithm == LicenseServiceDurableStateEncryptionAlgorithm.AES_256_GCM &&
                keySizeBits == 256 &&
                nonceSizeBytes == 12 &&
                authenticationTagSizeBits == 128
        ) { "unsupported license service durable state encryption profile" }
    }

    companion object {
        val AES_256_GCM = LicenseServiceDurableStateEncryptionProfile(
            algorithm = LicenseServiceDurableStateEncryptionAlgorithm.AES_256_GCM,
            keySizeBits = 256,
            nonceSizeBytes = 12,
            authenticationTagSizeBits = 128
        )
    }
}

@JvmInline
value class LicenseServiceDurableStoreId(val value: String) {
    init {
        require(value.isNotBlank()) { "license service durable store id must not be blank" }
        require(durableUtf8Length(value, DURABLE_MAX_ID_BYTES) != null) {
            "license service durable store id exceeds bounds"
        }
    }

    override fun toString(): String = "LicenseServiceDurableStoreId([redacted])"
}

@JvmInline
value class LicenseServiceDurableStateProtectorId(val value: String) {
    init {
        require(value.isNotBlank()) { "license service durable state protector id must not be blank" }
        require(durableUtf8Length(value, DURABLE_MAX_ID_BYTES) != null) {
            "license service durable state protector id exceeds bounds"
        }
    }

    override fun toString(): String = "LicenseServiceDurableStateProtectorId([redacted])"
}

@JvmInline
value class LicenseServiceDurableStateProtectorGeneration(val value: Long) {
    init {
        require(value > 0L) { "license service durable state protector generation must be positive" }
    }

    override fun toString(): String = value.toString()
}

data class LicenseServiceDurableStateProtectorReference(
    val id: LicenseServiceDurableStateProtectorId,
    val generation: LicenseServiceDurableStateProtectorGeneration
)

/** Exact pre-seal binding. This exists before nonce/ciphertext/tag and is the AEAD AAD source. */
class LicenseServiceDurableStateBinding(
    val version: LicenseServiceDurableStateEnvelopeVersion,
    val purpose: LicenseServiceDurableStatePurpose,
    val profile: LicenseServiceDurableStateEncryptionProfile,
    val storeId: LicenseServiceDurableStoreId,
    val generation: LicenseServiceDurableStateGeneration,
    val backendRevision: LicenseServiceDurableBackendRevision,
    val protector: LicenseServiceDurableStateProtectorReference
) {
    init {
        require(version.value == 1) { "unsupported license service durable state envelope version" }
        require(purpose == LicenseServiceDurableStatePurpose.LICENSE_SERVICE_SECURITY_STATE) {
            "unsupported license service durable state purpose"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is LicenseServiceDurableStateBinding &&
            version == other.version &&
            purpose == other.purpose &&
            profile == other.profile &&
            storeId == other.storeId &&
            generation == other.generation &&
            backendRevision == other.backendRevision &&
            protector == other.protector

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + purpose.hashCode()
        result = 31 * result + profile.hashCode()
        result = 31 * result + storeId.hashCode()
        result = 31 * result + generation.hashCode()
        result = 31 * result + backendRevision.hashCode()
        result = 31 * result + protector.hashCode()
        return result
    }

    override fun toString(): String =
        "LicenseServiceDurableStateBinding(version=${version.value}, purpose=$purpose, profile=$profile, " +
            "storeId=[redacted], generation=$generation, backendRevision=$backendRevision, " +
            "protector=$protector)"
}

class LicenseServiceDurableStateEnvelope(
    val binding: LicenseServiceDurableStateBinding,
    nonce: ByteArray,
    ciphertext: ByteArray,
    authenticationTag: ByteArray
) {
    private val nonceBytes = checkedNonce(binding.profile, nonce)
    private val ciphertextBytes = checkedCiphertext(ciphertext)
    private val authenticationTagBytes = checkedAuthenticationTag(binding.profile, authenticationTag)

    fun copyNonce(): ByteArray = nonceBytes.copyOf()
    fun copyCiphertext(): ByteArray = ciphertextBytes.copyOf()
    fun copyAuthenticationTag(): ByteArray = authenticationTagBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is LicenseServiceDurableStateEnvelope &&
            binding == other.binding &&
            nonceBytes.contentEquals(other.nonceBytes) &&
            ciphertextBytes.contentEquals(other.ciphertextBytes) &&
            authenticationTagBytes.contentEquals(other.authenticationTagBytes)

    override fun hashCode(): Int {
        var result = binding.hashCode()
        result = 31 * result + nonceBytes.contentHashCode()
        result = 31 * result + ciphertextBytes.contentHashCode()
        result = 31 * result + authenticationTagBytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "LicenseServiceDurableStateEnvelope(binding=$binding, " +
            "nonce=<redacted:${nonceBytes.size} bytes>, ciphertext=<redacted:${ciphertextBytes.size} bytes>, " +
            "authenticationTag=<redacted:${authenticationTagBytes.size} bytes>)"

    private fun checkedNonce(
        profile: LicenseServiceDurableStateEncryptionProfile,
        bytes: ByteArray
    ): ByteArray {
        require(bytes.size == profile.nonceSizeBytes) {
            "invalid license service durable state nonce size"
        }
        return bytes.copyOf()
    }

    private fun checkedCiphertext(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty()) { "license service durable state ciphertext must not be empty" }
        require(bytes.size <= DURABLE_MAX_CIPHERTEXT_BYTES) {
            "license service durable state ciphertext exceeds bounds"
        }
        return bytes.copyOf()
    }

    private fun checkedAuthenticationTag(
        profile: LicenseServiceDurableStateEncryptionProfile,
        bytes: ByteArray
    ): ByteArray {
        require(bytes.size * 8 == profile.authenticationTagSizeBits) {
            "invalid license service durable state authentication tag size"
        }
        return bytes.copyOf()
    }
}

class LicenseServiceDurableStateEnvelopePayload private constructor(
    private val bytes: ByteArray
) {
    val size: Int get() = bytes.size

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is LicenseServiceDurableStateEnvelopePayload && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String =
        "LicenseServiceDurableStateEnvelopePayload(size=${bytes.size}, content=<redacted>)"

    companion object {
        fun of(bytes: ByteArray): LicenseServiceDurableStateEnvelopePayload {
            require(bytes.isNotEmpty()) { "license service durable state envelope payload must not be empty" }
            return LicenseServiceDurableStateEnvelopePayload(bytes.copyOf())
        }
    }
}

sealed interface LicenseServiceDurableStateEnvelopeEncodeResult {
    data class Encoded(val payload: LicenseServiceDurableStateEnvelopePayload) :
        LicenseServiceDurableStateEnvelopeEncodeResult

    data class Rejected(val reason: LicenseServiceDurableStateCodecRejection) :
        LicenseServiceDurableStateEnvelopeEncodeResult
}

sealed interface LicenseServiceDurableStateEnvelopeDecodeResult {
    data class Decoded(val envelope: LicenseServiceDurableStateEnvelope) :
        LicenseServiceDurableStateEnvelopeDecodeResult

    data class Rejected(val reason: LicenseServiceDurableStateCodecRejection) :
        LicenseServiceDurableStateEnvelopeDecodeResult
}

/** Canonical durable representation of the sealed licensing security-state envelope. */
object LicenseServiceDurableStateEnvelopeCanonicalCodec {
    private const val MAGIC = 0x4C534534 // LSE4
    private const val CODEC_VERSION = 1
    private const val PURPOSE_CODE = 1
    private const val ALGORITHM_CODE_AES_256_GCM = 1

    internal const val MAX_ID_BYTES = DURABLE_MAX_ID_BYTES
    internal const val MAX_CIPHERTEXT_BYTES = DURABLE_MAX_CIPHERTEXT_BYTES
    internal const val MAX_ENVELOPE_BYTES = DURABLE_MAX_ENVELOPE_BYTES

    fun encode(
        envelope: LicenseServiceDurableStateEnvelope
    ): LicenseServiceDurableStateEnvelopeEncodeResult {
        val binding = envelope.binding
        val storeIdBytes = binding.storeId.value.toByteArray(StandardCharsets.UTF_8)
        val protectorIdBytes = binding.protector.id.value.toByteArray(StandardCharsets.UTF_8)
        val nonce = envelope.copyNonce()
        val ciphertext = envelope.copyCiphertext()
        val tag = envelope.copyAuthenticationTag()

        val budget = EncodedBudget(MAX_ENVELOPE_BYTES)
        if (!budget.add(Int.SIZE_BYTES * 13 + Long.SIZE_BYTES * 3)) {
            return rejectedEncode(LicenseServiceDurableStateCodecRejection.BOUNDS_EXCEEDED)
        }
        if (!budget.add(storeIdBytes.size + protectorIdBytes.size + nonce.size + ciphertext.size + tag.size)) {
            return rejectedEncode(LicenseServiceDurableStateCodecRejection.BOUNDS_EXCEEDED)
        }

        val output = ByteArrayOutputStream(budget.used)
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(CODEC_VERSION)
            data.writeInt(binding.version.value)
            data.writeInt(PURPOSE_CODE)
            data.writeInt(ALGORITHM_CODE_AES_256_GCM)
            data.writeInt(binding.profile.keySizeBits)
            data.writeInt(binding.profile.nonceSizeBytes)
            data.writeInt(binding.profile.authenticationTagSizeBits)
            data.writeBoundedId(storeIdBytes)
            data.writeLong(binding.generation.value)
            data.writeLong(binding.backendRevision.value)
            data.writeBoundedId(protectorIdBytes)
            data.writeLong(binding.protector.generation.value)
            data.writeBoundedBytes(nonce)
            data.writeBoundedBytes(ciphertext)
            data.writeBoundedBytes(tag)
        }
        val bytes = output.toByteArray()
        if (bytes.size != budget.used || bytes.size > MAX_ENVELOPE_BYTES) {
            return rejectedEncode(LicenseServiceDurableStateCodecRejection.BOUNDS_EXCEEDED)
        }
        return LicenseServiceDurableStateEnvelopeEncodeResult.Encoded(
            LicenseServiceDurableStateEnvelopePayload.of(bytes)
        )
    }

    fun decode(
        payload: LicenseServiceDurableStateEnvelopePayload
    ): LicenseServiceDurableStateEnvelopeDecodeResult {
        if (payload.size > MAX_ENVELOPE_BYTES) {
            return rejectedDecode(LicenseServiceDurableStateCodecRejection.BOUNDS_EXCEEDED)
        }

        val original = payload.copyBytes()
        return try {
            val input = ByteArrayInputStream(original)
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) {
                return rejectedDecode(LicenseServiceDurableStateCodecRejection.MALFORMED)
            }
            if (data.readInt() != CODEC_VERSION) {
                return rejectedDecode(LicenseServiceDurableStateCodecRejection.UNSUPPORTED_VERSION)
            }
            val envelopeVersion = LicenseServiceDurableStateEnvelopeVersion(data.readInt())
            if (envelopeVersion.value != 1) {
                return rejectedDecode(LicenseServiceDurableStateCodecRejection.UNSUPPORTED_VERSION)
            }
            if (data.readInt() != PURPOSE_CODE) {
                return rejectedDecode(LicenseServiceDurableStateCodecRejection.MALFORMED)
            }
            if (data.readInt() != ALGORITHM_CODE_AES_256_GCM) {
                return rejectedDecode(LicenseServiceDurableStateCodecRejection.MALFORMED)
            }
            val profile = LicenseServiceDurableStateEncryptionProfile(
                algorithm = LicenseServiceDurableStateEncryptionAlgorithm.AES_256_GCM,
                keySizeBits = data.readInt(),
                nonceSizeBytes = data.readInt(),
                authenticationTagSizeBits = data.readInt()
            )
            val binding = LicenseServiceDurableStateBinding(
                version = envelopeVersion,
                purpose = LicenseServiceDurableStatePurpose.LICENSE_SERVICE_SECURITY_STATE,
                profile = profile,
                storeId = LicenseServiceDurableStoreId(data.readBoundedId(input)),
                generation = LicenseServiceDurableStateGeneration(data.readLong()),
                backendRevision = LicenseServiceDurableBackendRevision(data.readLong()),
                protector = LicenseServiceDurableStateProtectorReference(
                    id = LicenseServiceDurableStateProtectorId(data.readBoundedId(input)),
                    generation = LicenseServiceDurableStateProtectorGeneration(data.readLong())
                )
            )
            val nonce = data.readBoundedBytes(input, profile.nonceSizeBytes, profile.nonceSizeBytes)
            val ciphertext = data.readBoundedBytes(input, 1, MAX_CIPHERTEXT_BYTES)
            val tagBytes = profile.authenticationTagSizeBits / 8
            val tag = data.readBoundedBytes(input, tagBytes, tagBytes)
            if (input.available() != 0) {
                return rejectedDecode(LicenseServiceDurableStateCodecRejection.NON_CANONICAL)
            }

            val envelope = LicenseServiceDurableStateEnvelope(binding, nonce, ciphertext, tag)
            val reencoded = when (val encoded = encode(envelope)) {
                is LicenseServiceDurableStateEnvelopeEncodeResult.Encoded -> encoded.payload.copyBytes()
                is LicenseServiceDurableStateEnvelopeEncodeResult.Rejected -> return rejectedDecode(encoded.reason)
            }
            if (!reencoded.contentEquals(original)) {
                return rejectedDecode(LicenseServiceDurableStateCodecRejection.NON_CANONICAL)
            }
            LicenseServiceDurableStateEnvelopeDecodeResult.Decoded(envelope)
        } catch (_: EOFException) {
            rejectedDecode(LicenseServiceDurableStateCodecRejection.MALFORMED)
        } catch (_: IllegalArgumentException) {
            rejectedDecode(LicenseServiceDurableStateCodecRejection.MALFORMED)
        } catch (_: RuntimeException) {
            rejectedDecode(LicenseServiceDurableStateCodecRejection.MALFORMED)
        }
    }

    private fun DataOutputStream.writeBoundedId(bytes: ByteArray) {
        require(bytes.size in 1..MAX_ID_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedId(input: ByteArrayInputStream): String {
        val length = readInt()
        if (length !in 1..MAX_ID_BYTES || length > input.available()) throw EOFException()
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun DataOutputStream.writeBoundedBytes(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedBytes(
        input: ByteArrayInputStream,
        minimum: Int,
        maximum: Int
    ): ByteArray {
        val length = readInt()
        if (length !in minimum..maximum || length > input.available()) throw EOFException()
        return ByteArray(length).also(::readFully)
    }

    private fun rejectedEncode(
        reason: LicenseServiceDurableStateCodecRejection
    ): LicenseServiceDurableStateEnvelopeEncodeResult =
        LicenseServiceDurableStateEnvelopeEncodeResult.Rejected(reason)

    private fun rejectedDecode(
        reason: LicenseServiceDurableStateCodecRejection
    ): LicenseServiceDurableStateEnvelopeDecodeResult =
        LicenseServiceDurableStateEnvelopeDecodeResult.Rejected(reason)

    private class EncodedBudget(private val maximum: Int) {
        var used: Int = 0
            private set

        fun add(bytes: Int): Boolean {
            if (bytes < 0 || used > maximum - bytes) return false
            used += bytes
            return true
        }
    }
}

/**
 * Canonical AEAD associated data for the dedicated licensing security-state domain.
 * It is computed from pre-seal binding, so exact revision/generation/destination ownership are
 * authenticated before ciphertext exists. Nonce/ciphertext/tag are deliberately excluded.
 */
object LicenseServiceDurableStateAssociatedDataEncoder {
    private const val MAGIC = 0x4C534134 // LSA4
    private const val VERSION = 1
    private const val PURPOSE_CODE = 1
    private const val ALGORITHM_CODE_AES_256_GCM = 1

    fun encode(binding: LicenseServiceDurableStateBinding): ByteArray {
        val storeBytes = binding.storeId.value.toByteArray(StandardCharsets.UTF_8)
        val protectorBytes = binding.protector.id.value.toByteArray(StandardCharsets.UTF_8)
        val outputSize = Int.SIZE_BYTES * 10 + Long.SIZE_BYTES * 3 + storeBytes.size + protectorBytes.size
        return ByteArrayOutputStream(outputSize).use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeInt(binding.version.value)
                data.writeInt(PURPOSE_CODE)
                data.writeInt(ALGORITHM_CODE_AES_256_GCM)
                data.writeInt(binding.profile.keySizeBits)
                data.writeInt(binding.profile.nonceSizeBytes)
                data.writeInt(binding.profile.authenticationTagSizeBits)
                data.writeInt(storeBytes.size)
                data.write(storeBytes)
                data.writeLong(binding.generation.value)
                data.writeLong(binding.backendRevision.value)
                data.writeInt(protectorBytes.size)
                data.write(protectorBytes)
                data.writeLong(binding.protector.generation.value)
            }
            output.toByteArray()
        }
    }
}
