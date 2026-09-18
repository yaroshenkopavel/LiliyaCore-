package pro.liliya.core.cognitive

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
import pro.liliya.core.foundation.CorrelationIdGenerator
import pro.liliya.core.foundation.FoundationComposition
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
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

class ConversationV3NativeRuntimeContractTest {
    private val storeId = PersistentStoreId("conversation-v3-native-test")
    private val profile = CognitiveEncryptionProfile.AES_256_GCM
    private val dekRef = CognitiveDekReference(
        CognitiveDekId("v3-test-dek"),
        CognitiveDekGeneration(1)
    )
    private val material = CognitiveDekMaterial(
        profile,
        ByteArray(32) { (it + 1).toByte() }
    )

    @Test
    fun indexed_native_v3_reopens_one_session_without_full_store_scan() {
        val backend = CountingIndexedBackend()
        val first = openConversation(backend)
        val sessionA = CognitiveConversationSessionId("session-A")
        val sessionB = CognitiveConversationSessionId("session-B")

        assertIs<PersistentConversationAppendPairResult.Appended>(
            first.appendPair(
                sessionA,
                msg(1, CognitiveConversationRole.USER, "a-user"),
                msg(2, CognitiveConversationRole.ASSISTANT, "a-reply"),
                at(1)
            )
        )
        assertIs<PersistentConversationAppendPairResult.Appended>(
            first.appendPair(
                sessionB,
                msg(1, CognitiveConversationRole.USER, "b-user"),
                msg(2, CognitiveConversationRole.ASSISTANT, "b-reply"),
                at(2)
            )
        )

        assertEquals(0, backend.fullLoadCalls)
        assertEquals(0, backend.fullCommitCalls)
        assertTrue(backend.entries.containsKey(ConversationV3IndexCodec.MARKER_ID))
        assertTrue(backend.entries.containsKey(ConversationV3IndexCodec.headId(sessionA)))
        assertTrue(backend.entries.containsKey(ConversationV3IndexCodec.headId(sessionB)))

        backend.resetReadCounters()
        val reopened = openConversation(backend)
        assertEquals(0, backend.fullLoadCalls)
        assertEquals(0, backend.pageLoadCalls)

        val snapshot = assertNotNull(reopened.reopen(sessionA))
        assertEquals(listOf("a-user", "a-reply"), snapshot.messages.map { it.content })
        assertEquals(0, backend.pageLoadCalls)
        assertFalse(backend.exactReadIds.contains(ConversationV3IndexCodec.headId(sessionB)))
        assertTrue(backend.exactReadIds.contains(ConversationV3IndexCodec.headId(sessionA)))
    }

    @Test
    fun orphan_chunk_after_head_conflict_is_recovered_without_global_scan() {
        val backend = CountingIndexedBackend()
        val session = CognitiveConversationSessionId("recover-v3-session")
        val store = openConversation(backend, maxRetained = 6)

        assertIs<PersistentConversationAppendPairResult.Appended>(
            store.appendPair(
                session,
                msg(1, CognitiveConversationRole.USER, "u1"),
                msg(2, CognitiveConversationRole.ASSISTANT, "a1"),
                at(1)
            )
        )

        backend.failNextHeadTransition = true
        assertIs<PersistentConversationAppendPairResult.Rejected>(
            store.appendPair(
                session,
                msg(3, CognitiveConversationRole.USER, "u2"),
                msg(4, CognitiveConversationRole.ASSISTANT, "a2"),
                at(2)
            )
        )

        backend.resetReadCounters()
        val recovered = assertNotNull(store.reopen(session))
        assertEquals(listOf(1L, 2L, 3L, 4L), recovered.messages.map { it.sequence.value })
        assertEquals(0, backend.pageLoadCalls)

        assertIs<PersistentConversationAppendPairResult.AlreadyPresent>(
            store.appendPair(
                session,
                msg(3, CognitiveConversationRole.USER, "u2"),
                msg(4, CognitiveConversationRole.ASSISTANT, "a2"),
                at(3)
            )
        )
    }

