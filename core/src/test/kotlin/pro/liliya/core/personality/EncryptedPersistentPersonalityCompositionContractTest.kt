package pro.liliya.core.personality

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.encryption.CognitiveAeadProvider
import pro.liliya.core.encryption.CognitiveAeadSealedData
import pro.liliya.core.encryption.CognitiveAssociatedData
import pro.liliya.core.encryption.CognitiveDekGeneration
import pro.liliya.core.encryption.CognitiveDekId
import pro.liliya.core.encryption.CognitiveDekMaterial
import pro.liliya.core.encryption.CognitiveDekMaterialResolver
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionProfile
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitiveEnvelopeVersion
import pro.liliya.core.encryption.CognitiveNonce
import pro.liliya.core.encryption.CognitiveNonceSource
import pro.liliya.core.encryption.CognitivePersistentRecordDraft
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.identity.SelfGeneration
import pro.liliya.core.identity.SelfIdentityId
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentBackendLoadResult
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

class EncryptedPersistentPersonalityCompositionContractTest {
    private val profile = CognitiveEncryptionProfile.AES_256_GCM
    private val dekRef = CognitiveDekReference(CognitiveDekId("personality-dek"), CognitiveDekGeneration(1))
    private val material = CognitiveDekMaterial(ByteArray(32) { (it * 7 + 3).toByte() })
    private val storeId = PersistentStoreId("encrypted-personality-domain")

    @Test
    fun encrypted_profile_reopens_with_exact_generation_and_next_generation_continues() {
        val backend = InMemoryPersistentRecordBackend()
        val first = openPersonality(backend)
        val installed = assertIs<PersistentPersonalityInstallResult.Installed>(first.install(profile("p1", "calm")))

        val reopened = openPersonality(backend)
        assertEquals(installed.ownership.profile, reopened.find(PersonalityProfileId("p1")))
        assertEquals(installed.ownership.generation, reopened.inspect(PersonalityProfileId("p1"))?.generation)

        val second = assertIs<PersistentPersonalityInstallResult.Installed>(reopened.install(profile("p2", "precise")))
        assertEquals(installed.ownership.generation.value + 1L, second.ownership.generation.value)
    }

    @Test
    fun attribute_values_and_profile_id_are_not_plaintext_in_durable_backend() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = openPersonality(backend)
        val secretValue = "private personality preference"
        val secretId = "private-profile-id"
        assertIs<PersistentPersonalityInstallResult.Installed>(
            composition.install(profile(secretId, secretValue))
        )

