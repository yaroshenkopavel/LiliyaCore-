package pro.liliya.core.persistence

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.EncryptedPersistentKnowledgeComposition
import pro.liliya.core.knowledge.EncryptedPersistentKnowledgeInspectResult
import pro.liliya.core.knowledge.EncryptedPersistentKnowledgeOpenResult
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgePersistentRecordCodec
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.knowledge.PersistentKnowledgeComposition
import pro.liliya.core.knowledge.PersistentKnowledgeOpenResult
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.EncryptedPersistentMemoryComposition
import pro.liliya.core.memory.EncryptedPersistentMemoryInspectResult
import pro.liliya.core.memory.EncryptedPersistentMemoryOpenResult
import pro.liliya.core.memory.MemoryPersistentRecordCodec
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.memory.PersistentMemoryComposition
import pro.liliya.core.memory.PersistentMemoryOpenResult
import pro.liliya.core.memory.PersistentMemoryRememberResult
import pro.liliya.core.knowledge.PersistentKnowledgeCreateResult
import pro.liliya.core.observability.LoggerProvider

class PersistentDomainLazyIndexedOpenContractTest {
    private class LazyDomainBackend : IndexedPersistentRecordMutationBackend {
        private val entries = LinkedHashMap<PersistentEntityId, PersistentBackendEntry>()
        private var revision = 0L
        private var highWatermark = 0L

        var legacyLoadCalls = 0
        var metadataCalls = 0
        var exactCalls = 0
        var pageCalls = 0

        fun seed(record: PersistentRecord, generation: Long) {
            require(entries.isEmpty())
            val persistentGeneration = PersistentGeneration(generation)
            entries[record.id] = PersistentBackendEntry(persistentGeneration, record)
            revision = 1L
            highWatermark = generation
        }

        fun resetReadCounters() {
            legacyLoadCalls = 0
            metadataCalls = 0
            exactCalls = 0
            pageCalls = 0
        }

        override fun load(storeId: PersistentStoreId): PersistentBackendLoadResult {
            legacyLoadCalls += 1
            return PersistentBackendLoadResult.Failed("legacy full load must not be used")
        }

        override fun commit(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            state: PersistentBackendState
        ): PersistentBackendCommitResult =
            PersistentBackendCommitResult.Failed("legacy full commit must not be used")

        override fun loadMetadata(storeId: PersistentStoreId): PersistentBackendMetadataLoadResult {
            metadataCalls += 1
            return metadataResult()
        }

        override fun loadEntry(
            storeId: PersistentStoreId,
            entityId: PersistentEntityId
        ): PersistentBackendEntryLoadResult {
            exactCalls += 1
            val entry = entries[entityId] ?: return PersistentBackendEntryLoadResult.Missing
            return PersistentBackendEntryLoadResult.Loaded(
                PersistentRecordSnapshot(entry.record, entry.generation)
            )
        }

        override fun loadPage(
            storeId: PersistentStoreId,
            request: PersistentBackendPageRequest
        ): PersistentBackendPageLoadResult {
            pageCalls += 1
            if (entries.isEmpty()) return PersistentBackendPageLoadResult.Missing
            val ascending = request.order == PersistentBackendPageOrder.OLDEST_FIRST
            val ordered = entries.values
                .map { PersistentRecordSnapshot(it.record, it.generation) }
                .sortedWith(compareBy({ it.record.createdAt }, { it.record.id.value }))
                .let { if (ascending) it else it.reversed() }
            val filtered = ordered.filter { snapshot ->
                val cursor = request.cursorExclusive ?: return@filter true
                if (ascending) {
                    snapshot.record.createdAt > cursor.createdAt ||
                        (snapshot.record.createdAt == cursor.createdAt &&
                            snapshot.record.id.value > cursor.entityId.value)
                } else {
                    snapshot.record.createdAt < cursor.createdAt ||
                        (snapshot.record.createdAt == cursor.createdAt &&
                            snapshot.record.id.value < cursor.entityId.value)
                }
            }
            val selected = filtered.take(request.limit)
            val nextCursor =
                if (filtered.size > request.limit && selected.isNotEmpty()) {
                    val last = selected.last().record
                    PersistentBackendPageCursor(last.createdAt, last.id)
                } else {
                    null
                }
            return PersistentBackendPageLoadResult.Loaded(
                PersistentBackendPage(selected, nextCursor)
            )
        }

