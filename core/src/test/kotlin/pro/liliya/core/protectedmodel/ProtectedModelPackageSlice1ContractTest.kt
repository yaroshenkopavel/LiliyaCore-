package pro.liliya.core.protectedmodel

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtectedModelPackageSlice1ContractTest {
    @Test
    fun package_ownership_is_exact_and_stale_owner_cannot_retire_replacement() {
        val store = ProtectedModelPackageStore()
        val id = ProtectedModelPackageId("model-secret")

        val first = assertIs<ProtectedModelPackageRegistrationResult.Registered>(store.register(id)).ownership
        assertEquals(1L, first.reference.generation.value)
        assertTrue(first.retire())
        assertNull(store.inspect(id))

        val second = assertIs<ProtectedModelPackageRegistrationResult.Registered>(store.register(id)).ownership
        assertEquals(2L, second.reference.generation.value)
        assertFalse(first.retire())
        assertEquals(second.reference, store.inspect(id))
    }

    @Test
    fun duplicate_live_package_registration_rejects() {
        val store = ProtectedModelPackageStore()
        val id = ProtectedModelPackageId("model-a")

        assertIs<ProtectedModelPackageRegistrationResult.Registered>(store.register(id))
        val rejected = assertIs<ProtectedModelPackageRegistrationResult.Rejected>(store.register(id))

        assertTrue(rejected.reason.contains("already registered"))
        assertEquals(1, store.snapshot().size)
    }

    @Test
    fun generation_overflow_fails_closed() {
        val store = ProtectedModelPackageStore(Long.MAX_VALUE)
        val rejected = assertIs<ProtectedModelPackageRegistrationResult.Rejected>(
            store.register(ProtectedModelPackageId("model-a"))
        )

        assertTrue(rejected.reason.contains("overflow"))
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun protected_package_envelope_detaches_mutable_security_bytes() {
        val digest = byteArrayOf(1, 2, 3, 4)
        val nonce = ByteArray(12) { it.toByte() }
        val tag = ByteArray(16) { (it + 16).toByte() }
        val signature = byteArrayOf(9, 8, 7, 6)
        val envelope = ProtectedModelPackageEnvelope(manifest(), digest, nonce, tag, signature)

        digest.fill(99)
        nonce.fill(99)
        tag.fill(99)
        signature.fill(99)

        assertContentEquals(byteArrayOf(1, 2, 3, 4), envelope.copyPayloadDigest())
        assertNotEquals(99.toByte(), envelope.copyNonce()[0])
        assertNotEquals(99.toByte(), envelope.copyAuthenticationTag()[0])
        assertContentEquals(byteArrayOf(9, 8, 7, 6), envelope.copySignature())

        val copy = envelope.copySignature()
        copy.fill(0)
        assertContentEquals(byteArrayOf(9, 8, 7, 6), envelope.copySignature())
    }

    @Test
    fun identifiers_and_cryptographic_bytes_render_redacted() {
        val rendered = ProtectedModelPackageEnvelope(
            manifest(),
            byteArrayOf(1, 2, 3),
            ByteArray(12),
            ByteArray(16),
            byteArrayOf(4, 5, 6)
        ).toString()

        assertFalse(rendered.contains("package-secret"))
        assertFalse(rendered.contains("model-dek-secret"))
        assertFalse(rendered.contains("signer-secret"))
        assertFalse(rendered.contains("profile-secret"))
        assertTrue(rendered.contains("[redacted]"))
        assertTrue(rendered.contains("payloadDigest=<redacted:"))
        assertTrue(rendered.contains("signature=<redacted:"))
    }

    @Test
    fun v0_1_encryption_profile_is_exact_aes_256_gcm() {
        val profile = ProtectedModelEncryptionProfile.AES_256_GCM
        assertEquals(ProtectedModelEncryptionAlgorithm.AES_256_GCM, profile.algorithm)
        assertEquals(256, profile.keySizeBits)
        assertEquals(12, profile.nonceSizeBytes)
        assertEquals(128, profile.authenticationTagSizeBits)
    }

    private fun manifest() = ProtectedModelManifest(
        formatVersion = ProtectedModelFormatVersion(1),
        model = ProtectedModelReference(
            packageId = ProtectedModelPackageId("package-secret"),
            generation = ProtectedModelGeneration(1)
        ),
        profileId = ProtectedModelProfileId("profile-secret"),
        plaintextSizeBytes = 1024,
        ciphertextSizeBytes = 1040,
        modelDek = ModelDekReference(ModelDekId("model-dek-secret"), ModelDekGeneration(1)),
        encryptionProfile = ProtectedModelEncryptionProfile.AES_256_GCM,
        signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
        signerId = ProtectedModelSignerId("signer-secret")
    )
}
