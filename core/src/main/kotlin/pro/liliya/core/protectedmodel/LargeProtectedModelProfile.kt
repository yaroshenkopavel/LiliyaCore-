package pro.liliya.core.protectedmodel

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

@JvmInline
value class LargeProtectedModelPayloadProfileId(val value: String) {
    init {
        require(value.isNotBlank()) { "large protected model payload profile id must not be blank" }
        require(value.length <= MAX_LARGE_PAYLOAD_PROFILE_ID_CHARS) {
            "large protected model payload profile id exceeds maximum length"
        }
    }

    override fun toString(): String = value
}

@JvmInline
value class LargeProtectedModelPayloadProfileVersion(val value: Int) {
    init {
        require(value > 0) { "large protected model payload profile version must be positive" }
    }
}

data class LargeProtectedModelPayloadProfile(
    val id: LargeProtectedModelPayloadProfileId,
    val version: LargeProtectedModelPayloadProfileVersion
) {
    companion object {
        val SEGMENTED_AES_256_GCM_SHA256_V1 = LargeProtectedModelPayloadProfile(
            id = LargeProtectedModelPayloadProfileId("SEGMENTED_AES_256_GCM_SHA256"),
            version = LargeProtectedModelPayloadProfileVersion(1)
        )
    }
}

data class LargeProtectedModelResourceBudgets(
    val maxTotalPlaintextBytes: Long,
    val maxTotalCiphertextBytes: Long,
    val maxSegmentCount: Int,
    val minSegmentPlaintextBytes: Long,
    val maxSegmentPlaintextBytes: Long,
    val maxSegmentCiphertextBytes: Long,
    val maxStructuralIdentifierChars: Int = DEFAULT_MAX_STRUCTURAL_IDENTIFIER_CHARS,
    val maxCanonicalManifestBytes: Long = DEFAULT_MAX_CANONICAL_MANIFEST_BYTES
) {
    init {
        require(maxTotalPlaintextBytes > 0L) { "max total plaintext bytes must be positive" }
        require(maxTotalCiphertextBytes > 0L) { "max total ciphertext bytes must be positive" }
        require(maxSegmentCount > 0) { "max segment count must be positive" }
        require(minSegmentPlaintextBytes > 0L) { "min segment plaintext bytes must be positive" }
        require(maxSegmentPlaintextBytes >= minSegmentPlaintextBytes) {
            "max segment plaintext bytes must be >= minimum"
        }
        require(maxSegmentCiphertextBytes > 0L) { "max segment ciphertext bytes must be positive" }
        require(maxSegmentPlaintextBytes <= maxTotalPlaintextBytes) {
            "max segment plaintext bytes exceeds total plaintext budget"
        }
        require(maxSegmentCiphertextBytes <= maxTotalCiphertextBytes) {
            "max segment ciphertext bytes exceeds total ciphertext budget"
        }
        require(maxStructuralIdentifierChars > 0) {
            "max structural identifier chars must be positive"
        }
        require(maxCanonicalManifestBytes > 0L) {
            "max canonical manifest bytes must be positive"
        }
    }

    companion object {
        val DEFAULT = LargeProtectedModelResourceBudgets(
            maxTotalPlaintextBytes = 16L * 1024L * 1024L * 1024L,
            maxTotalCiphertextBytes = 16L * 1024L * 1024L * 1024L,
            maxSegmentCount = 65_536,
            minSegmentPlaintextBytes = 1L,
            maxSegmentPlaintextBytes = 16L * 1024L * 1024L,
            maxSegmentCiphertextBytes = 16L * 1024L * 1024L
        )
    }
}

/**
 * Untrusted structural input for one future large-model payload segment.
 *
 * The constructor only detaches mutable byte inputs. Exact profile sizes, ordering and resource
 * constraints are validated by [LargeProtectedModelManifestFactory].
 */
class LargeProtectedModelSegmentDraft(
    val index: Int,
    val plaintextSizeBytes: Long,
    val ciphertextSizeBytes: Long,
    nonce: ByteArray,
    ciphertextDigest: ByteArray
) {
    private val nonceBytes = nonce.copyOf()
    private val ciphertextDigestBytes = ciphertextDigest.copyOf()

    fun copyNonce(): ByteArray = nonceBytes.copyOf()
    fun copyCiphertextDigest(): ByteArray = ciphertextDigestBytes.copyOf()

    override fun toString(): String =
        "LargeProtectedModelSegmentDraft(index=$index, plaintextSizeBytes=$plaintextSizeBytes, " +
            "ciphertextSizeBytes=$ciphertextSizeBytes, nonce=<redacted:${nonceBytes.size} bytes>, " +
            "ciphertextDigest=<redacted:${ciphertextDigestBytes.size} bytes>)"
}

