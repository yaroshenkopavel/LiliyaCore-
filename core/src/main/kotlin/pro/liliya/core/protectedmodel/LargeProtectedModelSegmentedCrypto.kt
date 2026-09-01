package pro.liliya.core.protectedmodel

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.Signature
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Explicit outer trust/compatibility metadata for the segmented large-model package profile. */
data class LargeProtectedModelSignedManifest(
    val formatVersion: ProtectedModelFormatVersion,
    val modelProfileId: ProtectedModelProfileId,
    val payload: LargeProtectedModelManifest,
    val encryptionProfile: ProtectedModelEncryptionProfile,
    val signatureAlgorithm: ProtectedModelSignatureAlgorithm,
    val signerId: ProtectedModelSignerId
) {
    init {
        require(encryptionProfile == ProtectedModelEncryptionProfile.AES_256_GCM) {
            "unsupported large protected model encryption profile"
        }
        require(signatureAlgorithm == ProtectedModelSignatureAlgorithm.ED25519) {
            "unsupported large protected model signature algorithm"
        }
        require(
            payload.profile == LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1
        ) {
            "unsupported large protected model payload profile"
        }
    }

    override fun toString(): String =
        "LargeProtectedModelSignedManifest(formatVersion=${formatVersion.value}, " +
            "modelProfileId=$modelProfileId, payload=$payload, " +
            "encryptionProfile=$encryptionProfile, signatureAlgorithm=$signatureAlgorithm, " +
            "signerId=$signerId)"
}

/** Large segmented package envelope. There is intentionally no global digest/nonce/tag tuple. */
class LargeProtectedModelPackageEnvelope(
    val manifest: LargeProtectedModelSignedManifest,
    signature: ByteArray
) {
    private val signatureBytes = signature.copyOf()

    init {
        require(signatureBytes.isNotEmpty()) { "large protected model signature must not be empty" }
    }

    fun copySignature(): ByteArray = signatureBytes.copyOf()

    override fun toString(): String =
        "LargeProtectedModelPackageEnvelope(manifest=$manifest, " +
            "signature=<redacted:${signatureBytes.size} bytes>)"
}

data class LargeProtectedModelPackageBudgets(
    val maxModelProfileIdChars: Int,
    val maxSignerIdChars: Int,
    val maxCanonicalSignedManifestBytes: Long
) {
    init {
        require(maxModelProfileIdChars > 0) { "max model profile id chars must be positive" }
        require(maxSignerIdChars > 0) { "max signer id chars must be positive" }
        require(maxCanonicalSignedManifestBytes > 0L) {
            "max canonical signed manifest bytes must be positive"
        }
    }
}

/** Deterministic signed representation for the large segmented package profile. */
internal object LargeProtectedModelPackageCanonicalCodec {
    fun encode(manifest: LargeProtectedModelSignedManifest): ByteArray {
        val payloadBytes = LargeProtectedModelManifestCanonicalCodec.encode(manifest.payload)
        return try {
            ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.writeInt(CANONICAL_VERSION)
                    out.writeInt(manifest.formatVersion.value)
                    writeString(out, manifest.modelProfileId.value)
                    writeBytes(out, payloadBytes)
                    writeString(out, manifest.encryptionProfile.algorithm.name)
                    out.writeInt(manifest.encryptionProfile.keySizeBits)
                    out.writeInt(manifest.encryptionProfile.nonceSizeBytes)
                    out.writeInt(manifest.encryptionProfile.authenticationTagSizeBits)
                    writeString(out, manifest.signatureAlgorithm.name)
                    writeString(out, manifest.signerId.value)
                }
            }.toByteArray()
        } finally {
            payloadBytes.fill(0)
        }
    }

    fun signatureInput(manifest: LargeProtectedModelSignedManifest): ByteArray {
        val encoded = encode(manifest)
        return try {
            ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.writeInt(SIGNATURE_INPUT_VERSION)
                    writeBytes(out, encoded)
                }
            }.toByteArray()
        } finally {
            encoded.fill(0)
        }
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
    private const val SIGNATURE_INPUT_VERSION = 1
}

enum class LargeProtectedModelPackageVerificationFailure {
    UNSUPPORTED_PROFILE,
    STRUCTURAL_IDENTIFIER_SIZE_INVALID,
    CANONICAL_MANIFEST_SIZE_INVALID,
    SIGNER_KEY_UNAVAILABLE,
    SIGNATURE_INVALID,
    PROVIDER_FAILED
}

