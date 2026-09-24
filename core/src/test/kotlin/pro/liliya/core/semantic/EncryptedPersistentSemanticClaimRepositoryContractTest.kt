package pro.liliya.core.semantic

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.IndexedPersistentRecordMutationBackend
import pro.liliya.core.persistence.PersistentBackendCommitResult
import pro.liliya.core.persistence.PersistentBackendEntry
import pro.liliya.core.persistence.PersistentBackendEntryLoadResult
import pro.liliya.core.persistence.PersistentBackendLoadResult
import pro.liliya.core.persistence.PersistentBackendMetadata
import pro.liliya.core.persistence.PersistentBackendMetadataLoadResult
import pro.liliya.core.persistence.PersistentBackendMutationResult
import pro.liliya.core.persistence.PersistentBackendPage
import pro.liliya.core.persistence.PersistentBackendPageCursor
import pro.liliya.core.persistence.PersistentBackendPageLoadResult
import pro.liliya.core.persistence.PersistentBackendPageOrder
import pro.liliya.core.persistence.PersistentBackendPageRequest
import pro.liliya.core.persistence.PersistentBackendState
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentRecordSnapshot
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

class EncryptedPersistentSemanticClaimRepositoryContractTest {
    private val profile = CognitiveEncryptionProfile.AES_256_GCM
    private val dekRef = CognitiveDekReference(
        CognitiveDekId("semantic-claims-dek"),
        CognitiveDekGeneration(1)
    )
    private val material = CognitiveDekMaterial(ByteArray(32) { (it * 5 + 17).toByte() })

    @Test
    fun claim_versions_are_append_only_and_strictly_monotonic() {
        val repository = openRepository(IndexedBackend(), "semantic-versions")
        val v1 = claim("Russian", 1, "preferred_language", "episode-r1")
        val v2 = claim("Russian", 2, "preferred_language", "episode-r2")
        val v3 = claim("Russian", 3, "preferred_language", "episode-r3")

        assertIs<SemanticClaimStoreResult.Stored>(repository.storeClaim(v1))
        assertIs<SemanticClaimStoreResult.AlreadyPresent>(repository.storeClaim(v1))

        val skipped = assertIs<SemanticClaimStoreResult.Rejected>(
            repository.storeClaim(v3)
        )
        assertEquals(
            "semantic claim version must be exactly next monotonic version: expected 2",
            skipped.reason
        )

        assertIs<SemanticClaimStoreResult.Stored>(repository.storeClaim(v2))
        assertIs<SemanticClaimStoreResult.Stored>(repository.storeClaim(v3))
    }

    @Test
    fun same_version_with_different_provenance_is_rejected_not_overwritten() {
        val repository = openRepository(IndexedBackend(), "semantic-divergent-replay")
        val original = claim("Russian", 1, "preferred_language", "episode-original")
        val divergent = original.copy(
            provenance = original.provenance.copy(
                episodes = listOf(pro.liliya.core.episodic.EpisodeId("episode-divergent"))
            )
        )

        assertIs<SemanticClaimStoreResult.Stored>(repository.storeClaim(original))
        val rejected = assertIs<SemanticClaimStoreResult.Rejected>(
            repository.storeClaim(divergent)
        )
        assertEquals(
            "semantic claim version already exists with different content",
            rejected.reason
        )
    }

    @Test
    fun supersession_can_link_different_object_claims_inside_same_conflict_group() {
        val repository = openRepository(IndexedBackend(), "semantic-supersession")
        val old = claim("Russian", 1, "preferred_language", "episode-old")
        val current = claim("Ukrainian", 1, "preferred_language", "episode-new")
        assertIs<SemanticClaimStoreResult.Stored>(repository.storeClaim(old))
        assertIs<SemanticClaimStoreResult.Stored>(repository.storeClaim(current))

        val relation = SemanticClaimRelation(
            type = SemanticClaimRelationType.SUPERSEDES,
            source = SemanticClaimVersionReference(current.id, current.version),
            target = SemanticClaimVersionReference(old.id, old.version),
            recordedAt = Instant.parse("2026-09-24T21:00:00Z")
        )

        assertIs<SemanticRelationStoreResult.Stored>(
            repository.storeRelation(relation)
        )
        assertIs<SemanticRelationStoreResult.AlreadyPresent>(
            repository.storeRelation(relation)
        )
    }

    @Test
    fun contradiction_across_different_predicates_is_rejected() {
        val repository = openRepository(IndexedBackend(), "semantic-cross-group")
        val language = claim("Russian", 1, "preferred_language", "episode-language")
        val timezone = claim("Europe/Kyiv", 1, "preferred_timezone", "episode-timezone")
        assertIs<SemanticClaimStoreResult.Stored>(repository.storeClaim(language))
        assertIs<SemanticClaimStoreResult.Stored>(repository.storeClaim(timezone))

        val relation = SemanticClaimRelation(
            type = SemanticClaimRelationType.CONTRADICTS,
            source = SemanticClaimVersionReference(language.id, language.version),
            target = SemanticClaimVersionReference(timezone.id, timezone.version),
            recordedAt = Instant.parse("2026-09-24T21:10:00Z")
        )

        val rejected = assertIs<SemanticRelationStoreResult.Rejected>(
            repository.storeRelation(relation)
        )
        assertEquals(
            "semantic claim relation must remain inside one conflict group",
            rejected.reason
        )
    }