class LargeProtectedModelSegment internal constructor(
    val index: Int,
    val plaintextSizeBytes: Long,
    val ciphertextSizeBytes: Long,
    nonce: ByteArray,
    ciphertextDigest: ByteArray
) {
    private val nonceBytes = nonce.copyOf()
    private val ciphertextDigestBytes = ciphertextDigest.copyOf()

    fun copyNonce(): ByteArray = nonceBytes.copyOf()
    fun copyCiphertextDigest(): ByteArray = ciphertextDigestBytes.copyOf()

    override fun toString(): String =
        "LargeProtectedModelSegment(index=$index, plaintextSizeBytes=$plaintextSizeBytes, " +
            "ciphertextSizeBytes=$ciphertextSizeBytes, nonce=<redacted:${nonceBytes.size} bytes>, " +
            "ciphertextDigest=<redacted:${ciphertextDigestBytes.size} bytes>)"
}

class LargeProtectedModelManifestRequest(
    val profile: LargeProtectedModelPayloadProfile,
    val model: ProtectedModelReference,
    val modelDek: ModelDekReference,
    val totalPlaintextSizeBytes: Long,
    val totalCiphertextSizeBytes: Long,
    val declaredSegmentCount: Int,
    segments: List<LargeProtectedModelSegmentDraft>
) {
    private val segmentDrafts = segments.toList()

    fun segments(): List<LargeProtectedModelSegmentDraft> = segmentDrafts.toList()

    override fun toString(): String =
        "LargeProtectedModelManifestRequest(profile=$profile, model=$model, modelDek=$modelDek, " +
            "totalPlaintextSizeBytes=$totalPlaintextSizeBytes, " +
            "totalCiphertextSizeBytes=$totalCiphertextSizeBytes, " +
            "declaredSegmentCount=$declaredSegmentCount, segments=<redacted:${segmentDrafts.size}>)"
}

class LargeProtectedModelManifest internal constructor(
    val profile: LargeProtectedModelPayloadProfile,
    val model: ProtectedModelReference,
    val modelDek: ModelDekReference,
    val totalPlaintextSizeBytes: Long,
    val totalCiphertextSizeBytes: Long,
    segments: List<LargeProtectedModelSegment>
) {
    private val orderedSegments = segments.toList()

    val segmentCount: Int
        get() = orderedSegments.size

    fun segments(): List<LargeProtectedModelSegment> = orderedSegments.toList()

    override fun toString(): String =
        "LargeProtectedModelManifest(profile=$profile, model=$model, modelDek=$modelDek, " +
            "totalPlaintextSizeBytes=$totalPlaintextSizeBytes, " +
            "totalCiphertextSizeBytes=$totalCiphertextSizeBytes, segmentCount=$segmentCount, " +
            "segments=<redacted:$segmentCount>)"
}

enum class LargeProtectedModelManifestFailure {
    UNSUPPORTED_PROFILE,
    TOTAL_PLAINTEXT_SIZE_INVALID,
    TOTAL_CIPHERTEXT_SIZE_INVALID,
    SEGMENT_COUNT_INVALID,
    SEGMENT_COUNT_MISMATCH,
    STRUCTURAL_IDENTIFIER_SIZE_INVALID,
    CANONICAL_MANIFEST_SIZE_INVALID,
    SEGMENT_INDEX_INVALID,
    DUPLICATE_SEGMENT_INDEX,
    SEGMENT_ORDER_INVALID,
    SEGMENT_PLAINTEXT_SIZE_INVALID,
    SEGMENT_CIPHERTEXT_SIZE_INVALID,
    INVALID_NONCE_SIZE,
    DUPLICATE_NONCE,
    INVALID_CIPHERTEXT_DIGEST_SIZE,
    AGGREGATE_SIZE_OVERFLOW,
    AGGREGATE_PLAINTEXT_SIZE_MISMATCH,
    AGGREGATE_CIPHERTEXT_SIZE_MISMATCH
}

sealed interface LargeProtectedModelManifestResult {
    data class Accepted(
        val manifest: LargeProtectedModelManifest
    ) : LargeProtectedModelManifestResult

    data class Rejected(
        val reason: LargeProtectedModelManifestFailure
    ) : LargeProtectedModelManifestResult
}

