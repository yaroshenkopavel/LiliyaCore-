package pro.liliya.core.protectedmodel

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtectedModelPayloadLoaderContractTest {
    @Test
    fun exact_verified_package_decrypts_and_hands_off_bounded_plaintext() {
        val fixture = fixture("model-bytes-v1".encodeToByteArray())
        var resolvedModel: ProtectedModelReference? = null
        var resolvedDek: ModelDekReference? = null
        val loader = loader(fixture) { model, dek ->
            resolvedModel = model
            resolvedDek = dek
            fixture.modelKey
        }

        val result = loader.open(fixture.envelope, fixture.ciphertext) { model, plaintext ->
            assertEquals(fixture.manifest.model, model)
            assertContentEquals(fixture.plaintext, plaintext)
            "runtime-handle"
        }

        val opened = assertIs<ProtectedModelOpenResult.Opened<String>>(result)
        assertEquals(fixture.manifest.model, opened.model)
        assertEquals("runtime-handle", opened.value)
        assertEquals(fixture.manifest.model, resolvedModel)
        assertEquals(fixture.manifest.modelDek, resolvedDek)
    }

    @Test
    fun package_verification_failure_blocks_key_resolution() {
        val fixture = fixture("model-bytes-v1".encodeToByteArray())
        val substituted = fixture.ciphertext.copyOf().also {
            it[0] = (it[0].toInt() xor 0x01).toByte()
        }
        var keyResolutionCalled = false
        val loader = loader(fixture) { _, _ ->
            keyResolutionCalled = true
            fixture.modelKey
        }

        val result = loader.open(fixture.envelope, substituted) { _, _ -> "unused" }

        assertEquals(
            ProtectedModelOpenFailure.PACKAGE_VERIFICATION_REJECTED,
            assertIs<ProtectedModelOpenResult.Rejected>(result).reason
        )
        assertFalse(keyResolutionCalled)
    }

    @Test
    fun wrong_exact_model_dek_fails_authenticated_decryption() {
        val fixture = fixture("model-bytes-v1".encodeToByteArray())
        val wrongKey = newAesKey()
        val loader = loader(fixture) { model, dek ->
            if (model == fixture.manifest.model && dek == fixture.manifest.modelDek) wrongKey else null
        }

        val result = loader.open(fixture.envelope, fixture.ciphertext) { _, _ -> "unused" }

        assertEquals(
            ProtectedModelOpenFailure.AUTHENTICATED_DECRYPTION_FAILED,
            assertIs<ProtectedModelOpenResult.Rejected>(result).reason
        )
    }

    @Test
    fun unavailable_exact_dek_fails_closed() {
        val fixture = fixture("model-bytes-v1".encodeToByteArray())
        val loader = loader(fixture) { _, _ -> null }

        val result = loader.open(fixture.envelope, fixture.ciphertext) { _, _ -> "unused" }

        assertEquals(
            ProtectedModelOpenFailure.MODEL_DEK_UNAVAILABLE,
            assertIs<ProtectedModelOpenResult.Rejected>(result).reason
        )
    }

    @Test
    fun non_aes256_dek_is_rejected_before_decryption() {
        val fixture = fixture("model-bytes-v1".encodeToByteArray())
        val aes128 = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        val loader = loader(fixture) { _, _ -> aes128 }

        val result = loader.open(fixture.envelope, fixture.ciphertext) { _, _ -> "unused" }

        assertEquals(
            ProtectedModelOpenFailure.MODEL_DEK_REJECTED,
            assertIs<ProtectedModelOpenResult.Rejected>(result).reason
        )
    }

    @Test
    fun plaintext_bound_is_enforced_before_key_resolution() {
        val fixture = fixture("model-bytes-v1".encodeToByteArray())
        var keyResolutionCalled = false
        val loader = ProtectedModelPayloadLoader(
            verifier = fixture.verifier,
            dekResolver = ProtectedModelDekResolver { _, _ ->
                keyResolutionCalled = true
                fixture.modelKey
            },
            maxPlaintextSizeBytes = fixture.plaintext.size.toLong() - 1L
        )

        val result = loader.open(fixture.envelope, fixture.ciphertext) { _, _ -> "unused" }

        assertEquals(
            ProtectedModelOpenFailure.PLAINTEXT_SIZE_OUT_OF_BOUNDS,
            assertIs<ProtectedModelOpenResult.Rejected>(result).reason
        )
        assertFalse(keyResolutionCalled)
    }

    @Test
    fun ciphertext_bound_is_enforced_before_verification_or_key_resolution() {
        val fixture = fixture("model-bytes-v1".encodeToByteArray())
        val oversizedManifest = fixture.manifest.copy(
            plaintextSizeBytes = fixture.manifest.plaintextSizeBytes,
            ciphertextSizeBytes = fixture.manifest.ciphertextSizeBytes + 1L
        )
        val oversizedEnvelope = ProtectedModelPackageEnvelope(
            manifest = oversizedManifest,
            payloadDigest = fixture.envelope.copyPayloadDigest(),
            nonce = fixture.envelope.copyNonce(),
            authenticationTag = fixture.envelope.copyAuthenticationTag(),
            signature = fixture.envelope.copySignature()
        )
        var keyResolutionCalled = false
        val loader = loader(fixture) { _, _ ->
            keyResolutionCalled = true
            fixture.modelKey
        }

        val result = loader.open(oversizedEnvelope, fixture.ciphertext) { _, _ -> "unused" }

        assertEquals(
            ProtectedModelOpenFailure.CIPHERTEXT_SIZE_OUT_OF_BOUNDS,
            assertIs<ProtectedModelOpenResult.Rejected>(result).reason
        )
        assertFalse(keyResolutionCalled)
    }

    @Test
    fun plaintext_buffer_is_zeroed_after_synchronous_handoff() {
        val fixture = fixture("sensitive-model-plaintext".encodeToByteArray())
        var handedOff: ByteArray? = null
        val loader = loader(fixture) { _, _ -> fixture.modelKey }

        val result = loader.open(fixture.envelope, fixture.ciphertext) { _, plaintext ->
            handedOff = plaintext
            assertContentEquals(fixture.plaintext, plaintext)
            plaintext.size
        }

        assertIs<ProtectedModelOpenResult.Opened<Int>>(result)
        val retainedReference = requireNotNull(handedOff)
        assertTrue(retainedReference.all { it == 0.toByte() })
    }

    @Test
    fun consumer_failure_is_typed_and_omits_secret_message_from_rendering() {
        val fixture = fixture("model-bytes-v1".encodeToByteArray())
        val loader = loader(fixture) { _, _ -> fixture.modelKey }

        val result = loader.open(fixture.envelope, fixture.ciphertext) { _, _ ->
            error("secret-consumer-message")
        }

        val failed = assertIs<ProtectedModelOpenResult.Failed>(result)
        assertEquals(ProtectedModelOpenFailure.CONSUMER_FAILED, failed.reason)
        val rendered = failed.toString()
        assertFalse(rendered.contains("secret-consumer-message"))
        assertFalse(rendered.contains("IllegalStateException: secret"))
    }

    private fun loader(
        fixture: Fixture,
        resolver: (ProtectedModelReference, ModelDekReference) -> SecretKey?
    ) = ProtectedModelPayloadLoader(
        verifier = fixture.verifier,
        dekResolver = ProtectedModelDekResolver(resolver),
        maxPlaintextSizeBytes = 1024L * 1024L
    )

    private fun fixture(plaintext: ByteArray): Fixture {
        val signerKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val modelKey = newAesKey()
        val model = ProtectedModelReference(
            packageId = ProtectedModelPackageId("model-package"),
            generation = ProtectedModelGeneration(1)
        )
        val dek = ModelDekReference(ModelDekId("model-dek"), ModelDekGeneration(1))
        val nonce = ByteArray(12) { (it + 11).toByte() }

        val provisionalManifest = ProtectedModelManifest(
            formatVersion = ProtectedModelFormatVersion(1),
            model = model,
            profileId = ProtectedModelProfileId("gguf-q4"),
            plaintextSizeBytes = plaintext.size.toLong(),
            ciphertextSizeBytes = plaintext.size.toLong(),
            modelDek = dek,
            encryptionProfile = ProtectedModelEncryptionProfile.AES_256_GCM,
            signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
            signerId = ProtectedModelSignerId("release-signer")
        )
        val encrypted = encrypt(provisionalManifest, plaintext, modelKey, nonce)
        val manifest = provisionalManifest.copy(ciphertextSizeBytes = encrypted.ciphertext.size.toLong())
        val digest = MessageDigest.getInstance("SHA-256").digest(encrypted.ciphertext)
        val signature = signEnvelope(
            manifest = manifest,
            digest = digest,
            nonce = nonce,
            authenticationTag = encrypted.authenticationTag,
            privateKey = signerKeys.private
        )
        val envelope = ProtectedModelPackageEnvelope(
            manifest = manifest,
            payloadDigest = digest,
            nonce = nonce,
            authenticationTag = encrypted.authenticationTag,
            signature = signature
        )
        val verifier = ProtectedModelPackageVerifier(
            ProtectedModelSignerResolver { signerId, algorithm ->
                if (signerId == manifest.signerId && algorithm == manifest.signatureAlgorithm) {
                    signerKeys.public
                } else {
                    null
                }
            }
        )
        return Fixture(
            plaintext = plaintext.copyOf(),
            ciphertext = encrypted.ciphertext,
            manifest = manifest,
            envelope = envelope,
            modelKey = modelKey,
            verifier = verifier
        )
    }

    private fun encrypt(
        manifest: ProtectedModelManifest,
        plaintext: ByteArray,
        key: SecretKey,
        nonce: ByteArray
    ): EncryptedParts {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(ProtectedModelManifestCanonicalCodec.encode(manifest))
        val output = cipher.doFinal(plaintext)
        val tagSize = 16
        return EncryptedParts(
            ciphertext = output.copyOfRange(0, output.size - tagSize),
            authenticationTag = output.copyOfRange(output.size - tagSize, output.size)
        )
    }

    private fun signEnvelope(
        manifest: ProtectedModelManifest,
        digest: ByteArray,
        nonce: ByteArray,
        authenticationTag: ByteArray,
        privateKey: PrivateKey
    ): ByteArray {
        val input = ProtectedModelManifestCanonicalCodec.signatureInput(
            manifest = manifest,
            payloadDigest = digest,
            nonce = nonce,
            authenticationTag = authenticationTag
        )
        return try {
            Signature.getInstance("Ed25519").run {
                initSign(privateKey)
                update(input)
                sign()
            }
        } finally {
            input.fill(0)
        }
    }

    private fun newAesKey(): SecretKey = KeyGenerator.getInstance("AES").apply {
        init(256)
    }.generateKey()

    private data class EncryptedParts(
        val ciphertext: ByteArray,
        val authenticationTag: ByteArray
    )

    private data class Fixture(
        val plaintext: ByteArray,
        val ciphertext: ByteArray,
        val manifest: ProtectedModelManifest,
        val envelope: ProtectedModelPackageEnvelope,
        val modelKey: SecretKey,
        val verifier: ProtectedModelPackageVerifier
    )
}
