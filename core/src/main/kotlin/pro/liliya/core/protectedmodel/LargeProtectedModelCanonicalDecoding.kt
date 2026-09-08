package pro.liliya.core.protectedmodel

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class LargeProtectedModelCanonicalDecodeFailure {
    MALFORMED,
    UNSUPPORTED_VERSION,
    RESOURCE_LIMIT_REJECTED,
    STRUCTURAL_REJECTED,
    TRAILING_DATA
}

sealed interface LargeProtectedModelManifestCanonicalDecodeResult {
    data class Decoded(
        val manifest: LargeProtectedModelManifest
    ) : LargeProtectedModelManifestCanonicalDecodeResult

    data class Rejected(
        val reason: LargeProtectedModelCanonicalDecodeFailure,
        val manifestFailure: LargeProtectedModelManifestFailure? = null
    ) : LargeProtectedModelManifestCanonicalDecodeResult
}

sealed interface LargeProtectedModelSignedManifestCanonicalDecodeResult {
    data class Decoded(
        val manifest: LargeProtectedModelSignedManifest
    ) : LargeProtectedModelSignedManifestCanonicalDecodeResult

    data class Rejected(
        val reason: LargeProtectedModelCanonicalDecodeFailure,
        val manifestFailure: LargeProtectedModelManifestFailure? = null
    ) : LargeProtectedModelSignedManifestCanonicalDecodeResult
}

/**
 * Strict inverse for [LargeProtectedModelManifestCanonicalCodec.encode].
 *
 * Decode is structural only. It does not prove package authenticity, License, model-key possession,
 * Authority or execution permission.
 */
object LargeProtectedModelManifestCanonicalDecoder {
    fun decode(
        bytes: ByteArray,
        budgets: LargeProtectedModelResourceBudgets
    ): LargeProtectedModelManifestCanonicalDecodeResult {
        if (bytes.isEmpty() || bytes.size.toLong() > budgets.maxCanonicalManifestBytes) {
            return rejected(LargeProtectedModelCanonicalDecodeFailure.RESOURCE_LIMIT_REJECTED)
        }

        return try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != MANIFEST_CANONICAL_VERSION) {
                return rejected(LargeProtectedModelCanonicalDecodeFailure.UNSUPPORTED_VERSION)
            }

            val profileId = readString(input, budgets.maxStructuralIdentifierChars)
                ?: return rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
            val profileVersion = input.readInt()
            val packageId = readString(input, budgets.maxStructuralIdentifierChars)
                ?: return rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
            val modelGeneration = input.readLong()
            val dekId = readString(input, budgets.maxStructuralIdentifierChars)
                ?: return rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
            val dekGeneration = input.readLong()
            val totalPlaintext = input.readLong()
            val totalCiphertextBody = input.readLong()
            val totalProtectedPayload = input.readLong()
            val segmentCount = input.readInt()

            if (segmentCount <= 0 || segmentCount > budgets.maxSegmentCount) {
                return rejected(LargeProtectedModelCanonicalDecodeFailure.RESOURCE_LIMIT_REJECTED)
            }

            val drafts = ArrayList<LargeProtectedModelSegmentDraft>(segmentCount)
            repeat(segmentCount) {
                val index = input.readInt()
                val plaintextSize = input.readLong()
                val ciphertextBodySize = input.readLong()
                val nonce = readBytes(input, SEGMENT_NONCE_SIZE_BYTES)
                    ?: return rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
                val digest = readBytes(input, SEGMENT_PROTECTED_PAYLOAD_DIGEST_SIZE_BYTES)
                    ?: return rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
                try {
                    drafts += LargeProtectedModelSegmentDraft(
                        index = index,
                        plaintextSizeBytes = plaintextSize,
                        ciphertextBodySizeBytes = ciphertextBodySize,
                        nonce = nonce,
                        protectedPayloadDigest = digest
                    )
                } finally {
                    nonce.fill(0)
                    digest.fill(0)
                }
            }

            if (input.read() != -1) {
                return rejected(LargeProtectedModelCanonicalDecodeFailure.TRAILING_DATA)
            }

            val request = try {
                LargeProtectedModelManifestRequest(
                    profile = LargeProtectedModelPayloadProfile(
                        LargeProtectedModelPayloadProfileId(profileId),
                        LargeProtectedModelPayloadProfileVersion(profileVersion)
                    ),
                    model = ProtectedModelReference(
                        ProtectedModelPackageId(packageId),
                        ProtectedModelGeneration(modelGeneration)
                    ),
                    modelDek = ModelDekReference(
                        ModelDekId(dekId),
                        ModelDekGeneration(dekGeneration)
                    ),
                    totalPlaintextSizeBytes = totalPlaintext,
                    totalCiphertextBodySizeBytes = totalCiphertextBody,
                    totalProtectedPayloadSizeBytes = totalProtectedPayload,
                    declaredSegmentCount = segmentCount,
                    segments = drafts
                )
            } catch (_: IllegalArgumentException) {
                return rejected(LargeProtectedModelCanonicalDecodeFailure.STRUCTURAL_REJECTED)
            }