    @Test
    fun existing_indexed_store_without_v3_marker_stays_on_legacy_fallback() {
        val backend = CountingIndexedBackend()
        val encrypted = encryptedStore(backend)
        assertIs<CognitiveEncryptionResult.Success<*>>(
            encrypted.install(
                CognitivePersistentRecordDraft(
                    id = PersistentEntityId("pre-v3-record"),
                    schemaId = PersistentSchemaId("pre-v3-schema"),
                    schemaVersion = PersistentSchemaVersion(1),
                    plaintext = CognitivePlaintext("legacy".encodeToByteArray()),
                    createdAt = at(1),
                    dek = dekRef
                )
            )
        )
        assertFalse(backend.entries.containsKey(ConversationV3IndexCodec.MARKER_ID))

        val result = EncryptedPersistentConversationStore.open(
            encryptedStore = encryptedStore(backend),
            activeDek = dekRef,
            maxRetainedMessages = 4,
            maxMessageChars = 1024
        )
        assertIs<PersistentConversationOpenResult.Incompatible>(result)
        assertFalse(backend.entries.containsKey(ConversationV3IndexCodec.MARKER_ID))
        assertTrue(backend.pageLoadCalls > 0)
    }

    @Test
    fun native_v3_keeps_complete_history_while_working_tail_is_bounded() {
        val backend = CountingIndexedBackend()
        val session = CognitiveConversationSessionId("long-v3-session")
        val store = openConversation(backend, maxRetained = 4)

        repeat(10) { turn ->
            val first = turn.toLong() * 2L + 1L
            assertIs<PersistentConversationAppendPairResult.Appended>(
                store.appendPair(
                    session,
                    msg(first, CognitiveConversationRole.USER, "u-$turn"),
                    msg(first + 1L, CognitiveConversationRole.ASSISTANT, "a-$turn"),
                    at(first)
                )
            )
        }

        val reopened = openConversation(backend, maxRetained = 4)
        val tail = assertNotNull(reopened.reopen(session))
        assertEquals(listOf(17L, 18L, 19L, 20L), tail.messages.map { it.sequence.value })

        val oldest = assertIs<PersistentConversationHistoryResult.Found>(
            reopened.history(
                sessionId = session,
                beforeSequenceExclusive = 5L,
                maxMessages = 4
            )
        ).snapshot
        assertEquals(listOf(1L, 2L, 3L, 4L), oldest.messages.map { it.sequence.value })

        val chunkRows = backend.entries.keys.count {
            it.value.startsWith("conversation-v3-chunk-")
        }
        assertEquals(10, chunkRows)
    }

    private fun openConversation(
        backend: CountingIndexedBackend,
        maxRetained: Int = 4
    ): EncryptedPersistentConversationStore {
        val encrypted = encryptedStore(backend)
        return assertIs<PersistentConversationOpenResult.Opened>(
            EncryptedPersistentConversationStore.open(
                encryptedStore = encrypted,
                activeDek = dekRef,
                maxRetainedMessages = maxRetained,
                maxMessageChars = 1024
            )
        ).store
    }

    private fun encryptedStore(
        backend: CountingIndexedBackend
    ): EncryptedPersistentRecordStore {
        val raw = assertIs<PersistentStoreOpenResult.Opened>(
            PersistentRecordStore.open(foundation(), storeId, backend)
        ).store
        return EncryptedPersistentRecordStore(
            store = raw,
            profile = profile,
            envelopeVersion = CognitiveEnvelopeVersion(1),
            nonceSource = DeterministicNonceSource(),
            aead = DeterministicAeadProvider(),
            dekResolver = resolver()
        )
    }