object LargeProtectedModelManifestFactory {
    fun create(
        request: LargeProtectedModelManifestRequest,
        budgets: LargeProtectedModelResourceBudgets = LargeProtectedModelResourceBudgets.DEFAULT
    ): LargeProtectedModelManifestResult {
        if (request.profile != LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1) {
            return rejected(LargeProtectedModelManifestFailure.UNSUPPORTED_PROFILE)
        }
        if (request.totalPlaintextSizeBytes <= 0L ||
            request.totalPlaintextSizeBytes > budgets.maxTotalPlaintextBytes
        ) {
            return rejected(LargeProtectedModelManifestFailure.TOTAL_PLAINTEXT_SIZE_INVALID)
        }
        if (request.totalCiphertextSizeBytes <= 0L ||
            request.totalCiphertextSizeBytes > budgets.maxTotalCiphertextBytes
        ) {
            return rejected(LargeProtectedModelManifestFailure.TOTAL_CIPHERTEXT_SIZE_INVALID)
        }
        if (request.declaredSegmentCount <= 0 ||
            request.declaredSegmentCount > budgets.maxSegmentCount
        ) {
            return rejected(LargeProtectedModelManifestFailure.SEGMENT_COUNT_INVALID)
        }
        if (request.model.packageId.value.length > budgets.maxStructuralIdentifierChars ||
            request.modelDek.id.value.length > budgets.maxStructuralIdentifierChars
        ) {
            return rejected(LargeProtectedModelManifestFailure.STRUCTURAL_IDENTIFIER_SIZE_INVALID)
        }

        val drafts = request.segments()
        if (drafts.size != request.declaredSegmentCount) {
            return rejected(LargeProtectedModelManifestFailure.SEGMENT_COUNT_MISMATCH)
        }
        val canonicalUpperBound = canonicalManifestUpperBound(request, drafts.size)
            ?: return rejected(LargeProtectedModelManifestFailure.CANONICAL_MANIFEST_SIZE_INVALID)
        if (canonicalUpperBound > budgets.maxCanonicalManifestBytes) {
            return rejected(LargeProtectedModelManifestFailure.CANONICAL_MANIFEST_SIZE_INVALID)
        }

        val seenIndices = HashSet<Int>(drafts.size)
        for (draft in drafts) {
            if (draft.index < 0 || draft.index >= request.declaredSegmentCount) {
                return rejected(LargeProtectedModelManifestFailure.SEGMENT_INDEX_INVALID)
            }
            if (!seenIndices.add(draft.index)) {
                return rejected(LargeProtectedModelManifestFailure.DUPLICATE_SEGMENT_INDEX)
            }
        }
        drafts.forEachIndexed { expectedIndex, draft ->
            if (draft.index != expectedIndex) {
                return rejected(LargeProtectedModelManifestFailure.SEGMENT_ORDER_INVALID)
            }
        }

        val nonceKeys = HashSet<ByteArrayKey>(drafts.size)
        val acceptedSegments = ArrayList<LargeProtectedModelSegment>(drafts.size)
        var aggregatePlaintext = 0L
        var aggregateCiphertext = 0L

        for (draft in drafts) {
            if (draft.plaintextSizeBytes < budgets.minSegmentPlaintextBytes ||
                draft.plaintextSizeBytes > budgets.maxSegmentPlaintextBytes
            ) {
                return rejected(LargeProtectedModelManifestFailure.SEGMENT_PLAINTEXT_SIZE_INVALID)
            }
            if (draft.ciphertextSizeBytes <= 0L ||
                draft.ciphertextSizeBytes > budgets.maxSegmentCiphertextBytes
            ) {
                return rejected(LargeProtectedModelManifestFailure.SEGMENT_CIPHERTEXT_SIZE_INVALID)
            }

            val nonce = draft.copyNonce()
            val digest = draft.copyCiphertextDigest()
            try {
                if (nonce.size != SEGMENT_NONCE_SIZE_BYTES) {
                    return rejected(LargeProtectedModelManifestFailure.INVALID_NONCE_SIZE)
                }
                if (!nonceKeys.add(ByteArrayKey(nonce))) {
                    return rejected(LargeProtectedModelManifestFailure.DUPLICATE_NONCE)
                }
                if (digest.size != SEGMENT_CIPHERTEXT_DIGEST_SIZE_BYTES) {
                    return rejected(LargeProtectedModelManifestFailure.INVALID_CIPHERTEXT_DIGEST_SIZE)
                }

                aggregatePlaintext = try {
                    Math.addExact(aggregatePlaintext, draft.plaintextSizeBytes)
                } catch (_: ArithmeticException) {
                    return rejected(LargeProtectedModelManifestFailure.AGGREGATE_SIZE_OVERFLOW)
                }
                aggregateCiphertext = try {
                    Math.addExact(aggregateCiphertext, draft.ciphertextSizeBytes)
                } catch (_: ArithmeticException) {
                    return rejected(LargeProtectedModelManifestFailure.AGGREGATE_SIZE_OVERFLOW)
                }

                acceptedSegments += LargeProtectedModelSegment(
                    index = draft.index,
                    plaintextSizeBytes = draft.plaintextSizeBytes,
                    ciphertextSizeBytes = draft.ciphertextSizeBytes,
                    nonce = nonce,
                    ciphertextDigest = digest
                )
            } finally {
                nonce.fill(0)
                digest.fill(0)
            }
        }

        if (aggregatePlaintext != request.totalPlaintextSizeBytes) {
            return rejected(LargeProtectedModelManifestFailure.AGGREGATE_PLAINTEXT_SIZE_MISMATCH)
        }
        if (aggregateCiphertext != request.totalCiphertextSizeBytes) {
            return rejected(LargeProtectedModelManifestFailure.AGGREGATE_CIPHERTEXT_SIZE_MISMATCH)
        }

        return LargeProtectedModelManifestResult.Accepted(
            LargeProtectedModelManifest(
                profile = request.profile,
                model = request.model,
                modelDek = request.modelDek,
                totalPlaintextSizeBytes = aggregatePlaintext,
                totalCiphertextSizeBytes = aggregateCiphertext,
                segments = acceptedSegments
            )
        )
    }

