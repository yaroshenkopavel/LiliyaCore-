package pro.liliya.core.episodic

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

class EncryptedPersistentEpisodicIndexStoreContractTest {
    private val profile = CognitiveEncryptionProfile.AES_256_GCM
    private val dekRef = CognitiveDekReference(CognitiveDekId("episodic-index-dek"), CognitiveDekGeneration(1))
    private val material = CognitiveDekMaterial(ByteArray(32) { (it * 5 + 7).toByte() })

    @Test
    fun projection_is_idempotent_and_manifest_completeness_is_exact_checkpoint_bound() {
        val store = openIndexStore(IndexedBackend(), PersistentStoreId("episode-index"))
        val snapshot = episodeSnapshot("episode-1", generation = 7L)

        val first = assertIs<EpisodeIndexProjectionResult.Complete>(store.project(snapshot))
        assertEquals(5, first.projected)
        assertEquals(5, first.indexed)
        assertEquals(0, first.alreadyIndexed)

        val replay = assertIs<EpisodeIndexProjectionResult.Complete>(store.project(snapshot))
        assertEquals(5, replay.projected)
        assertEquals(0, replay.indexed)
        assertEquals(5, replay.alreadyIndexed)

        val checkpoint = EpisodeIndexSourceCheckpoint(revision = 12L, highWatermark = 7L, entryCount = 1L)
        assertEquals(EpisodeIndexCompleteness.UNKNOWN, store.completeness(checkpoint))

        val manifest = EpisodeIndexManifest(checkpoint, Instant.parse("2026-09-19T12:30:00Z"))
        assertIs<EpisodeIndexManifestResult.Loaded>(store.writeManifest(manifest))
        assertEquals(EpisodeIndexCompleteness.COMPLETE, store.completeness(checkpoint))
        assertEquals(
            EpisodeIndexCompleteness.INCOMPLETE,
            store.completeness(checkpoint.copy(revision = checkpoint.revision + 1L))
        )
        assertEquals(
            EpisodeIndexCompleteness.INCOMPLETE,
            store.completeness(checkpoint.copy(entryCount = checkpoint.entryCount + 1L))
        )
    }

    @Test
    fun temporal_page_uses_indexed_time_for_global_axis_order() {
        val store = openIndexStore(IndexedBackend(), PersistentStoreId("episode-index-order"))
        val lateDerivedEarlyObserved = EpisodeTemporalIndexEntry(
            axis = EpisodeTemporalAxis.OBSERVED,
            indexedAt = Instant.parse("2026-09-19T09:00:00Z"),
            episodeId = EpisodeId("episode-late-derived"),
            episodeGeneration = 2L
        )
        val earlyDerivedLateObserved = EpisodeTemporalIndexEntry(
            axis = EpisodeTemporalAxis.OBSERVED,
            indexedAt = Instant.parse("2026-09-19T11:00:00Z"),
            episodeId = EpisodeId("episode-early-derived"),
            episodeGeneration = 1L
        )
        assertIs<EpisodeIndexWriteResult.Indexed>(store.write(earlyDerivedLateObserved))
        assertIs<EpisodeIndexWriteResult.Indexed>(store.write(lateDerivedEarlyObserved))

        val page = assertIs<EpisodeIndexPageResult.Loaded>(
            store.temporalPage(
                axis = EpisodeTemporalAxis.OBSERVED,
                limit = 10,
                order = PersistentBackendPageOrder.OLDEST_FIRST
            )
        )
        assertEquals(
            listOf("episode-late-derived", "episode-early-derived"),
            page.entries.map { it.episodeId.value }
        )
    }

    private fun episodeSnapshot(id: String, generation: Long): EpisodeSnapshot = EpisodeSnapshot(
        record = EpisodeRecord(
            id = EpisodeId(id),
            evidence = listOf(
                RawEvidenceReference(RawEvidenceNamespace("action-log"), RawEvidenceId("action-1")),
                RawEvidenceReference(RawEvidenceNamespace("conversation-v3"), RawEvidenceId("chunk-1"))
            ),
            description = "canonical episode payload stays outside index",
            observedAt = Instant.parse("2026-09-19T10:00:00Z"),
            eventAt = Instant.parse("2026-09-19T09:59:00Z"),
            derivedAt = Instant.parse("2026-09-19T10:01:00Z")
        ),
        generation = generation
    )

    private fun openIndexStore(
        backend: IndexedBackend,
        storeId: PersistentStoreId
    ): EncryptedPersistentEpisodicIndexStore {
        val persistent = assertIs<PersistentStoreOpenResult.Opened>(
            PersistentRecordStore.open(foundation(), storeId, backend)
        ).store
        val encrypted = EncryptedPersistentRecordStore(
            store = persistent,
            profile = profile,
            envelopeVersion = CognitiveEnvelopeVersion(1),
            nonceSource = DeterministicNonceSource(),
            aead = DeterministicAeadProvider(),
            dekResolver = resolver()
        )
        return assertIs<EpisodicIndexOpenResult.Opened>(
            EncryptedPersistentEpisodicIndexStore.open(encrypted, dekRef)
        ).store
    }