        override fun installEntry(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            expectedHighWatermark: Long,
            entry: PersistentBackendEntry
        ): PersistentBackendMutationResult {
            if (expectedRevision != revision || expectedHighWatermark != highWatermark) {
                return PersistentBackendMutationResult.Conflict
            }
            if (entries.containsKey(entry.record.id) ||
                entry.generation.value != highWatermark + 1L
            ) {
                return PersistentBackendMutationResult.Rejected("invalid indexed install")
            }
            entries[entry.record.id] = entry
            revision += 1L
            highWatermark = entry.generation.value
            return PersistentBackendMutationResult.Committed(
                PersistentBackendMetadata(
                    revision = revision,
                    highWatermark = highWatermark,
                    entryCount = entries.size.toLong()
                )
            )
        }

        override fun transitionEntry(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            expectedHighWatermark: Long,
            sourceId: PersistentEntityId,
            sourceGeneration: PersistentGeneration,
            replacement: PersistentBackendEntry
        ): PersistentBackendMutationResult =
            PersistentBackendMutationResult.Failed("unused")

        override fun removeEntry(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            expectedHighWatermark: Long,
            id: PersistentEntityId,
            generation: PersistentGeneration
        ): PersistentBackendMutationResult =
            PersistentBackendMutationResult.Failed("unused")

        private fun metadataResult(): PersistentBackendMetadataLoadResult =
            if (revision == 0L && entries.isEmpty()) {
                PersistentBackendMetadataLoadResult.Missing
            } else {
                PersistentBackendMetadataLoadResult.Loaded(
                    PersistentBackendMetadata(
                        revision = revision,
                        highWatermark = highWatermark,
                        entryCount = entries.size.toLong()
                    )
                )
            }
    }

    @Test
    fun plaintext_indexed_memory_and_knowledge_open_without_full_enumeration() {
        val memoryBackend = LazyDomainBackend()
        val memory = memoryRecord("lazy-memory", "lazy memory")
        memoryBackend.seed(MemoryPersistentRecordCodec.encode(memory), 7L)
        memoryBackend.resetReadCounters()

        val memoryComposition = assertIs<PersistentMemoryOpenResult.Opened>(
            PersistentMemoryComposition.open(
                foundation(),
                PersistentStoreId("lazy-memory-store"),
                memoryBackend
            )
        ).composition
        assertEquals(0, memoryBackend.legacyLoadCalls)
        assertEquals(0, memoryBackend.pageCalls)
        assertEquals(0, memoryBackend.exactCalls)

        val memorySnapshot = requireNotNull(memoryComposition.inspect(memory.id))
        assertEquals(memory, memorySnapshot.record)
        assertEquals(1, memoryBackend.exactCalls)
        assertEquals(0, memoryBackend.pageCalls)

        assertEquals(listOf(memorySnapshot), memoryComposition.snapshotEntries())
        assertTrue(memoryBackend.pageCalls > 0)

        val knowledgeBackend = LazyDomainBackend()
        val knowledge = knowledgeItem("lazy-knowledge", "lazy knowledge")
        knowledgeBackend.seed(KnowledgePersistentRecordCodec.encode(knowledge), 11L)
        knowledgeBackend.resetReadCounters()

        val knowledgeComposition = assertIs<PersistentKnowledgeOpenResult.Opened>(
            PersistentKnowledgeComposition.open(
                foundation(),
                PersistentStoreId("lazy-knowledge-store"),
                knowledgeBackend
            )
        ).composition
        assertEquals(0, knowledgeBackend.legacyLoadCalls)
        assertEquals(0, knowledgeBackend.pageCalls)
        assertEquals(0, knowledgeBackend.exactCalls)

        val knowledgeSnapshot = requireNotNull(knowledgeComposition.inspect(knowledge.id))
        assertEquals(knowledge, knowledgeSnapshot.item)
        assertEquals(1, knowledgeBackend.exactCalls)
        assertEquals(0, knowledgeBackend.pageCalls)

        assertEquals(listOf(knowledgeSnapshot), knowledgeComposition.snapshotEntries())
        assertTrue(knowledgeBackend.pageCalls > 0)
    }