    private fun canonicalManifestUpperBound(
        request: LargeProtectedModelManifestRequest,
        segmentCount: Int
    ): Long? = try {
        var total = 0L
        total = Math.addExact(total, 4L)
        total = Math.addExact(total, stringUpperBound(request.profile.id.value))
        total = Math.addExact(total, 4L)
        total = Math.addExact(total, stringUpperBound(request.model.packageId.value))
        total = Math.addExact(total, 8L)
        total = Math.addExact(total, stringUpperBound(request.modelDek.id.value))
        total = Math.addExact(total, 8L)
        total = Math.addExact(total, 8L)
        total = Math.addExact(total, 8L)
        total = Math.addExact(total, 4L)
        total = Math.addExact(
            total,
            Math.multiplyExact(segmentCount.toLong(), CANONICAL_SEGMENT_UPPER_BOUND_BYTES)
        )
        total
    } catch (_: ArithmeticException) {
        null
    }

    private fun stringUpperBound(value: String): Long =
        Math.addExact(4L, Math.multiplyExact(value.length.toLong(), MAX_UTF8_BYTES_PER_CHAR))

    private fun rejected(reason: LargeProtectedModelManifestFailure) =
        LargeProtectedModelManifestResult.Rejected(reason)
}

/** Deterministic structural encoding for the large protected-model segmented profile. */
object LargeProtectedModelManifestCanonicalCodec {
    fun encode(manifest: LargeProtectedModelManifest): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(CANONICAL_VERSION)
            writeString(out, manifest.profile.id.value)
            out.writeInt(manifest.profile.version.value)
            writeString(out, manifest.model.packageId.value)
            out.writeLong(manifest.model.generation.value)
            writeString(out, manifest.modelDek.id.value)
            out.writeLong(manifest.modelDek.generation.value)
            out.writeLong(manifest.totalPlaintextSizeBytes)
            out.writeLong(manifest.totalCiphertextSizeBytes)
            out.writeInt(manifest.segmentCount)
            manifest.segments().forEach { segment ->
                out.writeInt(segment.index)
                out.writeLong(segment.plaintextSizeBytes)
                out.writeLong(segment.ciphertextSizeBytes)
                val nonce = segment.copyNonce()
                val digest = segment.copyCiphertextDigest()
                try {
                    writeBytes(out, nonce)
                    writeBytes(out, digest)
                } finally {
                    nonce.fill(0)
                    digest.fill(0)
                }
            }
        }
        return buffer.toByteArray()
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.encodeToByteArray()
        try {
            writeBytes(out, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun writeBytes(out: DataOutputStream, value: ByteArray) {
        out.writeInt(value.size)
        out.write(value)
    }

    private const val CANONICAL_VERSION = 1
}

private class ByteArrayKey(value: ByteArray) {
    private val bytes = value.copyOf()
    private val hash = bytes.contentHashCode()

    override fun equals(other: Any?): Boolean =
        other is ByteArrayKey && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = hash
}

private const val MAX_LARGE_PAYLOAD_PROFILE_ID_CHARS = 128
private const val DEFAULT_MAX_STRUCTURAL_IDENTIFIER_CHARS = 512
private const val DEFAULT_MAX_CANONICAL_MANIFEST_BYTES = 8L * 1024L * 1024L
private const val MAX_UTF8_BYTES_PER_CHAR = 4L
private const val CANONICAL_SEGMENT_UPPER_BOUND_BYTES = 72L
const val SEGMENT_NONCE_SIZE_BYTES = 12
const val SEGMENT_CIPHERTEXT_DIGEST_SIZE_BYTES = 32
