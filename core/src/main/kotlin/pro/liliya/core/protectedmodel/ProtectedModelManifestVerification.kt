package pro.liliya.core.protectedmodel

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature

/** Deterministic v0.1 canonical encoding for the signed protected-model manifest. */
object ProtectedModelManifestCanonicalCodec {
    fun encode(manifest: ProtectedModelManifest): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(CANONICAL_VERSION)
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
            writeString(out, manifest.signatureAlgorithm.name)
            writeString(out, manifest.signerId.value)
        }
        return buffer.toByteArray()
    }

    fun signatureInput(
        manifest: ProtectedModelManifest,
        payloadDigest: ByteArray,
        nonce: ByteArray,
        authenticationTag: ByteArray
    ): ByteArray {
        require(payloadDigest.isNotEmpty()) { "protected model payload digest must not be empty" }
        require(nonce.size == manifest.encryptionProfile.nonceSizeBytes) {
            "invalid protected model nonce size"
        }
        require(authenticationTag.size * 8 == manifest.encryptionProfile.authenticationTagSizeBits) {
            "invalid protected model authentication tag size"
        }
        val manifestBytes = encode(manifest)
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(SIGNATURE_INPUT_VERSION)
                writeBytes(out, manifestBytes)
                writeBytes(out, payloadDigest)
                writeBytes(out, nonce)
                writeBytes(out, authenticationTag)
            }
        }.toByteArray()
    }

    private fun writeString(out: DataOutputStream, value: String) {
        writeBytes(out, value.encodeToByteArray())
    }

    private fun writeBytes(out: DataOutputStream, value: ByteArray) {
        out.writeInt(value.size)
        out.write(value)
    }

    private const val CANONICAL_VERSION = 1
    private const val SIGNATURE_INPUT_VERSION = 1
}

fun interface ProtectedModelSignerResolver {
    fun resolve(
        signerId: ProtectedModelSignerId,
        algorithm: ProtectedModelSignatureAlgorithm
    ): PublicKey?
}

enum class ProtectedModelVerificationFailure {
    PAYLOAD_SIZE_MISMATCH,
    PAYLOAD_DIGEST_MISMATCH,
    SIGNER_KEY_UNAVAILABLE,
    SIGNATURE_INVALID,
    PROVIDER_FAILED
}

sealed interface ProtectedModelVerificationResult {
    data class Verified(val model: ProtectedModelReference) : ProtectedModelVerificationResult
    data class Rejected(val reason: ProtectedModelVerificationFailure) : ProtectedModelVerificationResult
    data class Failed(
        val reason: ProtectedModelVerificationFailure,
        val throwable: Throwable? = null
    ) : ProtectedModelVerificationResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

/**
 * Exact package authenticity/integrity boundary. This proves only the configured signer and payload binding;
 * it is not License entitlement, key release, Authority or execution permission.
 */
class ProtectedModelPackageVerifier(
    private val signerResolver: ProtectedModelSignerResolver
) {
    fun verify(
        envelope: ProtectedModelPackageEnvelope,
        ciphertext: ByteArray
    ): ProtectedModelVerificationResult {
        val manifest = envelope.manifest
        if (ciphertext.size.toLong() != manifest.ciphertextSizeBytes) {
            return ProtectedModelVerificationResult.Rejected(
                ProtectedModelVerificationFailure.PAYLOAD_SIZE_MISMATCH
            )
        }

        var expectedDigest: ByteArray? = null
        var actualDigest: ByteArray? = null
        var nonce: ByteArray? = null
        var authenticationTag: ByteArray? = null
        var signatureInput: ByteArray? = null
        var signatureBytes: ByteArray? = null
        return try {
            expectedDigest = envelope.copyPayloadDigest()
            actualDigest = MessageDigest.getInstance(PAYLOAD_DIGEST_ALGORITHM).digest(ciphertext)
            if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
                return ProtectedModelVerificationResult.Rejected(
                    ProtectedModelVerificationFailure.PAYLOAD_DIGEST_MISMATCH
                )
            }

            val signerKey = signerResolver.resolve(manifest.signerId, manifest.signatureAlgorithm)
                ?: return ProtectedModelVerificationResult.Rejected(
                    ProtectedModelVerificationFailure.SIGNER_KEY_UNAVAILABLE
                )

            nonce = envelope.copyNonce()
            authenticationTag = envelope.copyAuthenticationTag()
            signatureInput = ProtectedModelManifestCanonicalCodec.signatureInput(
                manifest = manifest,
                payloadDigest = expectedDigest,
                nonce = nonce,
                authenticationTag = authenticationTag
            )
            signatureBytes = envelope.copySignature()

            val verifier = Signature.getInstance(signatureName(manifest.signatureAlgorithm))
            verifier.initVerify(signerKey)
            verifier.update(signatureInput)
            if (verifier.verify(signatureBytes)) {
                ProtectedModelVerificationResult.Verified(manifest.model)
            } else {
                ProtectedModelVerificationResult.Rejected(
                    ProtectedModelVerificationFailure.SIGNATURE_INVALID
                )
            }
        } catch (throwable: Throwable) {
            ProtectedModelVerificationResult.Failed(
                ProtectedModelVerificationFailure.PROVIDER_FAILED,
                throwable
            )
        } finally {
            expectedDigest?.fill(0)
            actualDigest?.fill(0)
            nonce?.fill(0)
            authenticationTag?.fill(0)
            signatureInput?.fill(0)
            signatureBytes?.fill(0)
        }
    }

    private fun signatureName(algorithm: ProtectedModelSignatureAlgorithm): String = when (algorithm) {
        ProtectedModelSignatureAlgorithm.ED25519 -> "Ed25519"
    }

    private companion object {
        const val PAYLOAD_DIGEST_ALGORITHM = "SHA-256"
    }
}
