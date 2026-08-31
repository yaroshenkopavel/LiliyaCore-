package pro.liliya.core.protectedmodel

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtectedModelSlice1ContractTest {
    @Test
    fun exact_ownership_rejects_duplicate_and_stale_retire_cannot_remove_replacement() {
        val store = ProtectedModelOwnershipStore()
        val id = ProtectedModelPackageId("model-main")
        val first = assertIs<ProtectedModelRegistrationResult.Registered>(store.register(id)).ownership
        assertIs<ProtectedModelRegistrationResult.Rejected>(store.register(id))
        assertTrue(first.retire())

        val second = assertIs<ProtectedModelRegistrationResult.Registered>(store.register(id)).ownership
        assertNotEquals(first.reference, second.reference)
        assertFalse(first.retire())
        assertEquals(second.reference, store.inspect(id))
    }

    @Test
    fun retirement_removes_only_exact_live_generation() {
        val store = ProtectedModelOwnershipStore()
        val ownership = assertIs<ProtectedModelRegistrationResult.Registered>(
            store.register(ProtectedModelPackageId("model-a"))
        ).ownership
        assertTrue(ownership.retire())
        assertFalse(ownership.retire())
        assertNull(store.inspect(ownership.reference.packageId))
    }

    @Test
    fun protected_bytes_are_defensively_copied_and_render_redacted() {
        val model = ProtectedModelReference(ProtectedModelPackageId("secret-model"), ProtectedModelGeneration(1))
        val dek = ModelDekReference(ModelDekId("secret-dek"), ModelDekGeneration(1))
        val nonce = ByteArray(12) { 1 }
        val ciphertext = byteArrayOf(2, 3, 4)
        val tag = ByteArray(16) { 5 }
        val payload = ProtectedModelEncryptedPayload(
            model,
            dek,
            ProtectedModelEncryptionProfile.AES_256_GCM,
            nonce,
            ciphertext,
            tag
        )
        nonce.fill(9)
        ciphertext.fill(9)
        tag.fill(9)

        assertContentEquals(ByteArray(12) { 1 }, payload.copyNonce())
        assertContentEquals(byteArrayOf(2, 3, 4), payload.copyCiphertext())
        assertContentEquals(ByteArray(16) { 5 }, payload.copyAuthenticationTag())
        val rendered = payload.toString()
        assertFalse(rendered.contains("secret-model"))
        assertFalse(rendered.contains("secret-dek"))
        assertTrue(rendered.contains("<redacted:"))
    }

    @Test
    fun package_requires_exact_manifest_payload_and_signature_binding() {
        val model = ProtectedModelReference(ProtectedModelPackageId("model-a"), ProtectedModelGeneration(1))
        val dek = ModelDekReference(ModelDekId("dek-a"), ModelDekGeneration(1))
        val signer = ProtectedModelSignerId("signer-a")
        val manifest = ProtectedModelManifest(
            ProtectedModelPackageFormatVersion(1),
            model,
            "GGUF",
            1024,
            dek,
            ProtectedModelEncryptionProfile.AES_256_GCM,
            ProtectedModelSignatureAlgorithm.ED25519,
            signer
        )
        val payload = ProtectedModelEncryptedPayload(
            model,
            dek,
            ProtectedModelEncryptionProfile.AES_256_GCM,
            ByteArray(12),
            byteArrayOf(1),
            ByteArray(16)
        )
        val signature = ProtectedModelSignature(ProtectedModelSignatureAlgorithm.ED25519, signer, byteArrayOf(7))
        val pkg = ProtectedModelPackage(manifest, payload, signature)

        assertEquals(model, pkg.manifest.model)
        assertEquals(dek, pkg.payload.modelDek)
    }

    @Test
    fun snapshot_is_deterministic_by_generation_then_id() {
        val store = ProtectedModelOwnershipStore()
        store.register(ProtectedModelPackageId("b"))
        store.register(ProtectedModelPackageId("a"))
        assertEquals(listOf("b", "a"), store.snapshot().map { it.packageId.value })
    }
}
