package pro.liliya.core.strategy

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
import pro.liliya.core.episodic.RawEvidenceId
import pro.liliya.core.episodic.RawEvidenceNamespace
import pro.liliya.core.episodic.RawEvidenceReference
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

class EncryptedPersistentStrategyAdaptationStoreContractTest {
    private val profile = CognitiveEncryptionProfile.AES_256_GCM
    private val dekRef = CognitiveDekReference(
        CognitiveDekId("strategy-adaptation-dek"),
        CognitiveDekGeneration(1)
    )
    private val material = CognitiveDekMaterial(ByteArray(32) { (it * 5 + 17).toByte() })

    @Test
    fun encrypted_record_reopens_exactly_without_restoring_permission_state() {
        val backend = IndexedBackend()
        val first = openStore(backend, PersistentStoreId("strategy-adaptation"))
        val record = record(1)

        val stored = assertIs<StrategyAdaptationStoreResult.Stored>(first.store(record))
        assertEquals(1L, stored.snapshot.generation)

        val reopened = openStore(backend, PersistentStoreId("strategy-adaptation"))
        val found = assertIs<StrategyAdaptationLookupResult.Found>(
            reopened.lookup(record.candidate.id)
        )
        assertEquals(record, found.snapshot.record)
        assertEquals(record.candidate.id, found.snapshot.record.candidate.id)
        assertEquals(0, backend.legacyLoadCalls)
        assertEquals(0, backend.legacyCommitCalls)
    }

    @Test
    fun exact_duplicate_replay_is_rejected_instead_of_creating_a_second_generation() {
        val backend = IndexedBackend()
        val store = openStore(backend, PersistentStoreId("strategy-adaptation-replay"))
        val record = record(1)

        assertIs<StrategyAdaptationStoreResult.Stored>(store.store(record))
        val duplicate = assertIs<StrategyAdaptationStoreResult.Rejected>(store.store(record))
        assertEquals(
            CognitiveEncryptionFailureCategory.PERSISTENCE_CONFLICT,
            duplicate.category
        )
        val found = assertIs<StrategyAdaptationLookupResult.Found>(store.lookup(record.candidate.id))
        assertEquals(1L, found.snapshot.generation)
    }
    @Test
    fun bounded_page_uses_persistent_cursor_and_never_scans_as_snapshot() {
        val backend = IndexedBackend()
        val store = openStore(backend, PersistentStoreId("strategy-adaptation-pages"))
        val firstRecord = record(1, decidedAt = Instant.parse("2026-09-28T10:01:00Z"))
        val secondRecord = record(2, decidedAt = Instant.parse("2026-09-28T10:02:00Z"))
        val thirdRecord = record(3, decidedAt = Instant.parse("2026-09-28T10:03:00Z"))
        listOf(firstRecord, secondRecord, thirdRecord).forEach {
            assertIs<StrategyAdaptationStoreResult.Stored>(store.store(it))
        }

        val firstPage = assertIs<StrategyAdaptationPageResult.Loaded>(
            store.page(2, PersistentBackendPageOrder.OLDEST_FIRST)
        )
        assertEquals(
            listOf(firstRecord.candidate.id, secondRecord.candidate.id),
            firstPage.entries.map { it.record.candidate.id }
        )

        val secondPage = assertIs<StrategyAdaptationPageResult.Loaded>(
            store.page(2, PersistentBackendPageOrder.OLDEST_FIRST, firstPage.nextCursor)
        )
        assertEquals(
            listOf(thirdRecord.candidate.id),
            secondPage.entries.map { it.record.candidate.id }
        )
        assertEquals(0, backend.legacyLoadCalls)
    }

    private fun record(
        version: Long,
        decidedAt: Instant = Instant.parse("2026-09-28T10:00:00Z").plusSeconds(version)
    ): StrategyAdaptationRecord {
        val candidate = StrategyCandidate.create(
            version = StrategyVersion(version),
            source = StrategyReflectionSource(
                pro.liliya.core.reflection.ReflectionResultId(
                    "reflection-result-" + version.toString().padStart(64, 'a')
                ),
                pro.liliya.core.reflection.ReflectionVersion(1),
                0,
                pro.liliya.core.reflection.ReflectionFindingKind.STRATEGY_CANDIDATE_INPUT
            ),
            target = StrategyTarget.RETRIEVAL,
            scope = StrategyScope("semantic.retrieval.ranking"),
            proposal = StrategyText("strategy $version"),
            compatibility = listOf(
                StrategyCompatibilityConstraint("abi", "arm64-v8a"),
                StrategyCompatibilityConstraint("runtime", "offline")
            ),
            rollbackTo = null,
            createdAt = decidedAt.minusSeconds(2),
            expiresAt = decidedAt.plusSeconds(300)
        )
        val ref = StrategyReference(candidate.id, candidate.version)
        val validation = StrategyValidationRecord.create(
            candidate = ref,
            disposition = StrategyValidationDisposition.VALID,
            constraintResults = candidate.compatibility.map {
                StrategyConstraintResult(
                    it,
                    StrategyConstraintDisposition.SATISFIED,
                    "compatible"
                )
            },
            policyId = StrategyPolicyId("strategy-validation-v1"),
            policyVersion = StrategyPolicyVersion(1),
            validatedAt = decidedAt.minusSeconds(1)
        )
        val adoption = StrategyAdoptionRecord.create(
            candidate = ref,
            validation = StrategyValidationReference(validation.id, ref),
            disposition = StrategyAdoptionDisposition.ADOPT,
            rationale = "adopted strategy $version",
            decidedAt = decidedAt
        )
        val intent = StrategyApplicationIntent.create(
            candidate = ref,
            adoption = StrategyAdoptionReference(adoption.id, ref),
            target = candidate.target,
            scope = candidate.scope,
            createdAt = decidedAt.plusNanos(1)
        )
        return StrategyAdaptationRecord(candidate, validation, adoption, intent)
    }

