package pro.liliya.core.protectedmodel

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class LargeProtectedModelCanonicalDecodeFailure {
    MALFORMED,
    RESOURCE_LIMIT_REJECTED,
    UNSUPPORTED_PROFILE,
    MANIFEST_REJECTED
}

sealed interface LargeProtectedModelCanonicalDecodeResult {
    data class Decoded(
        val manifest: LargeProtectedModelSignedManifest
    ) : LargeProtectedModelCanonicalDecodeResult

    data class Rejected(
        val reason: LargeProtectedModelCanonicalDecodeFailure,
        val manifestFailure: LargeProtectedModelManifestFailure? = null
    ) : LargeProtectedModelCanonicalDecodeResult
}

/**
 * Strict inverse for the existing canonical large protected-model signed-manifest encoding.
 *
 * Decoded != Verified. This parser reconstructs bounded structural objects only; package
 * authenticity remains owned by [LargeProtectedModelPackageVerifier].
 */
object LargeProtectedModelCanonicalDecoder {
    fun decodeSignedManifest(
        bytes: ByteArray,
        resourceBudgets: LargeProtectedModelResourceBudgets,
        packageBudgets: LargeProtectedModelPackageBudgets
    ): LargeProtectedModelCanonicalDecodeResult {
        if (bytes.isEmpty() ||
            bytes.size.toLong() > packageBudgets.maxCanonicalSignedManifestBytes
        ) {
            return rejected(LargeProtectedModelCanonicalDecodeFailure.RESOURCE_LIMIT_REJECTED)
        }

        return try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != OUTER_CANONICAL_VERSION) {
                return rejected(LargeProtectedModelCanonicalDecodeFailure.UNSUPPORTED_PROFILE)
            }

            val formatVersionValue = input.readInt()
            if (formatVersionValue <= 0) {
                return rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
            }
            val modelProfileId = readString(
                input = input,
                maxChars = packageBudgets.maxModelProfileIdChars
            ) ?: return rejected(LargeProtectedModelCanonicalDecodeFailure.RESOURCE_LIMIT_REJECTED)

            val payloadBytes = readBytes(
                input = input,
                maxBytes = resourceBudgets.maxCanonicalManifestBytes
            ) ?: return rejected(LargeProtectedModelCanonicalDecodeFailure.RESOURCE_LIMIT_REJECTED)

            val payload = try {
                when (val decoded = decodePayload(payloadBytes, resourceBudgets)) {
                    is PayloadDecodeResult.Decoded -> decoded.manifest
                    is PayloadDecodeResult.Rejected -> {
                        return LargeProtectedModelCanonicalDecodeResult.Rejected(
                            reason = decoded.reason,
                            manifestFailure = decoded.manifestFailure
                        )
                    }
                }
            } finally {
                payloadBytes.fill(0)
            }

            val encryptionAlgorithm = readString(input, MAX_ENUM_NAME_CHARS)
                ?: return rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
            val keySizeBits = input.readInt()
            val nonceSizeBytes = input.readInt()
            val authenticationTagSizeBits = input.readInt()
            if (
                encryptionAlgorithm != ProtectedModelEncryptionAlgorithm.AES_256_GCM.name ||
                keySizeBits != 256 ||
                nonceSizeBytes != 12 ||
                authenticationTagSizeBits != 128
            ) {
                return rejected(LargeProtectedModelCanonicalDecodeFailure.UNSUPPORTED_PROFILE)
            }

            val signatureAlgorithm = readString(input, MAX_ENUM_NAME_CHARS)
                ?: return rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
            if (signatureAlgorithm != ProtectedModelSignatureAlgorithm.ED25519.name) {
                return rejected(LargeProtectedModelCanonicalDecodeFailure.UNSUPPORTED_PROFILE)
            }
            val signerId = readString(input, packageBudgets.maxSignerIdChars)
                ?: return rejected(LargeProtectedModelCanonicalDecodeFailure.RESOURCE_LIMIT_REJECTED)

            if (input.read() != -1) {
                return rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
            }