    @Test
    fun encrypted_indexed_domains_open_lazy_and_exact_decrypt_uses_one_backend_lookup() {
        val memoryBackend = LazyDomainBackend()
        val memoryStoreId = PersistentStoreId("encrypted-lazy-memory")
        val firstMemoryStore = encryptedStore(memoryBackend, memoryStoreId, resolverAvailable())
        val firstMemory = assertIs<EncryptedPersistentMemoryOpenResult.Opened>(
            EncryptedPersistentMemoryComposition.open(
                foundation(),
                firstMemoryStore,
                dekRef
            )
        ).composition
        val memory = memoryRecord("encrypted-lazy-memory", "encrypted lazy memory")
        assertIs<PersistentMemoryRememberResult.Remembered>(firstMemory.remember(memory))

        memoryBackend.resetReadCounters()
        val reopenedMemory = assertIs<EncryptedPersistentMemoryOpenResult.Opened>(
            EncryptedPersistentMemoryComposition.open(
                foundation(),
                encryptedStore(memoryBackend, memoryStoreId, resolverAvailable()),
                dekRef
            )
        ).composition
        assertEquals(0, memoryBackend.legacyLoadCalls)
        assertEquals(0, memoryBackend.pageCalls)
        assertEquals(0, memoryBackend.exactCalls)

        val memorySnapshot = requireNotNull(reopenedMemory.inspect(memory.id))
        assertEquals(memory, memorySnapshot.record)
        assertEquals(1, memoryBackend.exactCalls)
        assertEquals(0, memoryBackend.pageCalls)

        val missingDekMemory = assertIs<EncryptedPersistentMemoryOpenResult.Opened>(
            EncryptedPersistentMemoryComposition.open(
                foundation(),
                encryptedStore(memoryBackend, memoryStoreId, resolverMissing()),
                dekRef
            )
        ).composition
        val unavailable = assertIs<EncryptedPersistentMemoryInspectResult.EncryptionUnavailable>(
            missingDekMemory.inspectResult(memory.id)
        )
        assertEquals(CognitiveEncryptionFailureCategory.DEK_MISSING, unavailable.category)
        assertFailsWith<IllegalStateException> {
            missingDekMemory.inspect(memory.id)
        }

        val knowledgeBackend = LazyDomainBackend()
        val knowledgeStoreId = PersistentStoreId("encrypted-lazy-knowledge")
        val firstKnowledge = assertIs<EncryptedPersistentKnowledgeOpenResult.Opened>(
            EncryptedPersistentKnowledgeComposition.open(
                foundation(),
                encryptedStore(knowledgeBackend, knowledgeStoreId, resolverAvailable()),
                dekRef
            )
        ).composition
        val knowledge = knowledgeItem("encrypted-lazy-knowledge", "encrypted lazy knowledge")
        assertIs<PersistentKnowledgeCreateResult.Created>(firstKnowledge.create(knowledge))

        knowledgeBackend.resetReadCounters()
        val reopenedKnowledge = assertIs<EncryptedPersistentKnowledgeOpenResult.Opened>(
            EncryptedPersistentKnowledgeComposition.open(
                foundation(),
                encryptedStore(knowledgeBackend, knowledgeStoreId, resolverAvailable()),
                dekRef
            )
        ).composition
        assertEquals(0, knowledgeBackend.legacyLoadCalls)
        assertEquals(0, knowledgeBackend.pageCalls)
        assertEquals(0, knowledgeBackend.exactCalls)

        val knowledgeSnapshot = requireNotNull(reopenedKnowledge.inspect(knowledge.id))
        assertEquals(knowledge, knowledgeSnapshot.item)
        assertEquals(1, knowledgeBackend.exactCalls)
        assertEquals(0, knowledgeBackend.pageCalls)

        val missingDekKnowledge = assertIs<EncryptedPersistentKnowledgeOpenResult.Opened>(
            EncryptedPersistentKnowledgeComposition.open(
                foundation(),
                encryptedStore(knowledgeBackend, knowledgeStoreId, resolverMissing()),
                dekRef
            )
        ).composition
        val knowledgeUnavailable =
            assertIs<EncryptedPersistentKnowledgeInspectResult.EncryptionUnavailable>(
                missingDekKnowledge.inspectResult(knowledge.id)
            )
        assertEquals(CognitiveEncryptionFailureCategory.DEK_MISSING, knowledgeUnavailable.category)
        assertFailsWith<IllegalStateException> {
            missingDekKnowledge.inspect(knowledge.id)
        }
    }