class VerifiedLargeProtectedModelPackage internal constructor(
    val manifest: LargeProtectedModelSignedManifest
) {
    override fun toString(): String = "VerifiedLargeProtectedModelPackage(manifest=$manifest)"
}

sealed interface LargeProtectedModelPackageVerificationResult {
    data class Verified(
        val value: VerifiedLargeProtectedModelPackage
    ) : LargeProtectedModelPackageVerificationResult

    data class Rejected(
        val reason: LargeProtectedModelPackageVerificationFailure
    ) : LargeProtectedModelPackageVerificationResult

    data class Failed(
        val reason: LargeProtectedModelPackageVerificationFailure,
        val throwable: Throwable? = null
    ) : LargeProtectedModelPackageVerificationResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

/**
 * Segmented package authenticity boundary. Reuses the existing protected-model signer trust root.
 * Successful verification is authenticity evidence only, never License/DEK/Authority/Execution.
 */
class LargeProtectedModelPackageVerifier(
    private val signerResolver: ProtectedModelSignerResolver,
    private val budgets: LargeProtectedModelPackageBudgets
) {
    fun verify(
        envelope: LargeProtectedModelPackageEnvelope
    ): LargeProtectedModelPackageVerificationResult {
        val manifest = envelope.manifest
        if (manifest.encryptionProfile != ProtectedModelEncryptionProfile.AES_256_GCM ||
            manifest.signatureAlgorithm != ProtectedModelSignatureAlgorithm.ED25519 ||
            manifest.payload.profile != LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1
        ) {
            return rejected(LargeProtectedModelPackageVerificationFailure.UNSUPPORTED_PROFILE)
        }
        if (manifest.modelProfileId.value.length > budgets.maxModelProfileIdChars ||
            manifest.signerId.value.length > budgets.maxSignerIdChars
        ) {
            return rejected(
                LargeProtectedModelPackageVerificationFailure.STRUCTURAL_IDENTIFIER_SIZE_INVALID
            )
        }

        var signatureInput: ByteArray? = null
        var signatureBytes: ByteArray? = null
        return try {
            signatureInput = LargeProtectedModelPackageCanonicalCodec.signatureInput(manifest)
            if (signatureInput.size.toLong() > budgets.maxCanonicalSignedManifestBytes) {
                return rejected(
                    LargeProtectedModelPackageVerificationFailure.CANONICAL_MANIFEST_SIZE_INVALID
                )
            }

            val signerKey = signerResolver.resolve(manifest.signerId, manifest.signatureAlgorithm)
                ?: return rejected(
                    LargeProtectedModelPackageVerificationFailure.SIGNER_KEY_UNAVAILABLE
                )

            signatureBytes = envelope.copySignature()
            val verifier = Signature.getInstance(signatureName(manifest.signatureAlgorithm))
            verifier.initVerify(signerKey)
            verifier.update(signatureInput)
            if (!verifier.verify(signatureBytes)) {
                rejected(LargeProtectedModelPackageVerificationFailure.SIGNATURE_INVALID)
            } else {
                LargeProtectedModelPackageVerificationResult.Verified(
                    VerifiedLargeProtectedModelPackage(manifest)
                )
            }
        } catch (throwable: Throwable) {
            LargeProtectedModelPackageVerificationResult.Failed(
                LargeProtectedModelPackageVerificationFailure.PROVIDER_FAILED,
                throwable
            )
        } finally {
            signatureInput?.fill(0)
            signatureBytes?.fill(0)
        }
    }

    private fun signatureName(algorithm: ProtectedModelSignatureAlgorithm): String = when (algorithm) {
        ProtectedModelSignatureAlgorithm.ED25519 -> "Ed25519"
    }

    private fun rejected(reason: LargeProtectedModelPackageVerificationFailure) =
        LargeProtectedModelPackageVerificationResult.Rejected(reason)
}

/** One bounded untrusted transport value. Nonce/AAD/DEK are never source-selected. */
class LargeProtectedModelEncryptedSegment(
    val index: Int,
    ciphertextBody: ByteArray,
    authenticationTag: ByteArray
) {
    private val ciphertextBodyBytes = ciphertextBody.copyOf()
    private val authenticationTagBytes = authenticationTag.copyOf()

    val ciphertextBodySizeBytes: Int
        get() = ciphertextBodyBytes.size

    val authenticationTagSizeBytes: Int
        get() = authenticationTagBytes.size

    fun copyCiphertextBody(): ByteArray = ciphertextBodyBytes.copyOf()
    fun copyAuthenticationTag(): ByteArray = authenticationTagBytes.copyOf()

    override fun toString(): String =
        "LargeProtectedModelEncryptedSegment(index=$index, " +
            "ciphertextBody=<redacted:${ciphertextBodyBytes.size} bytes>, " +
            "authenticationTag=<redacted:${authenticationTagBytes.size} bytes>)"
}

enum class LargeProtectedModelSegmentSourceFailure {
    REJECTED,
    PROVIDER_FAILED
}

sealed interface LargeProtectedModelSegmentReadResult {
    data class Segment(
        val value: LargeProtectedModelEncryptedSegment
    ) : LargeProtectedModelSegmentReadResult