        val loaded = assertIs<PersistentBackendLoadResult.Loaded>(backend.load(storeId))
        val durable = loaded.state.entries.values.single().record
        val payload = durable.payload.copyBytes()
        assertFalse(containsSubsequence(payload, secretValue.encodeToByteArray()))
        assertFalse(containsSubsequence(payload, secretId.encodeToByteArray()))
        assertFalse(durable.id.value.contains(secretId))
    }

    @Test
    fun missing_dek_fails_reopen_closed_instead_of_restoring_empty_personality() {
        val backend = InMemoryPersistentRecordBackend()
        assertIs<PersistentPersonalityInstallResult.Installed>(openPersonality(backend).install(profile("p1", "stable")))

        val unavailable = EncryptedPersistentPersonalityComposition.open(
            foundation(),
            encryptedStore(
                backend,
                object : CognitiveDekMaterialResolver {
                    override fun resolve(reference: CognitiveDekReference): CognitiveEncryptionResult<CognitiveDekMaterial> =
                        CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.DEK_MISSING)
                }
            ),
            dekRef
        )
        assertIs<EncryptedPersistentPersonalityOpenResult.EncryptionUnavailable>(unavailable)
    }

    @Test
    fun authenticated_personality_tamper_fails_reopen_closed() {
        val backend = InMemoryPersistentRecordBackend()
        assertIs<PersistentPersonalityInstallResult.Installed>(
            openPersonality(backend).install(profile("tamper-profile", "must remain authenticated"))
        )

        val current = assertIs<PersistentBackendLoadResult.Loaded>(backend.load(storeId))
        val (id, entry) = current.state.entries.entries.single().let { it.key to it.value }
        val bytes = entry.record.payload.copyBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        val tampered = current.state.copy(
            entries = current.state.entries + (
                id to entry.copy(record = entry.record.copy(payload = PersistentPayload(bytes)))
            )
        )
        backend.forceLoad(storeId, PersistentBackendLoadResult.Loaded(current.revision, tampered))

        val reopened = EncryptedPersistentPersonalityComposition.open(
            foundation(),
            encryptedStore(backend, resolver(material)),
            dekRef
        )
        val rejected = assertIs<EncryptedPersistentPersonalityOpenResult.EncryptionUnavailable>(reopened)
        assertTrue(
            rejected.category == CognitiveEncryptionFailureCategory.CIPHERTEXT_AUTHENTICATION_FAILED ||
                rejected.category == CognitiveEncryptionFailureCategory.MALFORMED_ENVELOPE
        )
    }

    @Test
    fun unknown_schema_version_is_incompatible_and_not_silently_migrated() {
        val backend = InMemoryPersistentRecordBackend()
        val encrypted = encryptedStore(backend, resolver(material))
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertIs<CognitiveEncryptionResult.Success<*>>(
            encrypted.install(
                CognitivePersistentRecordDraft(
                    id = PersistentEntityId("personality-unknown-version"),
                    schemaId = PersistentSchemaId("personality-profile"),
                    schemaVersion = PersistentSchemaVersion(3),
                    plaintext = CognitivePlaintext(bytes),
                    createdAt = Instant.parse("2026-09-17T18:00:00Z"),
                    dek = dekRef
                )
            )
        )
        bytes.fill(0)

        val result = EncryptedPersistentPersonalityComposition.open(
            foundation(),
            encryptedStore(backend, resolver(material)),
            dekRef
        )
        assertIs<EncryptedPersistentPersonalityOpenResult.Incompatible>(result)
    }

    @Test
    fun restored_personality_is_descriptive_state_only_and_contains_no_authority_semantics() {
        val backend = InMemoryPersistentRecordBackend()
        val installed = assertIs<PersistentPersonalityInstallResult.Installed>(
            openPersonality(backend).install(profile("p1", "measured"))
        )
        val reopened = openPersonality(backend)
        assertEquals(installed.ownership.profile, reopened.find(PersonalityProfileId("p1")))
        val declaredApi = EncryptedPersistentPersonalityComposition::class.java.declaredMethods.map { it.name }.toSet()
        assertFalse(declaredApi.any { it.contains("authority", ignoreCase = true) })
        assertFalse(declaredApi.any { it.contains("capability", ignoreCase = true) })
        assertFalse(declaredApi.any { it.contains("execute", ignoreCase = true) })
        assertFalse(declaredApi.any { it.contains("learn", ignoreCase = true) })
        assertTrue(declaredApi.any { it.startsWith("find") })
    }

    private fun profile(id: String, value: String) = PersonalityProfile(
        id = PersonalityProfileId(id),
        target = PersonalityTarget.Self(SelfIdentityId("self-1"), SelfGeneration(1)),
        attributes = listOf(PersonalityAttribute(PersonalityAttributeKey("tone"), PersonalityAttributeValue(value))),
        provenance = PersonalityProvenance(PersonalitySourceId("declared"), PersonalitySourceReference("user-config")),
        createdAt = Instant.parse("2026-09-17T17:00:00Z")
    )

    private fun openPersonality(backend: InMemoryPersistentRecordBackend): EncryptedPersistentPersonalityComposition =
        assertIs<EncryptedPersistentPersonalityOpenResult.Opened>(
            EncryptedPersistentPersonalityComposition.open(
                foundation(),
                encryptedStore(backend, resolver(material)),
                dekRef
            )
        ).composition

    private fun encryptedStore(
        backend: InMemoryPersistentRecordBackend,
        resolver: CognitiveDekMaterialResolver
    ): EncryptedPersistentRecordStore {
        val store = assertIs<PersistentStoreOpenResult.Opened>(
            PersistentRecordStore.open(foundation(), storeId, backend)
        ).store
        return EncryptedPersistentRecordStore(
            store = store,
            profile = profile,
            envelopeVersion = CognitiveEnvelopeVersion(1),
            nonceSource = DeterministicNonceSource(),
            aead = DeterministicAeadProvider(),
            dekResolver = resolver
        )
    }

    private fun resolver(material: CognitiveDekMaterial) = object : CognitiveDekMaterialResolver {
        override fun resolve(reference: CognitiveDekReference): CognitiveEncryptionResult<CognitiveDekMaterial> =
            if (reference == dekRef) CognitiveEncryptionResult.Success(material)
            else CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.DEK_MISSING)
    }

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger()
        val writer = InMemoryLogWriter()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, writer) },
            correlationIds = CorrelationIdGenerator { "personality-${sequence.incrementAndGet()}" }
        )
    }

    private class DeterministicNonceSource : CognitiveNonceSource {
        private var next = 1
        override fun next(profile: CognitiveEncryptionProfile): CognitiveEncryptionResult<CognitiveNonce> =
            CognitiveEncryptionResult.Success(
                CognitiveNonce(profile, ByteArray(profile.nonceSizeBytes) { (next + it).toByte() })
            ).also { next++ }
    }

    private class DeterministicAeadProvider : CognitiveAeadProvider {
        override fun seal(
            profile: CognitiveEncryptionProfile,
            dek: CognitiveDekMaterial,
            nonce: CognitiveNonce,
            associatedData: CognitiveAssociatedData,
            plaintext: CognitivePlaintext
        ): CognitiveEncryptionResult<CognitiveAeadSealedData> {
            val key = dek.copyBytes()
            val n = nonce.copyBytes()
            val plain = plaintext.copyBytes()
            val cipher = ByteArray(plain.size) { i ->
                (plain[i].toInt() xor key[i % key.size].toInt() xor n[i % n.size].toInt()).toByte()
            }
            return CognitiveEncryptionResult.Success(
                CognitiveAeadSealedData(cipher, tag(key, n, associatedData.copyBytes(), cipher))
            )
        }

        override fun open(
            profile: CognitiveEncryptionProfile,
            dek: CognitiveDekMaterial,
            nonce: CognitiveNonce,
            associatedData: CognitiveAssociatedData,
            sealed: CognitiveAeadSealedData
        ): CognitiveEncryptionResult<CognitivePlaintext> {
            val key = dek.copyBytes()
            val n = nonce.copyBytes()
            val cipher = sealed.copyCiphertext()
            if (!MessageDigest.isEqual(tag(key, n, associatedData.copyBytes(), cipher), sealed.copyAuthenticationTag())) {
                return CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.CIPHERTEXT_AUTHENTICATION_FAILED)
            }
            return CognitiveEncryptionResult.Success(
                CognitivePlaintext(ByteArray(cipher.size) { i ->
                    (cipher[i].toInt() xor key[i % key.size].toInt() xor n[i % n.size].toInt()).toByte()
                })
            )
        }

        private fun tag(key: ByteArray, nonce: ByteArray, aad: ByteArray, cipher: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(key + nonce + aad + cipher).copyOf(16)
    }

    private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        if (needle.size > haystack.size) return false
        return (0..haystack.size - needle.size).any { start ->
            needle.indices.all { offset -> haystack[start + offset] == needle[offset] }
        }
    }
}