    private fun resolver() = object : CognitiveDekMaterialResolver {
        override fun resolve(
            reference: CognitiveDekReference
        ): CognitiveEncryptionResult<CognitiveDekMaterial> =
            if (reference == dekRef) CognitiveEncryptionResult.Success(material)
            else CognitiveEncryptionResult.Rejected(
                CognitiveEncryptionFailureCategory.DEK_MISSING
            )
    }

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger()
        val writer = InMemoryLogWriter()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, writer) },
            correlationIds = CorrelationIdGenerator {
                "v3-" + sequence.incrementAndGet()
            }
        )
    }

    private fun msg(
        sequence: Long,
        role: CognitiveConversationRole,
        content: String
    ) = CognitiveConversationContextMessage(
        CognitiveConversationSequence(sequence),
        role,
        content
    )

    private fun at(second: Long): Instant =
        Instant.ofEpochSecond(1_900_000_000L + second)

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
                )
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
                (plain[i].toInt() xor
                    key[i % key.size].toInt() xor
                    n[i % n.size].toInt()).toByte()
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
            val expected = tag(key, n, associatedData.copyBytes(), cipher)
            if (!MessageDigest.isEqual(expected, sealed.copyAuthenticationTag())) {
                return CognitiveEncryptionResult.Rejected(
                    CognitiveEncryptionFailureCategory.CIPHERTEXT_AUTHENTICATION_FAILED
                )
            }
            return CognitiveEncryptionResult.Success(
                CognitivePlaintext(
                    ByteArray(cipher.size) { i ->
                        (cipher[i].toInt() xor
                            key[i % key.size].toInt() xor
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
        ): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(key)
            digest.update(nonce)
            digest.update(aad)
            digest.update(cipher)
            return digest.digest().copyOf(16)
        }
    }

    private class CountingIndexedBackend : IndexedPersistentRecordMutationBackend {
        val entries = LinkedHashMap<PersistentEntityId, PersistentRecordSnapshot>()
        private var revision = 0L
        private var highWatermark = 0L

        var fullLoadCalls = 0
        var fullCommitCalls = 0
        var pageLoadCalls = 0
        var failNextHeadTransition = false
        val exactReadIds = ArrayList<PersistentEntityId>()

        fun resetReadCounters() {
            pageLoadCalls = 0
            exactReadIds.clear()
        }

        override fun load(
            storeId: PersistentStoreId
        ): PersistentBackendLoadResult {
            fullLoadCalls += 1
            return PersistentBackendLoadResult.Failed(
                "full load must not be used"
            )
        }

        override fun commit(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            state: PersistentBackendState
        ): PersistentBackendCommitResult {
            fullCommitCalls += 1
            return PersistentBackendCommitResult.Failed(
                "full commit must not be used"
            )
        }

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
        ): PersistentBackendEntryLoadResult {
            exactReadIds += entityId
            return entries[entityId]
                ?.let { PersistentBackendEntryLoadResult.Loaded(it) }
                ?: PersistentBackendEntryLoadResult.Missing
        }

        override fun loadPage(
            storeId: PersistentStoreId,
            request: PersistentBackendPageRequest
        ): PersistentBackendPageLoadResult {
            pageLoadCalls += 1
            val ordered = entries.values
                .filter {
                    request.schemaId == null ||
                        it.record.schemaId == request.schemaId
                }
                .sortedWith(
                    compareBy(
                        { it.record.createdAt },
                        { it.record.id.value }
                    )
                )
            val directional =
                if (request.order == PersistentBackendPageOrder.OLDEST_FIRST) {
                    ordered
                } else {
                    ordered.asReversed()
                }
            val filtered = directional.filter { snapshot ->
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
            val next =
                if (filtered.size > request.limit && page.isNotEmpty()) {
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
            if (expectedRevision != revision ||
                expectedHighWatermark != highWatermark
            ) {
                return PersistentBackendMutationResult.Conflict
            }
            if (entries.containsKey(entry.record.id) ||
                entry.generation.value != highWatermark + 1L
            ) {
                return PersistentBackendMutationResult.Rejected(
                    "invalid install"
                )
            }
            entries[entry.record.id] =
                PersistentRecordSnapshot(entry.record, entry.generation)
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
            if (failNextHeadTransition &&
                sourceId.value.startsWith("conversation-head-")
            ) {
                failNextHeadTransition = false
                return PersistentBackendMutationResult.Conflict
            }
            if (expectedRevision != revision ||
                expectedHighWatermark != highWatermark
            ) {
                return PersistentBackendMutationResult.Conflict
            }
            val source = entries[sourceId]
                ?: return PersistentBackendMutationResult.Rejected(
                    "missing source"
                )
            if (source.generation != sourceGeneration ||
                replacement.generation != sourceGeneration
            ) {
                return PersistentBackendMutationResult.Rejected(
                    "stale source"
                )
            }
            if (replacement.record.id != sourceId &&
                entries.containsKey(replacement.record.id)
            ) {
                return PersistentBackendMutationResult.Rejected(
                    "replacement exists"
                )
            }
            entries.remove(sourceId)
            entries[replacement.record.id] =
                PersistentRecordSnapshot(
                    replacement.record,
                    replacement.generation
                )
            revision += 1L
            return PersistentBackendMutationResult.Committed(metadata())
        }

        override fun removeEntry(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            expectedHighWatermark: Long,
            id: PersistentEntityId,
            generation: PersistentGeneration
        ): PersistentBackendMutationResult {
            if (expectedRevision != revision ||
                expectedHighWatermark != highWatermark
            ) {
                return PersistentBackendMutationResult.Conflict
            }
            val source = entries[id]
                ?: return PersistentBackendMutationResult.Rejected(
                    "missing source"
                )
            if (source.generation != generation) {
                return PersistentBackendMutationResult.Rejected(
                    "stale source"
                )
            }
            entries.remove(id)
            revision += 1L
            return PersistentBackendMutationResult.Committed(metadata())
        }

        private fun metadata() = PersistentBackendMetadata(
            revision = revision,
            highWatermark = highWatermark,
            entryCount = entries.size.toLong()
        )
    }
}