    private fun openStore(
        backend: IndexedBackend,
        storeId: PersistentStoreId
    ): EncryptedPersistentStrategyAdaptationStore {
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
        return assertIs<StrategyAdaptationMemoryOpenResult.Opened>(
            EncryptedPersistentStrategyAdaptationStore.open(encrypted, dekRef)
        ).store
    }

    private fun resolver() = object : CognitiveDekMaterialResolver {
        override fun resolve(
            reference: CognitiveDekReference
        ): CognitiveEncryptionResult<CognitiveDekMaterial> =
            if (reference == dekRef) CognitiveEncryptionResult.Success(material)
            else CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.DEK_MISSING)
    }
    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger()
        val writer = InMemoryLogWriter()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, writer) },
            correlationIds = CorrelationIdGenerator {
                "strategy-adaptation-" + sequence.incrementAndGet()
            }
        )
    }

    private class IndexedBackend : IndexedPersistentRecordMutationBackend {
        private val entries = LinkedHashMap<PersistentEntityId, PersistentBackendEntry>()
        private var revision = 0L
        private var highWatermark = 0L
        var legacyLoadCalls = 0
        var legacyCommitCalls = 0

        override fun load(storeId: PersistentStoreId): PersistentBackendLoadResult {
            legacyLoadCalls += 1
            return PersistentBackendLoadResult.Failed("legacy load must not be used")
        }

        override fun commit(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            state: PersistentBackendState
        ): PersistentBackendCommitResult {
            legacyCommitCalls += 1
            return PersistentBackendCommitResult.Failed("legacy commit must not be used")
        }

        override fun loadMetadata(storeId: PersistentStoreId): PersistentBackendMetadataLoadResult =
            if (revision == 0L && entries.isEmpty()) PersistentBackendMetadataLoadResult.Missing
            else PersistentBackendMetadataLoadResult.Loaded(metadata())
        override fun loadEntry(
            storeId: PersistentStoreId,
            entityId: PersistentEntityId
        ): PersistentBackendEntryLoadResult = entries[entityId]?.let {
            PersistentBackendEntryLoadResult.Loaded(
                PersistentRecordSnapshot(it.record, it.generation)
            )
        } ?: PersistentBackendEntryLoadResult.Missing

        override fun loadPage(
            storeId: PersistentStoreId,
            request: PersistentBackendPageRequest
        ): PersistentBackendPageLoadResult {
            val ordered = entries.values
                .filter { request.schemaId == null || it.record.schemaId == request.schemaId }
                .map { PersistentRecordSnapshot(it.record, it.generation) }
                .sortedWith(compareBy({ it.record.createdAt }, { it.record.id.value }))
                .let {
                    if (request.order == PersistentBackendPageOrder.OLDEST_FIRST) it
                    else it.asReversed()
                }
            val filtered = ordered.filter { snapshot ->
                val cursor = request.cursorExclusive ?: return@filter true
                when (request.order) {
                    PersistentBackendPageOrder.OLDEST_FIRST ->
                        snapshot.record.createdAt > cursor.createdAt ||
                            (snapshot.record.createdAt == cursor.createdAt &&
                                snapshot.record.id.value > cursor.entityId.value)
                    PersistentBackendPageOrder.NEWEST_FIRST ->
                        snapshot.record.createdAt < cursor.createdAt ||
                            (snapshot.record.createdAt == cursor.createdAt &&
                                snapshot.record.id.value < cursor.entityId.value)
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
                    ByteArray(profile.nonceSizeBytes) { (next + it).toByte() }
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
            val expected = tag(key, n, associatedData.copyBytes(), cipher)
            if (!MessageDigest.isEqual(expected, sealed.copyAuthenticationTag())) {
                return CognitiveEncryptionResult.Rejected(
                    CognitiveEncryptionFailureCategory.CIPHERTEXT_AUTHENTICATION_FAILED
                )
            }
            return CognitiveEncryptionResult.Success(
                CognitivePlaintext(
                    ByteArray(cipher.size) { i ->
                        (cipher[i].toInt() xor key[i % key.size].toInt() xor
                            n[i % n.size].toInt()).toByte()
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