    private fun encryptedStore(
        backend: PersistentRecordBackend,
        storeId: PersistentStoreId,
        resolver: CognitiveDekMaterialResolver
    ): EncryptedPersistentRecordStore {
        val store = assertIs<PersistentStoreOpenResult.Opened>(
            PersistentRecordStore.open(foundation(), storeId, backend)
        ).store
        return EncryptedPersistentRecordStore(
            store = store,
            profile = profile,
            envelopeVersion = CognitiveEnvelopeVersion(1),
            nonceSource = TestNonceSource(),
            aead = TestAeadProvider(),
            dekResolver = resolver
        )
    }

    private fun resolverAvailable(): CognitiveDekMaterialResolver =
        object : CognitiveDekMaterialResolver {
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

    private fun resolverMissing(): CognitiveDekMaterialResolver =
        object : CognitiveDekMaterialResolver {
            override fun resolve(
                reference: CognitiveDekReference
            ): CognitiveEncryptionResult<CognitiveDekMaterial> =
                CognitiveEncryptionResult.Rejected(
                    CognitiveEncryptionFailureCategory.DEK_MISSING
                )
        }

    private fun memoryRecord(id: String, content: String): MemoryRecord =
        MemoryRecord(
            id = MemoryRecordId(id),
            provenance = MemoryProvenance(MemorySourceId("conversation")),
            content = content,
            createdAt = Instant.parse("2026-09-25T11:00:00Z")
        )

    private fun knowledgeItem(id: String, content: String): KnowledgeItem =
        KnowledgeItem(
            id = KnowledgeItemId(id),
            origin = KnowledgeOrigin.Declared(KnowledgeSourceId("declared")),
            content = content,
            createdAt = Instant.parse("2026-09-25T11:01:00Z")
        )

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            correlationIds = CorrelationIdGenerator {
                "lazy-domain-" + sequence.incrementAndGet()
            }
        )
    }

    private class TestNonceSource : CognitiveNonceSource {
        override fun next(
            profile: CognitiveEncryptionProfile
        ): CognitiveEncryptionResult<CognitiveNonce> =
            CognitiveEncryptionResult.Success(
                CognitiveNonce(profile, ByteArray(profile.nonceSizeBytes) { 7 })
            )
    }

    private class TestAeadProvider : CognitiveAeadProvider {
        override fun seal(
            profile: CognitiveEncryptionProfile,
            dek: CognitiveDekMaterial,
            nonce: CognitiveNonce,
            associatedData: CognitiveAssociatedData,
            plaintext: CognitivePlaintext
        ): CognitiveEncryptionResult<CognitiveAeadSealedData> =
            CognitiveEncryptionResult.Success(
                CognitiveAeadSealedData(
                    plaintext.copyBytes(),
                    ByteArray(profile.authenticationTagSizeBits / 8)
                )
            )

        override fun open(
            profile: CognitiveEncryptionProfile,
            dek: CognitiveDekMaterial,
            nonce: CognitiveNonce,
            associatedData: CognitiveAssociatedData,
            sealed: CognitiveAeadSealedData
        ): CognitiveEncryptionResult<CognitivePlaintext> =
            CognitiveEncryptionResult.Success(
                CognitivePlaintext(sealed.copyCiphertext())
            )
    }

    private companion object {
        val profile = CognitiveEncryptionProfile.AES_256_GCM
        val dekRef = CognitiveDekReference(
            CognitiveDekId("lazy-domain-dek"),
            CognitiveDekGeneration(1)
        )
        val material = CognitiveDekMaterial(ByteArray(32) { (it + 1).toByte() })
    }
}
