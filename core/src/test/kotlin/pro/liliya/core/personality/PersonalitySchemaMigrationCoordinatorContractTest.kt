package pro.liliya.core.personality

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
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
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

class PersonalitySchemaMigrationCoordinatorContractTest {
    private val encryptionProfile = CognitiveEncryptionProfile.AES_256_GCM
    private val dekRef = CognitiveDekReference(CognitiveDekId("personality-migration-dek"), CognitiveDekGeneration(1))
    private val material = CognitiveDekMaterial(ByteArray(32) { (it * 11 + 5).toByte() })
    private val storeId = PersistentStoreId("personality-migration-domain")

    @Test
    fun migration_is_explicit_one_record_per_step_and_preserves_generation_high_watermark() {
        val backend = InMemoryPersistentRecordBackend()
        val firstStore = encryptedStore(backend, resolver(material))
        val firstOwnership = installLegacy(firstStore, profile("p1", "calm", "2026-09-17T17:00:00Z"))
        val secondOwnership = installLegacy(firstStore, profile("p2", "precise", "2026-09-17T17:01:00Z"))
        assertEquals(1L, firstOwnership.generation.value)
        assertEquals(2L, secondOwnership.generation.value)

        assertIs<EncryptedPersistentPersonalityOpenResult.Incompatible>(
            EncryptedPersistentPersonalityComposition.open(foundation(), encryptedStore(backend, resolver(material)), dekRef)
        )

        val firstStep = PersonalitySchemaMigrationCoordinator(
            encryptedStore(backend, resolver(material)),
            dekRef
        ).step()
        val migratedFirst = assertIs<PersonalitySchemaMigrationStepResult.MigratedOne>(firstStep)
        assertEquals(PersonalityProfileId("p1"), migratedFirst.profileId)
        assertEquals(1L, migratedFirst.generation.value)
        assertEquals(listOf(2, 1), schemaVersions(backend))

        assertIs<EncryptedPersistentPersonalityOpenResult.Incompatible>(
            EncryptedPersistentPersonalityComposition.open(foundation(), encryptedStore(backend, resolver(material)), dekRef)
        )

        // New coordinator instance models process restart between migration steps.
        val secondStep = PersonalitySchemaMigrationCoordinator(
            encryptedStore(backend, resolver(material)),
            dekRef
        ).step()
        val migratedSecond = assertIs<PersonalitySchemaMigrationStepResult.MigratedOne>(secondStep)
        assertEquals(PersonalityProfileId("p2"), migratedSecond.profileId)
        assertEquals(2L, migratedSecond.generation.value)
        assertEquals(listOf(2, 2), schemaVersions(backend))

        assertIs<PersonalitySchemaMigrationStepResult.UpToDate>(
            PersonalitySchemaMigrationCoordinator(encryptedStore(backend, resolver(material)), dekRef).step()
        )

        val reopened = assertIs<EncryptedPersistentPersonalityOpenResult.Opened>(
            EncryptedPersistentPersonalityComposition.open(foundation(), encryptedStore(backend, resolver(material)), dekRef)
        ).composition
        assertEquals(1L, reopened.inspect(PersonalityProfileId("p1"))?.generation?.value)
        assertEquals(2L, reopened.inspect(PersonalityProfileId("p2"))?.generation?.value)
        val next = assertIs<PersistentPersonalityInstallResult.Installed>(
            reopened.install(profile("p3", "steady", "2026-09-17T17:02:00Z"))
        )
        assertEquals(3L, next.ownership.generation.value)
    }

    @Test
    fun unknown_future_schema_blocks_entire_step_before_any_legacy_record_is_mutated() {
        val backend = InMemoryPersistentRecordBackend()
        val store = encryptedStore(backend, resolver(material))
        installLegacy(store, profile("legacy", "calm", "2026-09-17T17:00:00Z"))
        installRaw(
            store = store,
            id = PersistentEntityId("future-personality-record"),
            schemaId = PersonalityPersistentRecordCodec.schemaId,
            schemaVersion = PersistentSchemaVersion(3),
            bytes = byteArrayOf(7, 8, 9),
            createdAt = Instant.parse("2026-09-17T17:01:00Z")
        )
        val before = assertIs<PersistentBackendLoadResult.Loaded>(backend.load(storeId))

        assertIs<PersonalitySchemaMigrationStepResult.Incompatible>(
            PersonalitySchemaMigrationCoordinator(encryptedStore(backend, resolver(material)), dekRef).step()
        )

        val after = assertIs<PersistentBackendLoadResult.Loaded>(backend.load(storeId))
        assertEquals(before.revision, after.revision)
        assertEquals(before.state, after.state)
        assertEquals(listOf(1, 3), schemaVersions(backend))
    }

