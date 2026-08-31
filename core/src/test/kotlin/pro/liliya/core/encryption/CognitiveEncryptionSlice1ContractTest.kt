package pro.liliya.core.encryption

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.persistence.PersistentStoreId

class CognitiveEncryptionSlice1ContractTest {
    @Test
    fun exact_dek_ownership_rejects_duplicate_and_stale_retire() {
        val store = CognitiveDekStore()
        val id = CognitiveDekId("private-dek-id")

        val first = assertIs<CognitiveDekRegistrationResult.Registered>(store.register(id)).ownership
        assertIs<CognitiveDekRegistrationResult.Rejected>(store.register(id))
        assertTrue(first.retire())

        val replacement = assertIs<CognitiveDekRegistrationResult.Registered>(store.register(id)).ownership
        assertNotEquals(first.reference.generation, replacement.reference.generation)
        assertFalse(first.retire())
        assertEquals(replacement.reference, store.inspect(id))
    }

    @Test
    fun generation_overflow_fails_closed() {
        val store = CognitiveDekStore(initialGeneration = Long.MAX_VALUE)

        val result = store.register(CognitiveDekId("overflow-dek"))

        assertIs<CognitiveDekRegistrationResult.Rejected>(result)
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun independent_stores_isolate_same_logical_id() {
        val id = CognitiveDekId("same-id")
        val left = assertIs<CognitiveDekRegistrationResult.Registered>(CognitiveDekStore().register(id))
        val right = assertIs<CognitiveDekRegistrationResult.Registered>(CognitiveDekStore().register(id))

        assertEquals(1L, left.ownership.reference.generation.value)
        assertEquals(1L, right.ownership.reference.generation.value)
        assertTrue(left.ownership.retire())
        assertEquals(id, right.ownership.reference.id)
    }

    @Test
    fun aes_gcm_profile_is_allowlisted_and_exact() {
        assertEquals(CognitiveEncryptionAlgorithm.AES_256_GCM, CognitiveEncryptionProfile.AES_256_GCM.algorithm)
        assertFailsWith<IllegalArgumentException> {
            CognitiveEncryptionProfile(
                algorithm = CognitiveEncryptionAlgorithm.AES_256_GCM,
                keySizeBits = 128,
                nonceSizeBytes = 12,
                authenticationTagSizeBits = 128
            )
        }
    }

    @Test
    fun encrypted_envelope_defensively_copies_bytes_and_redacts_rendering() {
        val nonce = ByteArray(12) { 7 }
        val ciphertext = byteArrayOf(11, 22, 33, 44)
        val tag = ByteArray(16) { 9 }
        val envelope = encryptedEnvelope(nonce, ciphertext, tag)

        nonce.fill(1)
        ciphertext.fill(2)
        tag.fill(3)

        assertContentEquals(ByteArray(12) { 7 }, envelope.copyNonce())
        assertContentEquals(byteArrayOf(11, 22, 33, 44), envelope.copyCiphertext())
        assertContentEquals(ByteArray(16) { 9 }, envelope.copyAuthenticationTag())

        val returned = envelope.copyCiphertext()
        returned.fill(99)
        assertContentEquals(byteArrayOf(11, 22, 33, 44), envelope.copyCiphertext())

        val rendered = envelope.toString()
        assertFalse(rendered.contains("private-store"))
        assertFalse(rendered.contains("private-entity"))
        assertFalse(rendered.contains("private-schema"))
        assertFalse(rendered.contains("private-dek"))
        assertTrue(rendered.contains("<redacted:4 bytes>"))
    }

    @Test
    fun wrapped_dek_envelope_defensively_copies_and_redacts_sensitive_bytes() {
        val wrapped = byteArrayOf(41, 42, 43, 44)
        val nonce = ByteArray(12) { 5 }
        val tag = ByteArray(16) { 6 }
        val envelope = WrappedCognitiveDekEnvelope(
            version = CognitiveEnvelopeVersion(1),
            dek = CognitiveDekReference(CognitiveDekId("private-dek"), CognitiveDekGeneration(4)),
            protector = CognitiveKeyProtectorReference(
                id = CognitiveKeyProtectorId("private-protector"),
                generation = CognitiveKeyProtectorGeneration(3),
                platformReference = CognitiveKeyProtectorPlatformReference("private-platform-ref")
            ),
            wrappingAlgorithm = CognitiveDekWrappingAlgorithm.AES_256_GCM,
            purpose = CognitiveKeyPurpose.COGNITIVE_STORAGE,
            wrappedDek = wrapped,
            nonce = nonce,
            authenticationTag = tag
        )

        wrapped.fill(0)
        nonce.fill(0)
        tag.fill(0)

        assertContentEquals(byteArrayOf(41, 42, 43, 44), envelope.copyWrappedDek())
        assertContentEquals(ByteArray(12) { 5 }, envelope.copyNonce())
        assertContentEquals(ByteArray(16) { 6 }, envelope.copyAuthenticationTag())

        val rendered = envelope.toString()
        assertFalse(rendered.contains("private-dek"))
        assertFalse(rendered.contains("private-protector"))
        assertFalse(rendered.contains("private-platform-ref"))
        assertTrue(rendered.contains("wrappedDek=<redacted:4 bytes>"))
    }

    @Test
    fun malformed_envelope_metadata_fails_closed() {
        assertFailsWith<IllegalArgumentException> {
            encryptedEnvelope(
                nonce = ByteArray(11),
                ciphertext = byteArrayOf(1),
                tag = ByteArray(16)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WrappedCognitiveDekEnvelope(
                version = CognitiveEnvelopeVersion(1),
                dek = CognitiveDekReference(CognitiveDekId("dek"), CognitiveDekGeneration(1)),
                protector = CognitiveKeyProtectorReference(
                    CognitiveKeyProtectorId("protector"),
                    CognitiveKeyProtectorGeneration(1)
                ),
                wrappingAlgorithm = CognitiveDekWrappingAlgorithm.AES_256_GCM,
                purpose = CognitiveKeyPurpose.COGNITIVE_STORAGE,
                wrappedDek = byteArrayOf(1),
                nonce = ByteArray(12),
                authenticationTag = ByteArray(15)
            )
        }
    }

    @Test
    fun failure_rendering_omits_exception_message() {
        val failure = CognitiveEncryptionResult.Failed(
            category = CognitiveEncryptionFailureCategory.PROVIDER_FAILED,
            throwable = IllegalStateException("private-cognitive-secret")
        )

        val rendered = failure.toString()
        assertTrue(rendered.contains("java.lang.IllegalStateException"))
        assertFalse(rendered.contains("private-cognitive-secret"))
    }

    private fun encryptedEnvelope(
        nonce: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray
    ): EncryptedCognitivePayloadEnvelope =
        EncryptedCognitivePayloadEnvelope(
            version = CognitiveEnvelopeVersion(1),
            profile = CognitiveEncryptionProfile.AES_256_GCM,
            binding = CognitivePayloadBinding(
                storeId = PersistentStoreId("private-store"),
                entityId = PersistentEntityId("private-entity"),
                entityGeneration = PersistentGeneration(8),
                schemaId = PersistentSchemaId("private-schema"),
                schemaVersion = PersistentSchemaVersion(2),
                dek = CognitiveDekReference(CognitiveDekId("private-dek"), CognitiveDekGeneration(5))
            ),
            nonce = nonce,
            ciphertext = ciphertext,
            authenticationTag = tag
        )
}