    @Test
    fun same_claim_supersession_must_point_from_newer_version_to_older_version() {
        val repository = openRepository(IndexedBackend(), "semantic-version-relation")
        val v1 = claim("Russian", 1, "preferred_language", "episode-v1")
        val v2 = claim("Russian", 2, "preferred_language", "episode-v2")
        assertIs<SemanticClaimStoreResult.Stored>(repository.storeClaim(v1))
        assertIs<SemanticClaimStoreResult.Stored>(repository.storeClaim(v2))

        val invalid = SemanticClaimRelation(
            type = SemanticClaimRelationType.SUPERSEDES,
            source = SemanticClaimVersionReference(v1.id, v1.version),
            target = SemanticClaimVersionReference(v2.id, v2.version),
            recordedAt = Instant.parse("2026-09-24T21:20:00Z")
        )

        val rejected = assertIs<SemanticRelationStoreResult.Rejected>(
            repository.storeRelation(invalid)
        )
        assertEquals(
            "same-claim supersession must point from newer version to older version",
            rejected.reason
        )
    }

    private fun claim(
        value: String,
        version: Long,
        predicate: String,
        episodeId: String
    ): SemanticClaimRecord {
        val identity = SemanticClaimIdentity(
            subject = SemanticEntityReference("person", "user"),
            predicate = predicate
        )
        val objectValue = SemanticClaimObject.Text(value)
        val observed = Instant.parse("2026-09-24T20:00:00Z").plusSeconds(version)
        return SemanticClaimRecord(
            id = SemanticClaimIds.forClaim(identity, objectValue),
            version = SemanticClaimVersion(version),
            identity = identity,
            objectValue = objectValue,
            temporal = SemanticClaimTemporalState(
                observedAt = observed,
                validFrom = Instant.parse("2026-09-01T00:00:00Z")
            ),
            provenance = SemanticClaimProvenance(
                episodes = listOf(pro.liliya.core.episodic.EpisodeId(episodeId)),
                extraction = SemanticClaimExtractionProvenance(
                    extractorId = "semantic-v0.3-test",
                    extractorVersion = "1.0.0",
                    extractedAt = observed.plusSeconds(1)
                )
            )
        )
    }

    private fun openRepository(
        backend: IndexedBackend,
        storeId: String
    ): EncryptedPersistentSemanticClaimRepository {
        val persistent = assertIs<PersistentStoreOpenResult.Opened>(
            PersistentRecordStore.open(
                foundation(),
                PersistentStoreId(storeId),
                backend
            )
        ).store
        val encrypted = EncryptedPersistentRecordStore(
            store = persistent,
            profile = profile,
            envelopeVersion = CognitiveEnvelopeVersion(1),
            nonceSource = DeterministicNonceSource(),
            aead = DeterministicAeadProvider(),
            dekResolver = resolver()
        )
        return EncryptedPersistentSemanticClaimRepository(encrypted, dekRef)
    }