    @Test
    fun corrupt_legacy_record_blocks_entire_step_before_any_mutation() {
        val backend = InMemoryPersistentRecordBackend()
        val store = encryptedStore(backend, resolver(material))
        installLegacy(store, profile("legacy", "calm", "2026-09-17T17:00:00Z"))
        installRaw(
            store = store,
            id = PersistentEntityId("personality-corrupt-legacy"),
            schemaId = PersonalityPersistentRecordCodec.schemaId,
            schemaVersion = PersonalityPersistentRecordCodec.legacySchemaVersion,
            bytes = byteArrayOf(0, 1, 2, 3),
            createdAt = Instant.parse("2026-09-17T17:01:00Z")
        )
        val before = assertIs<PersistentBackendLoadResult.Loaded>(backend.load(storeId))

        assertIs<PersonalitySchemaMigrationStepResult.Corrupt>(
            PersonalitySchemaMigrationCoordinator(encryptedStore(backend, resolver(material)), dekRef).step()
        )

        val after = assertIs<PersistentBackendLoadResult.Loaded>(backend.load(storeId))
        assertEquals(before.revision, after.revision)
        assertEquals(before.state, after.state)
    }

    @Test
    fun missing_dek_blocks_migration_without_mutation() {
        val backend = InMemoryPersistentRecordBackend()
        installLegacy(encryptedStore(backend, resolver(material)), profile("legacy", "calm", "2026-09-17T17:00:00Z"))
        val before = assertIs<PersistentBackendLoadResult.Loaded>(backend.load(storeId))
        val missing = object : CognitiveDekMaterialResolver {
            override fun resolve(reference: CognitiveDekReference): CognitiveEncryptionResult<CognitiveDekMaterial> =
                CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.DEK_MISSING)
        }

        val result = PersonalitySchemaMigrationCoordinator(encryptedStore(backend, missing), dekRef).step()
        val unavailable = assertIs<PersonalitySchemaMigrationStepResult.EncryptionUnavailable>(result)
        assertEquals(CognitiveEncryptionFailureCategory.DEK_MISSING, unavailable.category)

        val after = assertIs<PersistentBackendLoadResult.Loaded>(backend.load(storeId))
        assertEquals(before.revision, after.revision)
        assertEquals(before.state, after.state)
    }

    private fun installLegacy(
        store: EncryptedPersistentRecordStore,
        profile: PersonalityProfile
    ) = assertIs<CognitiveEncryptionResult.Success<pro.liliya.core.persistence.PersistentRecordOwnership>>(
        PersonalityPersistentRecordCodec.encodeLegacyV1(profile).let { encoded ->
            val bytes = encoded.payload.copyBytes()
            try {
                store.install(
                    CognitivePersistentRecordDraft(
                        id = encoded.id,
                        schemaId = encoded.schemaId,
                        schemaVersion = encoded.schemaVersion,
                        plaintext = CognitivePlaintext(bytes),
                        createdAt = encoded.createdAt,
                        dek = dekRef
                    )
                )
            } finally {
                bytes.fill(0)
            }
        }
    ).value

    private fun installRaw(
        store: EncryptedPersistentRecordStore,
        id: PersistentEntityId,
        schemaId: PersistentSchemaId,
        schemaVersion: PersistentSchemaVersion,
        bytes: ByteArray,
        createdAt: Instant
    ) {
        assertIs<CognitiveEncryptionResult.Success<*>>(
            store.install(
                CognitivePersistentRecordDraft(
                    id = id,
                    schemaId = schemaId,
                    schemaVersion = schemaVersion,
                    plaintext = CognitivePlaintext(bytes),
                    createdAt = createdAt,
                    dek = dekRef
                )
            )
        )
    }

    private fun schemaVersions(backend: InMemoryPersistentRecordBackend): List<Int> =
        assertIs<PersistentBackendLoadResult.Loaded>(backend.load(storeId))
            .state.entries.values
            .sortedBy { it.record.createdAt }
            .map { it.record.schemaVersion.value }

    private fun profile(id: String, value: String, createdAt: String) = PersonalityProfile(
        id = PersonalityProfileId(id),
        target = PersonalityTarget.Self(SelfIdentityId("self-1"), SelfGeneration(1)),
        attributes = listOf(PersonalityAttribute(PersonalityAttributeKey("tone"), PersonalityAttributeValue(value))),
        provenance = PersonalityProvenance(PersonalitySourceId("declared"), PersonalitySourceReference("user-config")),
        createdAt = Instant.parse(createdAt)
    )

    private fun encryptedStore(
        backend: InMemoryPersistentRecordBackend,
        resolver: CognitiveDekMaterialResolver
    ): EncryptedPersistentRecordStore {
        val store = assertIs<PersistentStoreOpenResult.Opened>(
            PersistentRecordStore.open(foundation(), storeId, backend)
        ).store
        return EncryptedPersistentRecordStore(
            store = store,
            profile = encryptionProfile,
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
            correlationIds = CorrelationIdGenerator { "personality-migration-${sequence.incrementAndGet()}" }
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
}
