package pro.liliya.core.encryption

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.persistence.PersistentStoreId

class CognitiveCryptoBoundaryContractTest {
    private val profile = CognitiveEncryptionProfile.AES_256_GCM
    private val version = CognitiveEnvelopeVersion(1)
    private val dekReference = CognitiveDekReference(CognitiveDekId("dek-a"), CognitiveDekGeneration(7))

    @Test
    fun canonical_associated_data_changes_when_exact_binding_changes() {
        val base = binding(entity = "entity-a", generation = 3, schemaVersion = 2, dekGeneration = 7)
        val baseBytes = CognitiveAssociatedDataEncoder.encode(version, profile, base).copyBytes()

        val variants = listOf(
            binding(entity = "entity-b", generation = 3, schemaVersion = 2, dekGeneration = 7),
            binding(entity = "entity-a", generation = 4, schemaVersion = 2, dekGeneration = 7),
            binding(entity = "entity-a", generation = 3, schemaVersion = 3, dekGeneration = 7),
            binding(entity = "entity-a", generation = 3, schemaVersion = 2, dekGeneration = 8)
        )

        variants.forEach { variant ->
            val bytes = CognitiveAssociatedDataEncoder.encode(version, profile, variant).copyBytes()
            assertFalse(baseBytes.contentEquals(bytes))
        }
    }

    @Test
    fun secret_and_boundary_byte_wrappers_are_defensively_detached_and_redacted() {
        val keyInput = ByteArray(32) { it.toByte() }
        val plaintextInput = "private-memory-payload".encodeToByteArray()
        val nonceInput = ByteArray(12) { (it + 1).toByte() }

        val key = CognitiveDekMaterial(keyInput)
        val plaintext = CognitivePlaintext(plaintextInput)
        val nonce = CognitiveNonce(profile, nonceInput)

        keyInput.fill(99)
        plaintextInput.fill(88)
        nonceInput.fill(77)

        assertEquals(0, key.copyBytes()[0].toInt())
        assertEquals('p'.code.toByte(), plaintext.copyBytes()[0])
        assertEquals(1, nonce.copyBytes()[0].toInt())

        val keyCopy = key.copyBytes().also { it.fill(55) }
        val plainCopy = plaintext.copyBytes().also { it.fill(44) }
        val nonceCopy = nonce.copyBytes().also { it.fill(33) }
        assertNotEquals(55, key.copyBytes()[0].toInt())
        assertNotEquals(44, plaintext.copyBytes()[0].toInt())
        assertNotEquals(33, nonce.copyBytes()[0].toInt())

        val rendered = "$key $plaintext $nonce"
        assertFalse(rendered.contains("private-memory-payload"))
        assertTrue(rendered.contains("redacted"))
    }

    @Test
    fun deterministic_provider_authenticates_binding_and_rejects_tamper() {
        val provider = DeterministicContractAeadProvider()
        val key = CognitiveDekMaterial(ByteArray(32) { (it * 3 + 1).toByte() })
        val nonce = CognitiveNonce(profile, ByteArray(12) { (it + 9).toByte() })
        val aad = CognitiveAssociatedDataEncoder.encode(version, profile, binding())
        val plaintext = CognitivePlaintext("sensitive-cognitive-state".encodeToByteArray())

        val sealed = assertIs<CognitiveEncryptionResult.Success<CognitiveAeadSealedData>>(
            provider.seal(profile, key, nonce, aad, plaintext)
        ).value
        val opened = assertIs<CognitiveEncryptionResult.Success<CognitivePlaintext>>(
            provider.open(profile, key, nonce, aad, sealed)
        ).value

        assertContentEquals(plaintext.copyBytes(), opened.copyBytes())

        val tamperedCiphertext = sealed.copyCiphertext().also {
            if (it.isNotEmpty()) it[0] = (it[0].toInt() xor 0x01).toByte()
        }
        val tampered = CognitiveAeadSealedData(tamperedCiphertext, sealed.copyAuthenticationTag())
        val tamperResult = provider.open(profile, key, nonce, aad, tampered)
        assertEquals(
            CognitiveEncryptionFailureCategory.CIPHERTEXT_AUTHENTICATION_FAILED,
            assertIs<CognitiveEncryptionResult.Rejected>(tamperResult).category
        )

        val wrongAad = CognitiveAssociatedDataEncoder.encode(
            version,
            profile,
            binding(entity = "other-entity")
        )
        val substitutionResult = provider.open(profile, key, nonce, wrongAad, sealed)
        assertEquals(
            CognitiveEncryptionFailureCategory.CIPHERTEXT_AUTHENTICATION_FAILED,
            assertIs<CognitiveEncryptionResult.Rejected>(substitutionResult).category
        )
    }