    private fun resolver() = object : CognitiveDekMaterialResolver {
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
            correlationIds = CorrelationIdGenerator { "episodic-index-${sequence.incrementAndGet()}" }
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

        override fun loadMetadata(storeId: PersistentStoreId): PersistentBackendMetadataLoadResult =
            if (revision == 0L && entries.isEmpty()) PersistentBackendMetadataLoadResult.Missing
            else PersistentBackendMetadataLoadResult.Loaded(metadata())

        override fun loadEntry(
            storeId: PersistentStoreId,
            entityId: PersistentEntityId
        ): PersistentBackendEntryLoadResult = entries[entityId]?.let {
            PersistentBackendEntryLoadResult.Loaded(PersistentRecordSnapshot(it.record, it.generation))
        } ?: PersistentBackendEntryLoadResult.Missing

        override fun loadPage(
            storeId: PersistentStoreId,
            request: PersistentBackendPageRequest
        ): PersistentBackendPageLoadResult {
            val ordered = entries.values
                .filter { request.schemaId == null || it.record.schemaId == request.schemaId }
                .map { PersistentRecordSnapshot(it.record, it.generation) }
                .sortedWith(compareBy({ it.record.createdAt }, { it.record.id.value }))
                .let { if (request.order == PersistentBackendPageOrder.OLDEST_FIRST) it else it.asReversed() }
            val filtered = ordered.filter { snapshot ->
                val cursor = request.cursorExclusive ?: return@filter true
                when (request.order) {
                    PersistentBackendPageOrder.OLDEST_FIRST ->
                        snapshot.record.createdAt > cursor.createdAt ||
                            (snapshot.record.createdAt == cursor.createdAt && snapshot.record.id.value > cursor.entityId.value)
                    PersistentBackendPageOrder.NEWEST_FIRST ->
                        snapshot.record.createdAt < cursor.createdAt ||
                            (snapshot.record.createdAt == cursor.createdAt && snapshot.record.id.value < cursor.entityId.value)
                }
            }
            val page = filtered.take(request.limit)
            val next = if (filtered.size > request.limit && page.isNotEmpty()) {
                val last = page.last().record
                PersistentBackendPageCursor(last.createdAt, last.id)
            } else null
            return if (entries.isEmpty()) PersistentBackendPageLoadResult.Missing
            else PersistentBackendPageLoadResult.Loaded(PersistentBackendPage(page, next))
        }

        override fun installEntry(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            expectedHighWatermark: Long,
            entry: PersistentBackendEntry
        ): PersistentBackendMutationResult {
            if (revision != expectedRevision || highWatermark != expectedHighWatermark) {
                return PersistentBackendMutationResult.Conflict
            }
            if (entries.containsKey(entry.record.id) || entry.generation.value != highWatermark + 1L) {
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
        ): PersistentBackendMutationResult {
            if (revision != expectedRevision || highWatermark != expectedHighWatermark) {
                return PersistentBackendMutationResult.Conflict
            }
            val current = entries[sourceId]
                ?: return PersistentBackendMutationResult.Rejected("missing source")
            if (current.generation != sourceGeneration || replacement.generation != sourceGeneration) {
                return PersistentBackendMutationResult.Rejected("stale generation")
            }
            entries.remove(sourceId)
            entries[replacement.record.id] = replacement
            revision += 1L
            return PersistentBackendMutationResult.Committed(metadata())
        }

        override fun removeEntry(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            expectedHighWatermark: Long,
            id: PersistentEntityId,
            generation: PersistentGeneration
        ): PersistentBackendMutationResult = PersistentBackendMutationResult.Rejected("not used")

        private fun metadata() = PersistentBackendMetadata(
            revision = revision,
            highWatermark = highWatermark,
            entryCount = entries.size.toLong()
        )
    }

    private class DeterministicNonceSource : CognitiveNonceSource {
        private var next = 1
        override fun next(profile: CognitiveEncryptionProfile): CognitiveEncryptionResult<CognitiveNonce> =
            CognitiveEncryptionResult.Success(
                CognitiveNonce(profile, ByteArray(profile.nonceSizeBytes) { (next + it).toByte() }).also { next += 1 }
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
            val key = dek.copyBytes(); val n = nonce.copyBytes(); val plain = plaintext.copyBytes()
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
            val key = dek.copyBytes(); val n = nonce.copyBytes(); val cipher = sealed.copyCiphertext()
            val expected = tag(key, n, associatedData.copyBytes(), cipher)
            if (!MessageDigest.isEqual(expected, sealed.copyAuthenticationTag())) {
                return CognitiveEncryptionResult.Rejected(
                    CognitiveEncryptionFailureCategory.CIPHERTEXT_AUTHENTICATION_FAILED
                )
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