    private fun resolver() = object : CognitiveDekMaterialResolver {
        override fun resolve(
            reference: CognitiveDekReference
        ): CognitiveEncryptionResult<CognitiveDekMaterial> =
            if (reference == dekRef) {
                CognitiveEncryptionResult.Success(material)
            } else {
                CognitiveEncryptionResult.Rejected(
                    CognitiveEncryptionFailureCategory.DEK_MISSING
                )
            }
    }

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger()
        val writer = InMemoryLogWriter()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, writer) },
            correlationIds = CorrelationIdGenerator {
                "semantic-repository-${sequence.incrementAndGet()}"
            }
        )
    }

    private class IndexedBackend : IndexedPersistentRecordMutationBackend {
        private val entries = LinkedHashMap<PersistentEntityId, PersistentBackendEntry>()
        private var revision = 0L
        private var highWatermark = 0L

        override fun load(storeId: PersistentStoreId): PersistentBackendLoadResult =
            PersistentBackendLoadResult.Failed("legacy load must not be used")

        override fun commit(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            state: PersistentBackendState
        ): PersistentBackendCommitResult =
            PersistentBackendCommitResult.Failed("legacy commit must not be used")

        override fun loadMetadata(
            storeId: PersistentStoreId
        ): PersistentBackendMetadataLoadResult =
            if (revision == 0L && entries.isEmpty()) {
                PersistentBackendMetadataLoadResult.Missing
            } else {
                PersistentBackendMetadataLoadResult.Loaded(metadata())
            }

        override fun loadEntry(
            storeId: PersistentStoreId,
            entityId: PersistentEntityId
        ): PersistentBackendEntryLoadResult =
            entries[entityId]?.let {
                PersistentBackendEntryLoadResult.Loaded(
                    PersistentRecordSnapshot(it.record, it.generation)
                )
            } ?: PersistentBackendEntryLoadResult.Missing

        override fun loadPage(
            storeId: PersistentStoreId,
            request: PersistentBackendPageRequest
        ): PersistentBackendPageLoadResult {
            val ordered = entries.values
                .filter {
                    request.schemaId == null ||
                        it.record.schemaId == request.schemaId
                }
                .map {
                    PersistentRecordSnapshot(it.record, it.generation)
                }
                .sortedWith(
                    compareBy(
                        { it.record.createdAt },
                        { it.record.id.value }
                    )
                )
                .let {
                    if (request.order == PersistentBackendPageOrder.OLDEST_FIRST) {
                        it
                    } else {
                        it.asReversed()
                    }
                }

            val filtered = ordered.filter { snapshot ->
                val cursor = request.cursorExclusive ?: return@filter true
                when (request.order) {
                    PersistentBackendPageOrder.OLDEST_FIRST ->
                        snapshot.record.createdAt > cursor.createdAt ||
                            (
                                snapshot.record.createdAt == cursor.createdAt &&
                                    snapshot.record.id.value > cursor.entityId.value
                                )

                    PersistentBackendPageOrder.NEWEST_FIRST ->
                        snapshot.record.createdAt < cursor.createdAt ||
                            (
                                snapshot.record.createdAt == cursor.createdAt &&
                                    snapshot.record.id.value < cursor.entityId.value
                                )
                }
            }

            val page = filtered.take(request.limit)
            val next = if (filtered.size > request.limit && page.isNotEmpty()) {
                val last = page.last().record
                PersistentBackendPageCursor(last.createdAt, last.id)
            } else {
                null
            }

            return if (entries.isEmpty()) {
                PersistentBackendPageLoadResult.Missing
            } else {
                PersistentBackendPageLoadResult.Loaded(
                    PersistentBackendPage(page, next)
                )
            }
        }

        override fun installEntry(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            expectedHighWatermark: Long,
            entry: PersistentBackendEntry
        ): PersistentBackendMutationResult {
            if (revision != expectedRevision ||
                highWatermark != expectedHighWatermark
            ) {
                return PersistentBackendMutationResult.Conflict
            }
            if (entries.containsKey(entry.record.id) ||
                entry.generation.value != highWatermark + 1L
            ) {
                return PersistentBackendMutationResult.Rejected("invalid install")
            }
            entries[entry.record.id] = entry
            revision += 1L
            highWatermark = entry.generation.value
            return PersistentBackendMutationResult.Committed(metadata())
        }

        override fun transitionEntry(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            expectedHighWatermark: Long,
            sourceId: PersistentEntityId,
            sourceGeneration: PersistentGeneration,
            replacement: PersistentBackendEntry
        ): PersistentBackendMutationResult =
            PersistentBackendMutationResult.Rejected("not used")

        override fun removeEntry(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            expectedHighWatermark: Long,
            id: PersistentEntityId,
            generation: PersistentGeneration
        ): PersistentBackendMutationResult =
            PersistentBackendMutationResult.Rejected("not used")

        private fun metadata() = PersistentBackendMetadata(
            revision = revision,
            highWatermark = highWatermark,
            entryCount = entries.size.toLong()
        )
    }

    private class DeterministicNonceSource : CognitiveNonceSource {
        private var next = 1

        override fun next(
            profile: CognitiveEncryptionProfile
        ): CognitiveEncryptionResult<CognitiveNonce> =
            CognitiveEncryptionResult.Success(
                CognitiveNonce(
                    profile,
                    ByteArray(profile.nonceSizeBytes) {
                        (next + it).toByte()
                    }
                ).also { next += 1 }
            )
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
                (
                    plain[i].toInt() xor
                        key[i % key.size].toInt() xor
                        n[i % n.size].toInt()
                    ).toByte()
            }
            return CognitiveEncryptionResult.Success(
                CognitiveAeadSealedData(
                    cipher,
                    tag(key, n, associatedData.copyBytes(), cipher)
                )
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
            val expected = tag(
                key,
                n,
                associatedData.copyBytes(),
                cipher
            )
            if (!MessageDigest.isEqual(
                    expected,
                    sealed.copyAuthenticationTag()
                )
            ) {
                return CognitiveEncryptionResult.Rejected(
                    CognitiveEncryptionFailureCategory
                        .CIPHERTEXT_AUTHENTICATION_FAILED
                )
            }
            return CognitiveEncryptionResult.Success(
                CognitivePlaintext(
                    ByteArray(cipher.size) { i ->
                        (
                            cipher[i].toInt() xor
                                key[i % key.size].toInt() xor
                                n[i % n.size].toInt()
                            ).toByte()
                    }
                )
            )
        }

        private fun tag(
            key: ByteArray,
            nonce: ByteArray,
            aad: ByteArray,
            cipher: ByteArray
        ): ByteArray =
            MessageDigest.getInstance("SHA-256")
                .digest(key + nonce + aad + cipher)
                .copyOf(16)
    }
}