    data object Missing : LargeProtectedModelSegmentReadResult

    data class Rejected(
        val reason: LargeProtectedModelSegmentSourceFailure =
            LargeProtectedModelSegmentSourceFailure.REJECTED
    ) : LargeProtectedModelSegmentReadResult

    data class Failed(
        val reason: LargeProtectedModelSegmentSourceFailure =
            LargeProtectedModelSegmentSourceFailure.PROVIDER_FAILED,
        val throwable: Throwable? = null
    ) : LargeProtectedModelSegmentReadResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

interface LargeProtectedModelEncryptedSegmentSource {
    val segmentCount: Int
    fun read(index: Int): LargeProtectedModelSegmentReadResult
}

/** Plaintext is authenticated before callback and the supplied mutable bytes are cleared after it returns. */
fun interface LargeProtectedModelPlaintextSegmentConsumer {
    fun consume(
        model: ProtectedModelReference,
        segmentIndex: Int,
        plaintext: ByteArray
    )
}

/** Purpose-specific canonical GCM AAD. Callers cannot inject arbitrary AAD fields. */
internal object LargeProtectedModelSegmentAadCodec {
    fun encode(
        packageManifest: LargeProtectedModelSignedManifest,
        segment: LargeProtectedModelSegment
    ): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(AAD_VERSION)
            out.writeInt(packageManifest.formatVersion.value)
            writeString(out, packageManifest.payload.model.packageId.value)
            out.writeLong(packageManifest.payload.model.generation.value)
            writeString(out, packageManifest.modelProfileId.value)
            writeString(out, packageManifest.payload.profile.id.value)
            out.writeInt(packageManifest.payload.profile.version.value)
            writeString(out, packageManifest.payload.modelDek.id.value)
            out.writeLong(packageManifest.payload.modelDek.generation.value)
            out.writeInt(segment.index)
            out.writeInt(packageManifest.payload.segmentCount)
            out.writeLong(segment.plaintextSizeBytes)
            out.writeLong(segment.ciphertextBodySizeBytes)
            out.writeLong(segment.protectedPayloadSizeBytes)
        }
    }.toByteArray()

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.encodeToByteArray()
        try {
            out.writeInt(bytes.size)
            out.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private const val AAD_VERSION = 1
}

enum class LargeProtectedModelSegmentedOpenFailure {
    PACKAGE_UNSUPPORTED,
    SIGNER_UNAVAILABLE,
    PACKAGE_SIGNATURE_INVALID,
    PACKAGE_VERIFICATION_FAILED,
    SOURCE_SEGMENT_COUNT_MISMATCH,
    SEGMENT_MISSING,
    SEGMENT_SOURCE_REJECTED,
    SEGMENT_SOURCE_FAILED,
    SEGMENT_INDEX_MISMATCH,
    CIPHERTEXT_BODY_SIZE_MISMATCH,
    AUTHENTICATION_TAG_SIZE_MISMATCH,
    PROTECTED_PAYLOAD_SIZE_MISMATCH,
    PROTECTED_PAYLOAD_DIGEST_MISMATCH,
    MODEL_DEK_UNAVAILABLE,
    MODEL_DEK_REJECTED,
    AUTHENTICATED_DECRYPTION_FAILED,
    PLAINTEXT_SIZE_MISMATCH,
    CONSUMER_FAILED,
    RESOURCE_LIMIT_REJECTED,
    PROVIDER_FAILED
}

sealed interface LargeProtectedModelSegmentedOpenResult {
    data class Completed(
        val model: ProtectedModelReference,
        val segmentCount: Int,
        val plaintextBytes: Long
    ) : LargeProtectedModelSegmentedOpenResult

