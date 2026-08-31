package pro.liliya.core.protectedmodel

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtectedModelPayloadCryptoContractTest {
    @Test
    fun exact_payload_opens_with_exact_model_dek_and_aad() {
        val fixture = encryptedFixture()
        val opener = ProtectedModelPayloadOpener(
            ModelDekResolver { reference ->
                if (reference == fixture.envelope.manifest.modelDek) {
                    ProtectedModelCryptoResult.Success(ModelDekMaterial(fixture.key.copyOf()))
                } else {
                    ProtectedModelCryptoResult.Rejected(ProtectedModelCryptoFailure.MODEL_DEK_UNAVAILABLE)
                }
            }
        )

        val opened = opener.open(fixture.envelope, fixture.ciphertext)
        val plaintext = assertIs<ProtectedModelCryptoResult.Success<ProtectedModelPlaintext>>(opened).value
        assertContentEquals(fixture.plaintext, plaintext.copyBytes())
    }

    @Test
    fun exact_manifest_binding_rejects_cross_model_substitution() {
        val fixture = encryptedFixture()
        val substitutedManifest = fixture.envelope.manifest.copy(
            model = fixture.envelope.manifest.model.copy(
                generation = ProtectedModelGeneration(2)
            )
        )
        val substituted = ProtectedModelPackageEnvelope(
            manifest = substitutedManifest,
            payloadDigest = fixture.envelope.copyPayloadDigest(),
            nonce = fixture.envelope.copyNonce(),
            authenticationTag = fixture.envelope.copyAuthenticationTag(),
            signature = fixture.envelope.copySignature()
        )
        val opener = ProtectedModelPayloadOpener(
            ModelDekResolver { ProtectedModelCryptoResult.Success(ModelDekMaterial(fixture.key.copyOf())) }
        )

        val result = opener.open(substituted, fixture.ciphertext)
        assertEquals(
            ProtectedModelCryptoFailure.AUTHENTICATION_FAILED,
            assertIs<ProtectedModelCryptoResult.Rejected>(result).reason
        )
    }

    @Test
    fun authentication_tag_tamper_fails_closed() {
        val fixture = encryptedFixture()
        val tamperedTag = fixture.envelope.copyAuthenticationTag().also {
            it[0] = (it[0].toInt() xor 0x01).toByte()
        }
        val tampered = ProtectedModelPackageEnvelope(
            manifest = fixture.envelope.manifest,
            payloadDigest = fixture.envelope.copyPayloadDigest(),
            nonce = fixture.envelope.copyNonce(),
            authenticationTag = tamperedTag,
            signature = fixture.envelope.copySignature()
        )
        val opener = ProtectedModelPayloadOpener(
            ModelDekResolver { ProtectedModelCryptoResult.Success(ModelDekMaterial(fixture.key.copyOf())) }
        )

        val result = opener.open(tampered, fixture.ciphertext)
        assertEquals(
            ProtectedModelCryptoFailure.AUTHENTICATION_FAILED,
            assertIs<ProtectedModelCryptoResult.Rejected>(result).reason
        )
    }

    @Test
    fun missing_model_dek_is_propagated_without_decrypt_attempt() {
        val fixture = encryptedFixture()
        val opener = ProtectedModelPayloadOpener(
            ModelDekResolver {
                ProtectedModelCryptoResult.Rejected(ProtectedModelCryptoFailure.MODEL_DEK_UNAVAILABLE)
            }
        )

        val result = opener.open(fixture.envelope, fixture.ciphertext)
        assertEquals(
            ProtectedModelCryptoFailure.MODEL_DEK_UNAVAILABLE,
            assertIs<ProtectedModelCryptoResult.Rejected>(result).reason
        )
    }

    @Test
    fun bounded_loader_rejects_manifest_size_over_limit_before_decryption() {
        val fixture = encryptedFixture()
        var resolverCalled = false
        val loader = ProtectedModelBoundedLoader(
            opener = ProtectedModelPayloadOpener(
                ModelDekResolver {
                    resolverCalled = true
                    ProtectedModelCryptoResult.Success(ModelDekMaterial(fixture.key.copyOf()))
                }
            ),
            maxPlaintextSizeBytes = fixture.plaintext.size.toLong() - 1
        )

        val result = loader.openAndUse(fixture.envelope, fixture.ciphertext) { it.size }
        assertEquals(
            ProtectedModelCryptoFailure.INVALID_REQUEST,
            assertIs<ProtectedModelLoaderResult.Rejected>(result).reason
        )
        assertFalse(resolverCalled)
    }

    @Test
    fun bounded_loader_handoff_is_cleared_after_consumer_returns() {
        val fixture = encryptedFixture()
        var observed: ByteArray? = null
        val loader = ProtectedModelBoundedLoader(
            opener = ProtectedModelPayloadOpener(
                ModelDekResolver {
                    ProtectedModelCryptoResult.Success(ModelDekMaterial(fixture.key.copyOf()))
                }
            ),
            maxPlaintextSizeBytes = 1024
        )

        val result = loader.openAndUse(fixture.envelope, fixture.ciphertext) { bytes ->
            observed = bytes
            assertContentEquals(fixture.plaintext, bytes)
            bytes.size
        }

        assertEquals(
            fixture.plaintext.size,
            assertIs<ProtectedModelLoaderResult.Loaded<Int>>(result).value
        )
        assertTrue(observed!!.all { it == 0.toByte() })
    }

    @Test
    fun sensitive_wrappers_and_failures_omit_secret_material_and_exception_message() {
        val material = ModelDekMaterial(ByteArray(32) { 7 })
        val plaintext = ProtectedModelPlaintext("secret-model-plaintext".encodeToByteArray())
        val failure = ProtectedModelCryptoResult.Failed(
            ProtectedModelCryptoFailure.PROVIDER_FAILED,
            IllegalStateException("secret-provider-message")
        )

        assertFalse(material.toString().contains("7, 7"))
        assertFalse(plaintext.toString().contains("secret-model-plaintext"))
        assertFalse(failure.toString().contains("secret-provider-message"))
    }

    private data class Fixture(
        val envelope: ProtectedModelPackageEnvelope,
        val ciphertext: ByteArray,
        val plaintext: ByteArray,
        val key: ByteArray
    )

    private fun encryptedFixture(): Fixture {
        val plaintext = "bounded protected model bytes".encodeToByteArray()
        val key = ByteArray(32) { (it + 11).toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val manifest = manifest(plaintext.size.toLong(), plaintext.size.toLong())
        val aad = ProtectedModelPayloadAssociatedData.encode(manifest)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce)
        )
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(plaintext)
        val ciphertext = sealed.copyOfRange(0, sealed.size - 16)
        val tag = sealed.copyOfRange(sealed.size - 16, sealed.size)

        val manifestWithCiphertextSize = manifest.copy(ciphertextSizeBytes = ciphertext.size.toLong())
        val aad2 = ProtectedModelPayloadAssociatedData.encode(manifestWithCiphertextSize)
        val cipher2 = Cipher.getInstance("AES/GCM/NoPadding")
        cipher2.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce)
        )
        cipher2.updateAAD(aad2)
        val sealed2 = cipher2.doFinal(plaintext)
        val finalCiphertext = sealed2.copyOfRange(0, sealed2.size - 16)
        val finalTag = sealed2.copyOfRange(sealed2.size - 16, sealed2.size)

        val digest = MessageDigest.getInstance("SHA-256").digest(finalCiphertext)
        val signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signatureInput = ProtectedModelManifestCanonicalCodec.signatureInput(
            manifestWithCiphertextSize,
            digest,
            nonce,
            finalTag
        )
        val signature = Signature.getInstance("Ed25519").run {
            initSign(signer.private)
            update(signatureInput)
            sign()
        }

        aad.fill(0)
        aad2.fill(0)
        sealed.fill(0)
        sealed2.fill(0)
        signatureInput.fill(0)

        return Fixture(
            envelope = ProtectedModelPackageEnvelope(
                manifest = manifestWithCiphertextSize,
                payloadDigest = digest,
                nonce = nonce,
                authenticationTag = finalTag,
                signature = signature
            ),
            ciphertext = finalCiphertext,
            plaintext = plaintext,
            key = key
        )
    }

    private fun manifest(plaintextSize: Long, ciphertextSize: Long) = ProtectedModelManifest(
        formatVersion = ProtectedModelFormatVersion(1),
        model = ProtectedModelReference(
            ProtectedModelPackageId("model-package"),
            ProtectedModelGeneration(1)
        ),
        profileId = ProtectedModelProfileId("gguf-q4"),
        plaintextSizeBytes = plaintextSize,
        ciphertextSizeBytes = ciphertextSize,
        modelDek = ModelDekReference(ModelDekId("model-dek"), ModelDekGeneration(1)),
        encryptionProfile = ProtectedModelEncryptionProfile.AES_256_GCM,
        signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
        signerId = ProtectedModelSignerId("release-signer")
    )
}