    @Test
    fun deterministic_nonce_source_is_explicit_and_has_no_hidden_global_state() {
        val sourceA = DeterministicNonceSource(5)
        val sourceB = DeterministicNonceSource(5)

        val firstA = assertIs<CognitiveEncryptionResult.Success<CognitiveNonce>>(sourceA.next(profile)).value
        val firstB = assertIs<CognitiveEncryptionResult.Success<CognitiveNonce>>(sourceB.next(profile)).value
        assertContentEquals(firstA.copyBytes(), firstB.copyBytes())

        val secondA = assertIs<CognitiveEncryptionResult.Success<CognitiveNonce>>(sourceA.next(profile)).value
        assertFalse(firstA.copyBytes().contentEquals(secondA.copyBytes()))
    }

    private fun binding(
        entity: String = "entity-a",
        generation: Long = 3,
        schemaVersion: Int = 2,
        dekGeneration: Long = 7
    ): CognitivePayloadBinding = CognitivePayloadBinding(
        storeId = PersistentStoreId("memory-store"),
        entityId = PersistentEntityId(entity),
        entityGeneration = PersistentGeneration(generation),
        schemaId = PersistentSchemaId("memory-record"),
        schemaVersion = PersistentSchemaVersion(schemaVersion),
        dek = CognitiveDekReference(CognitiveDekId("dek-a"), CognitiveDekGeneration(dekGeneration))
    )

    private class DeterministicNonceSource(seed: Int) : CognitiveNonceSource {
        private var next = seed

        override fun next(profile: CognitiveEncryptionProfile): CognitiveEncryptionResult<CognitiveNonce> {
            val value = next++
            val bytes = ByteArray(profile.nonceSizeBytes) { index -> (value + index).toByte() }
            return CognitiveEncryptionResult.Success(CognitiveNonce(profile, bytes))
        }
    }

    /** Test-only deterministic authenticated transform; never production cryptography. */
    private class DeterministicContractAeadProvider : CognitiveAeadProvider {
        override fun seal(
            profile: CognitiveEncryptionProfile,
            dek: CognitiveDekMaterial,
            nonce: CognitiveNonce,
            associatedData: CognitiveAssociatedData,
            plaintext: CognitivePlaintext
        ): CognitiveEncryptionResult<CognitiveAeadSealedData> {
            val key = dek.copyBytes()
            val nonceBytes = nonce.copyBytes()
            val plain = plaintext.copyBytes()
            val ciphertext = ByteArray(plain.size) { index ->
                (plain[index].toInt() xor key[index % key.size].toInt() xor nonceBytes[index % nonceBytes.size].toInt()).toByte()
            }
            val tag = tag(key, nonceBytes, associatedData.copyBytes(), ciphertext)
            return CognitiveEncryptionResult.Success(CognitiveAeadSealedData(ciphertext, tag))
        }

        override fun open(
            profile: CognitiveEncryptionProfile,
            dek: CognitiveDekMaterial,
            nonce: CognitiveNonce,
            associatedData: CognitiveAssociatedData,
            sealed: CognitiveAeadSealedData
        ): CognitiveEncryptionResult<CognitivePlaintext> {
            val key = dek.copyBytes()
            val nonceBytes = nonce.copyBytes()
            val ciphertext = sealed.copyCiphertext()
            val expected = tag(key, nonceBytes, associatedData.copyBytes(), ciphertext)
            if (!MessageDigest.isEqual(expected, sealed.copyAuthenticationTag())) {
                return CognitiveEncryptionResult.Rejected(
                    CognitiveEncryptionFailureCategory.CIPHERTEXT_AUTHENTICATION_FAILED
                )
            }
            val plaintext = ByteArray(ciphertext.size) { index ->
                (ciphertext[index].toInt() xor key[index % key.size].toInt() xor nonceBytes[index % nonceBytes.size].toInt()).toByte()
            }
            return CognitiveEncryptionResult.Success(CognitivePlaintext(plaintext))
        }

        private fun tag(
            key: ByteArray,
            nonce: ByteArray,
            associatedData: ByteArray,
            ciphertext: ByteArray
        ): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(key)
            digest.update(nonce)
            digest.update(associatedData)
            digest.update(ciphertext)
            return digest.digest().copyOf(16)
        }
    }
}