    data class Rejected(
        val reason: LargeProtectedModelSegmentedOpenFailure
    ) : LargeProtectedModelSegmentedOpenResult

    data class Failed(
        val reason: LargeProtectedModelSegmentedOpenFailure,
        val throwable: Throwable? = null
    ) : LargeProtectedModelSegmentedOpenResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

/**
 * Bounded sequential authenticated loader for the segmented profile.
 *
 * This primitive does not stage files or publish runtime model state. Earlier successful consumer
 * side effects are not rolled back if a later segment fails; terminal success is emitted only after
 * every exact signed segment authenticates and is consumed in order.
 */
class LargeProtectedModelSegmentedPayloadLoader(
    private val packageVerifier: LargeProtectedModelPackageVerifier,
    private val dekResolver: ProtectedModelDekResolver
) {
    fun open(
        envelope: LargeProtectedModelPackageEnvelope,
        source: LargeProtectedModelEncryptedSegmentSource,
        consumer: LargeProtectedModelPlaintextSegmentConsumer
    ): LargeProtectedModelSegmentedOpenResult {
        val verified = when (val verification = packageVerifier.verify(envelope)) {
            is LargeProtectedModelPackageVerificationResult.Verified -> verification.value
            is LargeProtectedModelPackageVerificationResult.Rejected ->
                return LargeProtectedModelSegmentedOpenResult.Rejected(
                    mapVerificationFailure(verification.reason)
                )
            is LargeProtectedModelPackageVerificationResult.Failed ->
                return LargeProtectedModelSegmentedOpenResult.Failed(
                    LargeProtectedModelSegmentedOpenFailure.PACKAGE_VERIFICATION_FAILED,
                    verification.throwable
                )
        }
        val manifest = verified.manifest
        val payload = manifest.payload

        val sourceCount = try {
            source.segmentCount
        } catch (throwable: Throwable) {
            return LargeProtectedModelSegmentedOpenResult.Failed(
                LargeProtectedModelSegmentedOpenFailure.SEGMENT_SOURCE_FAILED,
                throwable
            )
        }
        if (sourceCount != payload.segmentCount) {
            return LargeProtectedModelSegmentedOpenResult.Rejected(
                LargeProtectedModelSegmentedOpenFailure.SOURCE_SEGMENT_COUNT_MISMATCH
            )
        }

        val key = try {
            dekResolver.resolveForProtectedModelOpen(payload.model, payload.modelDek)
        } catch (throwable: Throwable) {
            return LargeProtectedModelSegmentedOpenResult.Failed(
                LargeProtectedModelSegmentedOpenFailure.PROVIDER_FAILED,
                throwable
            )
        } ?: return LargeProtectedModelSegmentedOpenResult.Rejected(
            LargeProtectedModelSegmentedOpenFailure.MODEL_DEK_UNAVAILABLE
        )

        if (!isAcceptedModelKey(key)) {
            return LargeProtectedModelSegmentedOpenResult.Rejected(
                LargeProtectedModelSegmentedOpenFailure.MODEL_DEK_REJECTED
            )
        }

        val signedSegments = payload.segments()
        var consumedPlaintext = 0L
        for (expectedSegment in signedSegments) {
            val read = try {
                source.read(expectedSegment.index)
            } catch (throwable: Throwable) {
                return LargeProtectedModelSegmentedOpenResult.Failed(
                    LargeProtectedModelSegmentedOpenFailure.SEGMENT_SOURCE_FAILED,
                    throwable
                )
            }
            val encrypted = when (read) {
                is LargeProtectedModelSegmentReadResult.Segment -> read.value
                LargeProtectedModelSegmentReadResult.Missing ->
                    return rejected(LargeProtectedModelSegmentedOpenFailure.SEGMENT_MISSING)
                is LargeProtectedModelSegmentReadResult.Rejected ->
                    return rejected(LargeProtectedModelSegmentedOpenFailure.SEGMENT_SOURCE_REJECTED)
                is LargeProtectedModelSegmentReadResult.Failed ->
                    return LargeProtectedModelSegmentedOpenResult.Failed(
                        LargeProtectedModelSegmentedOpenFailure.SEGMENT_SOURCE_FAILED,
                        read.throwable
                    )
            }

            val segmentResult = consumeOneSegment(
                manifest = manifest,
                signedSegment = expectedSegment,
                encrypted = encrypted,
                key = key,
                consumer = consumer
            )
            when (segmentResult) {
                is SegmentConsumptionResult.Consumed -> {
                    consumedPlaintext = try {
                        Math.addExact(consumedPlaintext, segmentResult.plaintextBytes)
                    } catch (_: ArithmeticException) {
                        return rejected(LargeProtectedModelSegmentedOpenFailure.RESOURCE_LIMIT_REJECTED)
                    }
                }
                is SegmentConsumptionResult.Rejected -> return rejected(segmentResult.reason)
                is SegmentConsumptionResult.Failed ->
                    return LargeProtectedModelSegmentedOpenResult.Failed(
                        segmentResult.reason,
                        segmentResult.throwable
                    )
            }
        }

        if (consumedPlaintext != payload.totalPlaintextSizeBytes) {
            return rejected(LargeProtectedModelSegmentedOpenFailure.PLAINTEXT_SIZE_MISMATCH)
        }
        return LargeProtectedModelSegmentedOpenResult.Completed(
            model = payload.model,
            segmentCount = payload.segmentCount,
            plaintextBytes = consumedPlaintext
        )
    }

    private fun consumeOneSegment(
        manifest: LargeProtectedModelSignedManifest,
        signedSegment: LargeProtectedModelSegment,
        encrypted: LargeProtectedModelEncryptedSegment,
        key: SecretKey,
        consumer: LargeProtectedModelPlaintextSegmentConsumer
    ): SegmentConsumptionResult {
        if (encrypted.index != signedSegment.index) {
            return SegmentConsumptionResult.Rejected(
                LargeProtectedModelSegmentedOpenFailure.SEGMENT_INDEX_MISMATCH
            )
        }
        if (encrypted.ciphertextBodySizeBytes.toLong() != signedSegment.ciphertextBodySizeBytes) {
            return SegmentConsumptionResult.Rejected(
                LargeProtectedModelSegmentedOpenFailure.CIPHERTEXT_BODY_SIZE_MISMATCH
            )
        }
        if (encrypted.authenticationTagSizeBytes != SEGMENT_AUTHENTICATION_TAG_SIZE_BYTES) {
            return SegmentConsumptionResult.Rejected(
                LargeProtectedModelSegmentedOpenFailure.AUTHENTICATION_TAG_SIZE_MISMATCH
            )
        }
        val observedProtectedSize = try {
            Math.addExact(
                encrypted.ciphertextBodySizeBytes.toLong(),
                encrypted.authenticationTagSizeBytes.toLong()
            )
        } catch (_: ArithmeticException) {
            return SegmentConsumptionResult.Rejected(
                LargeProtectedModelSegmentedOpenFailure.RESOURCE_LIMIT_REJECTED
            )
        }
        if (observedProtectedSize != signedSegment.protectedPayloadSizeBytes) {
            return SegmentConsumptionResult.Rejected(
                LargeProtectedModelSegmentedOpenFailure.PROTECTED_PAYLOAD_SIZE_MISMATCH
            )
        }

        var body: ByteArray? = null
        var tag: ByteArray? = null
        var expectedDigest: ByteArray? = null
        var actualDigest: ByteArray? = null
        var aad: ByteArray? = null
        var nonce: ByteArray? = null
        var cipherInput: ByteArray? = null
        var plaintext: ByteArray? = null
        try {
            body = encrypted.copyCiphertextBody()
            tag = encrypted.copyAuthenticationTag()
            expectedDigest = signedSegment.copyProtectedPayloadDigest()

            val digest = MessageDigest.getInstance(PROTECTED_PAYLOAD_DIGEST_ALGORITHM)
            digest.update(body)
            actualDigest = digest.digest(tag)
            if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
                return SegmentConsumptionResult.Rejected(
                    LargeProtectedModelSegmentedOpenFailure.PROTECTED_PAYLOAD_DIGEST_MISMATCH
                )
            }

            aad = LargeProtectedModelSegmentAadCodec.encode(manifest, signedSegment)
            nonce = signedSegment.copyNonce()
            cipherInput = ByteArray(body.size + tag.size).also { combined ->
                body.copyInto(combined, destinationOffset = 0)
                tag.copyInto(combined, destinationOffset = body.size)
            }

            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(manifest.encryptionProfile.authenticationTagSizeBits, nonce)
            )
            cipher.updateAAD(aad)
            plaintext = try {
                cipher.doFinal(cipherInput)
            } catch (_: AEADBadTagException) {
                return SegmentConsumptionResult.Rejected(
                    LargeProtectedModelSegmentedOpenFailure.AUTHENTICATED_DECRYPTION_FAILED
                )
            }

            if (plaintext.size.toLong() != signedSegment.plaintextSizeBytes) {
                return SegmentConsumptionResult.Rejected(
                    LargeProtectedModelSegmentedOpenFailure.PLAINTEXT_SIZE_MISMATCH
                )
            }

            try {
                consumer.consume(manifest.payload.model, signedSegment.index, plaintext)
            } catch (throwable: Throwable) {
                return SegmentConsumptionResult.Failed(
                    LargeProtectedModelSegmentedOpenFailure.CONSUMER_FAILED,
                    throwable
                )
            }
            return SegmentConsumptionResult.Consumed(plaintext.size.toLong())
        } catch (throwable: GeneralSecurityException) {
            return SegmentConsumptionResult.Failed(
                LargeProtectedModelSegmentedOpenFailure.PROVIDER_FAILED,
                throwable
            )
        } catch (throwable: Throwable) {
            return SegmentConsumptionResult.Failed(
                LargeProtectedModelSegmentedOpenFailure.PROVIDER_FAILED,
                throwable
            )
        } finally {
            body?.fill(0)
            tag?.fill(0)
            expectedDigest?.fill(0)
            actualDigest?.fill(0)
            aad?.fill(0)
            nonce?.fill(0)
            cipherInput?.fill(0)
            plaintext?.fill(0)
        }
    }

