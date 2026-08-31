package pro.liliya.core.protectedmodel

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ProtectedModelManifestVerificationContractTest {
    @Test
    fun canonical_manifest_encoding_is_deterministic() {
        val manifest = manifest()
        assertContentEquals(
            ProtectedModelManifestCanonicalCodec.encode(manifest),
            ProtectedModelManifestCanonicalCodec.encode(manifest)
        )
    }

    @Test
    fun exact_signed_payload_verifies() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val ciphertext = "protected-model-ciphertext".encodeToByteArray()
        val envelope = signedEnvelope(manifest(ciphertext.size.toLong()), ciphertext, keys.private)
        val verifier = ProtectedModelPackageVerifier(
            ProtectedModelSignerResolver { signerId, algorithm ->
                if (signerId == envelope.manifest.signerId &&
                    algorithm == ProtectedModelSignatureAlgorithm.ED25519
                ) keys.public else null
            }
        )

        val result = verifier.verify(envelope, ciphertext)
        assertEquals(
            envelope.manifest.model,
            assertIs<ProtectedModelVerificationResult.Verified>(result).model
        )
    }

    @Test
    fun payload_substitution_is_rejected_before_signature_acceptance() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val ciphertext = "protected-model-ciphertext".encodeToByteArray()
        val substituted = "substituted-model-payloadX".encodeToByteArray()
        val envelope = signedEnvelope(manifest(ciphertext.size.toLong()), ciphertext, keys.private)
        val verifier = ProtectedModelPackageVerifier(
            ProtectedModelSignerResolver { _, _ -> keys.public }
        )

        val result = verifier.verify(envelope, substituted)
        assertEquals(
            ProtectedModelVerificationFailure.PAYLOAD_DIGEST_MISMATCH,
            assertIs<ProtectedModelVerificationResult.Rejected>(result).reason
        )
    }

    @Test
    fun manifest_identity_substitution_invalidates_signature() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val ciphertext = "protected-model-ciphertext".encodeToByteArray()
        val original = signedEnvelope(manifest(ciphertext.size.toLong()), ciphertext, keys.private)
        val substitutedManifest = original.manifest.copy(
            model = original.manifest.model.copy(generation = ProtectedModelGeneration(2))
        )
        val substituted = ProtectedModelPackageEnvelope(
            manifest = substitutedManifest,
            payloadDigest = original.copyPayloadDigest(),
            nonce = original.copyNonce(),
            authenticationTag = original.copyAuthenticationTag(),
            signature = original.copySignature()
        )
        val verifier = ProtectedModelPackageVerifier(
            ProtectedModelSignerResolver { _, _ -> keys.public }
        )

        val result = verifier.verify(substituted, ciphertext)
        assertEquals(
            ProtectedModelVerificationFailure.SIGNATURE_INVALID,
            assertIs<ProtectedModelVerificationResult.Rejected>(result).reason
        )
    }

    @Test
    fun signer_mismatch_fails_closed() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val ciphertext = "protected-model-ciphertext".encodeToByteArray()
        val envelope = signedEnvelope(manifest(ciphertext.size.toLong()), ciphertext, keys.private)
        val verifier = ProtectedModelPackageVerifier(
            ProtectedModelSignerResolver { _, _ -> null }
        )

        val result = verifier.verify(envelope, ciphertext)
        assertEquals(
            ProtectedModelVerificationFailure.SIGNER_KEY_UNAVAILABLE,
            assertIs<ProtectedModelVerificationResult.Rejected>(result).reason
        )
    }

    @Test
    fun verification_failure_rendering_omits_exception_message() {
        val failure = ProtectedModelVerificationResult.Failed(
            ProtectedModelVerificationFailure.PROVIDER_FAILED,
            IllegalStateException("secret-provider-message")
        )
        val rendered = failure.toString()
        assertFalse(rendered.contains("secret-provider-message"))
        assertFalse(rendered.contains("IllegalStateException: secret"))
    }

    private fun signedEnvelope(
        manifest: ProtectedModelManifest,
        ciphertext: ByteArray,
        privateKey: java.security.PrivateKey
    ): ProtectedModelPackageEnvelope {
        val digest = MessageDigest.getInstance("SHA-256").digest(ciphertext)
        val input = ProtectedModelManifestCanonicalCodec.signatureInput(manifest, digest)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(privateKey)
            update(input)
            sign()
        }
        input.fill(0)
        return ProtectedModelPackageEnvelope(
            manifest = manifest,
            payloadDigest = digest,
            nonce = ByteArray(12) { (it + 1).toByte() },
            authenticationTag = ByteArray(16) { (it + 17).toByte() },
            signature = signature
        )
    }

    private fun manifest(ciphertextSize: Long = 26L) = ProtectedModelManifest(
        formatVersion = ProtectedModelFormatVersion(1),
        model = ProtectedModelReference(
            ProtectedModelPackageId("model-package"),
            ProtectedModelGeneration(1)
        ),
        profileId = ProtectedModelProfileId("gguf-q4"),
        plaintextSizeBytes = 1024,
        ciphertextSizeBytes = ciphertextSize,
        modelDek = ModelDekReference(ModelDekId("model-dek"), ModelDekGeneration(1)),
        encryptionProfile = ProtectedModelEncryptionProfile.AES_256_GCM,
        signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
        signerId = ProtectedModelSignerId("release-signer")
    )
}