            when (val created = LargeProtectedModelManifestFactory.create(request, budgets)) {
                is LargeProtectedModelManifestResult.Accepted ->
                    LargeProtectedModelManifestCanonicalDecodeResult.Decoded(created.manifest)
                is LargeProtectedModelManifestResult.Rejected ->
                    LargeProtectedModelManifestCanonicalDecodeResult.Rejected(
                        reason = LargeProtectedModelCanonicalDecodeFailure.STRUCTURAL_REJECTED,
                        manifestFailure = created.reason
                    )
            }
        } catch (_: EOFException) {
            rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
        } catch (_: IllegalArgumentException) {
            rejected(LargeProtectedModelCanonicalDecodeFailure.STRUCTURAL_REJECTED)
        }
    }

    private fun rejected(reason: LargeProtectedModelCanonicalDecodeFailure) =
        LargeProtectedModelManifestCanonicalDecodeResult.Rejected(reason)
}

/** Strict inverse for the bytes emitted by the large segmented package canonical encoder. */
object LargeProtectedModelSignedManifestCanonicalDecoder {
    fun decode(
        bytes: ByteArray,
        manifestBudgets: LargeProtectedModelResourceBudgets,
        packageBudgets: LargeProtectedModelPackageBudgets
    ): LargeProtectedModelSignedManifestCanonicalDecodeResult {
        if (bytes.isEmpty() || bytes.size.toLong() > packageBudgets.maxCanonicalSignedManifestBytes) {
            return rejected(LargeProtectedModelCanonicalDecodeFailure.RESOURCE_LIMIT_REJECTED)
        }

        return try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != PACKAGE_CANONICAL_VERSION) {
                return rejected(LargeProtectedModelCanonicalDecodeFailure.UNSUPPORTED_VERSION)
            }
            val formatVersion = input.readInt()
            val modelProfileId = readString(input, packageBudgets.maxModelProfileIdChars)
                ?: return rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
            val payloadBytes = readBytes(input, manifestBudgets.maxCanonicalManifestBytes)
                ?: return rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
            val encryptionAlgorithm = readString(input, MAX_ALGORITHM_CHARS)
                ?: return rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
            val keySizeBits = input.readInt()
            val nonceSizeBytes = input.readInt()
            val tagSizeBits = input.readInt()
            val signatureAlgorithm = readString(input, MAX_ALGORITHM_CHARS)
                ?: return rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
            val signerId = readString(input, packageBudgets.maxSignerIdChars)
                ?: return rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)

            if (input.read() != -1) {
                payloadBytes.fill(0)
                return rejected(LargeProtectedModelCanonicalDecodeFailure.TRAILING_DATA)
            }

            val payload = try {
                when (
                    val decoded = LargeProtectedModelManifestCanonicalDecoder.decode(
                        payloadBytes,
                        manifestBudgets
                    )
                ) {
                    is LargeProtectedModelManifestCanonicalDecodeResult.Decoded -> decoded.manifest
                    is LargeProtectedModelManifestCanonicalDecodeResult.Rejected ->
                        return LargeProtectedModelSignedManifestCanonicalDecodeResult.Rejected(
                            decoded.reason,
                            decoded.manifestFailure
                        )
                }
            } finally {
                payloadBytes.fill(0)
            }

            if (
                encryptionAlgorithm != ProtectedModelEncryptionAlgorithm.AES_256_GCM.name ||
                keySizeBits != 256 ||
                nonceSizeBytes != 12 ||
                tagSizeBits != 128 ||
                signatureAlgorithm != ProtectedModelSignatureAlgorithm.ED25519.name
            ) {
                return rejected(LargeProtectedModelCanonicalDecodeFailure.STRUCTURAL_REJECTED)
            }

            val manifest = try {
                LargeProtectedModelSignedManifest(
                    formatVersion = ProtectedModelFormatVersion(formatVersion),
                    modelProfileId = ProtectedModelProfileId(modelProfileId),
                    payload = payload,
                    encryptionProfile = ProtectedModelEncryptionProfile.AES_256_GCM,
                    signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
                    signerId = ProtectedModelSignerId(signerId)
                )
            } catch (_: IllegalArgumentException) {
                return rejected(LargeProtectedModelCanonicalDecodeFailure.STRUCTURAL_REJECTED)
            }
            LargeProtectedModelSignedManifestCanonicalDecodeResult.Decoded(manifest)
        } catch (_: EOFException) {
            rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
        } catch (_: IllegalArgumentException) {
            rejected(LargeProtectedModelCanonicalDecodeFailure.STRUCTURAL_REJECTED)
        }
    }

    private fun rejected(reason: LargeProtectedModelCanonicalDecodeFailure) =
        LargeProtectedModelSignedManifestCanonicalDecodeResult.Rejected(reason)
}

private fun readString(input: DataInputStream, maxChars: Int): String? {
    val maxBytes = try {
        Math.multiplyExact(maxChars, MAX_UTF8_BYTES_PER_CHAR)
    } catch (_: ArithmeticException) {
        return null
    }
    val bytes = readBytes(input, maxBytes) ?: return null
    return try {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val value = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        value.takeIf { it.isNotBlank() && it.length <= maxChars }
    } catch (_: Exception) {
        null
    } finally {
        bytes.fill(0)
    }
}

private fun readBytes(input: DataInputStream, maxBytes: Int): ByteArray? {
    val size = input.readInt()
    if (size < 0 || size > maxBytes) return null
    return ByteArray(size).also { input.readFully(it) }
}

private fun readBytes(input: DataInputStream, maxBytes: Long): ByteArray? {
    val size = input.readInt()
    if (size < 0 || size.toLong() > maxBytes) return null
    return ByteArray(size).also { input.readFully(it) }
}

private const val MANIFEST_CANONICAL_VERSION = 1
private const val PACKAGE_CANONICAL_VERSION = 1
private const val MAX_ALGORITHM_CHARS = 64
private const val MAX_UTF8_BYTES_PER_CHAR = 4