    private fun isAcceptedModelKey(key: SecretKey): Boolean {
        if (!key.algorithm.equals("AES", ignoreCase = true)) return false
        val encoded = try {
            key.encoded
        } catch (_: Throwable) {
            return false
        } ?: return false
        return try {
            encoded.size == AES_256_KEY_SIZE_BYTES
        } finally {
            encoded.fill(0)
        }
    }

    private fun mapVerificationFailure(
        failure: LargeProtectedModelPackageVerificationFailure
    ): LargeProtectedModelSegmentedOpenFailure = when (failure) {
        LargeProtectedModelPackageVerificationFailure.UNSUPPORTED_PROFILE ->
            LargeProtectedModelSegmentedOpenFailure.PACKAGE_UNSUPPORTED
        LargeProtectedModelPackageVerificationFailure.STRUCTURAL_IDENTIFIER_SIZE_INVALID,
        LargeProtectedModelPackageVerificationFailure.CANONICAL_MANIFEST_SIZE_INVALID ->
            LargeProtectedModelSegmentedOpenFailure.RESOURCE_LIMIT_REJECTED
        LargeProtectedModelPackageVerificationFailure.SIGNER_KEY_UNAVAILABLE ->
            LargeProtectedModelSegmentedOpenFailure.SIGNER_UNAVAILABLE
        LargeProtectedModelPackageVerificationFailure.SIGNATURE_INVALID ->
            LargeProtectedModelSegmentedOpenFailure.PACKAGE_SIGNATURE_INVALID
        LargeProtectedModelPackageVerificationFailure.PROVIDER_FAILED ->
            LargeProtectedModelSegmentedOpenFailure.PACKAGE_VERIFICATION_FAILED
    }

    private fun rejected(reason: LargeProtectedModelSegmentedOpenFailure) =
        LargeProtectedModelSegmentedOpenResult.Rejected(reason)

    private sealed interface SegmentConsumptionResult {
        data class Consumed(val plaintextBytes: Long) : SegmentConsumptionResult
        data class Rejected(val reason: LargeProtectedModelSegmentedOpenFailure) : SegmentConsumptionResult
        data class Failed(
            val reason: LargeProtectedModelSegmentedOpenFailure,
            val throwable: Throwable? = null
        ) : SegmentConsumptionResult
    }

    private companion object {
        const val AES_256_KEY_SIZE_BYTES = 32
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val PROTECTED_PAYLOAD_DIGEST_ALGORITHM = "SHA-256"
    }
}