            val manifest = try {
                LargeProtectedModelSignedManifest(
                    formatVersion = ProtectedModelFormatVersion(formatVersionValue),
                    modelProfileId = ProtectedModelProfileId(modelProfileId),
                    payload = payload,
                    encryptionProfile = ProtectedModelEncryptionProfile.AES_256_GCM,
                    signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
                    signerId = ProtectedModelSignerId(signerId)
                )
            } catch (_: IllegalArgumentException) {
                return rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
            }
            LargeProtectedModelCanonicalDecodeResult.Decoded(manifest)
        } catch (_: EOFException) {
            rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
        } catch (_: IllegalArgumentException) {
            rejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
        } catch (_: ArithmeticException) {
            rejected(LargeProtectedModelCanonicalDecodeFailure.RESOURCE_LIMIT_REJECTED)
        }
    }

    private fun decodePayload(
        bytes: ByteArray,
        budgets: LargeProtectedModelResourceBudgets
    ): PayloadDecodeResult {
        if (bytes.isEmpty() || bytes.size.toLong() > budgets.maxCanonicalManifestBytes) {
            return payloadRejected(LargeProtectedModelCanonicalDecodeFailure.RESOURCE_LIMIT_REJECTED)
        }

        return try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != PAYLOAD_CANONICAL_VERSION) {
                return payloadRejected(LargeProtectedModelCanonicalDecodeFailure.UNSUPPORTED_PROFILE)
            }

            val profileId = readString(input, budgets.maxStructuralIdentifierChars)
                ?: return payloadRejected(
                    LargeProtectedModelCanonicalDecodeFailure.RESOURCE_LIMIT_REJECTED
                )
            val profileVersion = input.readInt()
            if (
                profileId != LargeProtectedModelPayloadProfile
                    .SEGMENTED_AES_256_GCM_SHA256_V1.id.value ||
                profileVersion != LargeProtectedModelPayloadProfile
                    .SEGMENTED_AES_256_GCM_SHA256_V1.version.value
            ) {
                return payloadRejected(LargeProtectedModelCanonicalDecodeFailure.UNSUPPORTED_PROFILE)
            }

            val packageId = readString(input, budgets.maxStructuralIdentifierChars)
                ?: return payloadRejected(
                    LargeProtectedModelCanonicalDecodeFailure.RESOURCE_LIMIT_REJECTED
                )
            val modelGeneration = input.readLong()
            val modelDekId = readString(input, budgets.maxStructuralIdentifierChars)
                ?: return payloadRejected(
                    LargeProtectedModelCanonicalDecodeFailure.RESOURCE_LIMIT_REJECTED
                )
            val modelDekGeneration = input.readLong()
            val totalPlaintext = input.readLong()
            val totalCiphertext = input.readLong()
            val totalProtected = input.readLong()
            val segmentCount = input.readInt()

            if (segmentCount <= 0 || segmentCount > budgets.maxSegmentCount) {
                return payloadRejected(
                    LargeProtectedModelCanonicalDecodeFailure.RESOURCE_LIMIT_REJECTED
                )
            }

            val drafts = ArrayList<LargeProtectedModelSegmentDraft>(segmentCount)
            repeat(segmentCount) {
                val index = input.readInt()
                val plaintext = input.readLong()
                val ciphertext = input.readLong()
                val nonce = readBytes(input, MAX_NONCE_BYTES.toLong())
                    ?: return payloadRejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
                val digest = readBytes(input, MAX_DIGEST_BYTES.toLong())
                    ?: run {
                        nonce.fill(0)
                        return payloadRejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
                    }
                try {
                    drafts += LargeProtectedModelSegmentDraft(
                        index = index,
                        plaintextSizeBytes = plaintext,
                        ciphertextBodySizeBytes = ciphertext,
                        nonce = nonce,
                        protectedPayloadDigest = digest
                    )
                } finally {
                    nonce.fill(0)
                    digest.fill(0)
                }
            }

            if (input.read() != -1) {
                return payloadRejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
            }

            val request = try {
                LargeProtectedModelManifestRequest(
                    profile = LargeProtectedModelPayloadProfile
                        .SEGMENTED_AES_256_GCM_SHA256_V1,
                    model = ProtectedModelReference(
                        packageId = ProtectedModelPackageId(packageId),
                        generation = ProtectedModelGeneration(modelGeneration)
                    ),
                    modelDek = ModelDekReference(
                        id = ModelDekId(modelDekId),
                        generation = ModelDekGeneration(modelDekGeneration)
                    ),
                    totalPlaintextSizeBytes = totalPlaintext,
                    totalCiphertextBodySizeBytes = totalCiphertext,
                    totalProtectedPayloadSizeBytes = totalProtected,
                    declaredSegmentCount = segmentCount,
                    segments = drafts
                )
            } catch (_: IllegalArgumentException) {
                return payloadRejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
            }

            when (val created = LargeProtectedModelManifestFactory.create(request, budgets)) {
                is LargeProtectedModelManifestResult.Accepted ->
                    PayloadDecodeResult.Decoded(created.manifest)
                is LargeProtectedModelManifestResult.Rejected ->
                    PayloadDecodeResult.Rejected(
                        reason = LargeProtectedModelCanonicalDecodeFailure.MANIFEST_REJECTED,
                        manifestFailure = created.reason
                    )
            }
        } catch (_: EOFException) {
            payloadRejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
        } catch (_: IllegalArgumentException) {
            payloadRejected(LargeProtectedModelCanonicalDecodeFailure.MALFORMED)
        } catch (_: ArithmeticException) {
            payloadRejected(LargeProtectedModelCanonicalDecodeFailure.RESOURCE_LIMIT_REJECTED)
        }
    }

    private fun readString(input: DataInputStream, maxChars: Int): String? {
        val raw = readBytes(
            input = input,
            maxBytes = Math.multiplyExact(maxChars.toLong(), MAX_UTF8_BYTES_PER_CHAR)
        ) ?: return null
        return try {
            val decoded = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString()
            decoded.takeIf { it.length <= maxChars }
        } catch (_: Exception) {
            null
        } finally {
            raw.fill(0)
        }
    }

    private fun readBytes(input: DataInputStream, maxBytes: Long): ByteArray? {
        val length = input.readInt()
        if (length < 0 || length.toLong() > maxBytes) return null
        val result = ByteArray(length)
        input.readFully(result)
        return result
    }

    private fun rejected(
        reason: LargeProtectedModelCanonicalDecodeFailure
    ) = LargeProtectedModelCanonicalDecodeResult.Rejected(reason)

    private fun payloadRejected(
        reason: LargeProtectedModelCanonicalDecodeFailure,
        manifestFailure: LargeProtectedModelManifestFailure? = null
    ) = PayloadDecodeResult.Rejected(reason, manifestFailure)

    private sealed interface PayloadDecodeResult {
        data class Decoded(val manifest: LargeProtectedModelManifest) : PayloadDecodeResult
        data class Rejected(
            val reason: LargeProtectedModelCanonicalDecodeFailure,
            val manifestFailure: LargeProtectedModelManifestFailure? = null
        ) : PayloadDecodeResult
    }

    private const val OUTER_CANONICAL_VERSION = 1
    private const val PAYLOAD_CANONICAL_VERSION = 1
    private const val MAX_ENUM_NAME_CHARS = 64
    private const val MAX_NONCE_BYTES = 64
    private const val MAX_DIGEST_BYTES = 128
    private const val MAX_UTF8_BYTES_PER_CHAR = 4L
}
