package pro.liliya.core.cognitive

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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
    fun native_v3_rows_do_not_expose_session_or_message_plaintext() {
        val backend = CountingIndexedBackend()
        val session = CognitiveConversationSessionId("private-native-v3-session")
        val messageText = "private conversation text"
        val store = openConversation(backend)

        assertIs<PersistentConversationAppendResult.Appended>(
            store.append(
                session,
                msg(1, CognitiveConversationRole.USER, messageText),
                at(1)
            )
        )

        val sessionBytes = session.value.encodeToByteArray()
        val messageBytes = messageText.encodeToByteArray()
        for ((id, snapshot) in backend.entries) {
            assertFalse(id.value.contains(session.value))
            val payload = snapshot.record.payload.copyBytes()
            assertFalse(containsSubsequence(payload, sessionBytes))
            assertFalse(containsSubsequence(payload, messageBytes))
        }
    }

    @Test
    fun mixed_mode_marker_is_detected_without_global_legacy_scan() {
        val backend = CountingIndexedBackend()
        val encrypted = encryptedStore(backend)
        val markerRecord = ConversationV3MigrationCodec.encodeMixedMarker(at(1))
        assertIs<CognitiveEncryptionResult.Success<*>>(
            encrypted.install(
                CognitivePersistentRecordDraft(
                    id = markerRecord.id,
                    schemaId = markerRecord.schemaId,
                    schemaVersion = markerRecord.schemaVersion,
                    plaintext = CognitivePlaintext(markerRecord.payload.copyBytes()),
                    createdAt = markerRecord.createdAt,
                    dek = dekRef
                )
            )
        )

        backend.resetReadCounters()
        val opened = assertIs<PersistentConversationOpenResult.Opened>(
            EncryptedPersistentConversationStore.open(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                maxRetainedMessages = 4,
                maxMessageChars = 1024
            )
        ).store
        assertIs<PersistentConversationReopenResult.Absent>(
            opened.reopenResult(
                CognitiveConversationSessionId("missing-mixed-session")
            )
        )
        assertEquals(0, backend.pageLoadCalls)
        assertTrue(backend.exactReadIds.contains(ConversationV3IndexCodec.MARKER_ID))
        assertTrue(
            backend.exactReadIds.contains(
                ConversationV3MigrationCodec.MIXED_MARKER_ID
            )
        )
    }

    @Test
    fun native_and_mixed_markers_together_fail_open_closed() {
        val backend = CountingIndexedBackend()
        openConversation(backend)

        val encrypted = encryptedStore(backend)
        val markerRecord = ConversationV3MigrationCodec.encodeMixedMarker(at(2))
        assertIs<CognitiveEncryptionResult.Success<*>>(
            encrypted.install(
                CognitivePersistentRecordDraft(
                    id = markerRecord.id,
                    schemaId = markerRecord.schemaId,
                    schemaVersion = markerRecord.schemaVersion,
                    plaintext = CognitivePlaintext(markerRecord.payload.copyBytes()),
                    createdAt = markerRecord.createdAt,
                    dek = dekRef
                )
            )
        )

        val reopened = EncryptedPersistentConversationStore.open(
            encryptedStore = encryptedStore(backend),
            activeDek = dekRef,
            maxRetainedMessages = 4,
            maxMessageChars = 1024
        )
        assertIs<PersistentConversationOpenResult.Corrupt>(reopened)
    }

    @Test
    fun corrupted_native_v3_marker_fails_open_closed() {
        val backend = CountingIndexedBackend()
        openConversation(backend)

        val markerId = ConversationV3IndexCodec.MARKER_ID
        val markerRow = assertNotNull(backend.entries[markerId])
        val bytes = markerRow.record.payload.copyBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        backend.entries[markerId] = markerRow.copy(
            record = markerRow.record.copy(
                payload = pro.liliya.core.persistence.PersistentPayload(bytes)
            )
        )

        val reopened = EncryptedPersistentConversationStore.open(
            encryptedStore = encryptedStore(backend),
            activeDek = dekRef,
            maxRetainedMessages = 4,
            maxMessageChars = 1024
        )
        assertFalse(reopened is PersistentConversationOpenResult.Opened)
        assertTrue(
            reopened is PersistentConversationOpenResult.EncryptionUnavailable ||
                reopened is PersistentConversationOpenResult.Corrupt
        )
    }

    @Test
    fun corrupted_native_v3_head_is_not_reported_as_absent() {
        val backend = CountingIndexedBackend()
        val session = CognitiveConversationSessionId("corrupt-head-session")
        val store = openConversation(backend)

        assertIs<PersistentConversationAppendPairResult.Appended>(
            store.appendPair(
                session,
                msg(1, CognitiveConversationRole.USER, "hello"),
                msg(2, CognitiveConversationRole.ASSISTANT, "reply"),
                at(1)
            )
        )

        val headId = ConversationV3IndexCodec.headId(session)
        val head = assertNotNull(backend.entries[headId])
        val bytes = head.record.payload.copyBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        backend.entries[headId] = head.copy(
            record = head.record.copy(
                payload = pro.liliya.core.persistence.PersistentPayload(bytes)
            )
        )

        val reopened = openConversation(backend)
        val result = reopened.reopenResult(session)
        assertFalse(result is PersistentConversationReopenResult.Absent)
        assertTrue(
            result is PersistentConversationReopenResult.EncryptionUnavailable ||
                result is PersistentConversationReopenResult.Corrupt
        )
    }

    @Test
    fun missing_predecessor_before_working_tail_is_complete_fails_reopen_closed() {
        val backend = CountingIndexedBackend()
        val session = CognitiveConversationSessionId("truncated-v3-chain")
        val store = openConversation(backend, maxRetained = 6)

        assertIs<PersistentConversationAppendPairResult.Appended>(
            store.appendPair(
                session,
                msg(1, CognitiveConversationRole.USER, "u1"),
                msg(2, CognitiveConversationRole.ASSISTANT, "a1"),
                at(1)
            )
        )
        assertIs<PersistentConversationAppendPairResult.Appended>(
            store.appendPair(
                session,
                msg(3, CognitiveConversationRole.USER, "u2"),
                msg(4, CognitiveConversationRole.ASSISTANT, "a2"),
                at(2)
            )
        )

        backend.entries.remove(ConversationV3IndexCodec.chunkId(session, 1L))

        val reopened = openConversation(backend, maxRetained = 6)
        assertIs<PersistentConversationReopenResult.Corrupt>(
            reopened.reopenResult(session)
        )
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
    fun prepare_migration_installs_only_mixed_marker_without_scanning_or_deleting_legacy_rows() {
        val backend = CountingIndexedBackend()
        val encrypted = encryptedStore(backend)
        val session = CognitiveConversationSessionId("prepare-legacy-session")

        val payload = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x434E5632)
                val sessionBytes = session.value.encodeToByteArray()
                data.writeInt(sessionBytes.size)
                data.write(sessionBytes)
                data.writeInt(2)
                for ((sequence, role, content) in listOf(
                    Triple(1L, CognitiveConversationRole.USER, "old-user"),
                    Triple(2L, CognitiveConversationRole.ASSISTANT, "old-reply")
                )) {
                    data.writeLong(sequence)
                    data.writeInt(role.ordinal)
                    val bytes = content.encodeToByteArray()
                    data.writeInt(bytes.size)
                    data.write(bytes)
                }
            }
            output.toByteArray()
        }
        val legacyDigest = MessageDigest.getInstance("SHA-256")
            .digest((session.value + ":1").encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        val legacyId = PersistentEntityId("conversation-chunk-$legacyDigest")
        assertIs<CognitiveEncryptionResult.Success<*>>(
            encrypted.install(
                CognitivePersistentRecordDraft(
                    id = legacyId,
                    schemaId = PersistentSchemaId("cognitive-conversation-session"),
                    schemaVersion = PersistentSchemaVersion(2),
                    plaintext = CognitivePlaintext(payload),
                    createdAt = at(1),
                    dek = dekRef
                )
            )
        )
        val legacyBefore = assertNotNull(backend.entries[legacyId])

        backend.resetReadCounters()
        assertIs<PersistentConversationMigrationPrepareResult.Prepared>(
            EncryptedPersistentConversationStore.prepareMigration(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                persistedAt = at(2)
            )
        )

        assertEquals(0, backend.pageLoadCalls)
        assertEquals(
            legacyBefore,
            assertNotNull(backend.entries[legacyId])
        )
        assertTrue(
            backend.entries.containsKey(
                ConversationV3MigrationCodec.MIXED_MARKER_ID
            )
        )
        assertFalse(
            backend.entries.containsKey(ConversationV3IndexCodec.MARKER_ID)
        )

        backend.resetReadCounters()
        val mixed = assertIs<PersistentConversationOpenResult.Opened>(
            EncryptedPersistentConversationStore.open(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                maxRetainedMessages = 4,
                maxMessageChars = 1024
            )
        ).store
        val snapshot = assertNotNull(mixed.reopen(session))
        assertEquals(listOf("old-user", "old-reply"), snapshot.messages.map { it.content })
        assertEquals(0, backend.pageLoadCalls)
    }

    @Test
    fun prepare_migration_is_idempotent_and_rejects_native_or_empty_store() {
        val backend = CountingIndexedBackend()

        assertIs<PersistentConversationMigrationPrepareResult.Rejected>(
            EncryptedPersistentConversationStore.prepareMigration(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                persistedAt = at(1)
            )
        )

        val session = CognitiveConversationSessionId("prepare-idempotent-session")
        val payload = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x434E5632)
                val sessionBytes = session.value.encodeToByteArray()
                data.writeInt(sessionBytes.size)
                data.write(sessionBytes)
                data.writeInt(1)
                data.writeLong(1L)
                data.writeInt(CognitiveConversationRole.USER.ordinal)
                val bytes = "legacy".encodeToByteArray()
                data.writeInt(bytes.size)
                data.write(bytes)
            }
            output.toByteArray()
        }
        assertIs<CognitiveEncryptionResult.Success<*>>(
            encryptedStore(backend).install(
                CognitivePersistentRecordDraft(
                    id = PersistentEntityId(
                        "conversation-chunk-" +
                            MessageDigest.getInstance("SHA-256")
                                .digest((session.value + ":1").encodeToByteArray())
                                .joinToString("") { "%02x".format(it) }
                    ),
                    schemaId = PersistentSchemaId("cognitive-conversation-session"),
                    schemaVersion = PersistentSchemaVersion(2),
                    plaintext = CognitivePlaintext(payload),
                    createdAt = at(2),
                    dek = dekRef
                )
            )
        )

        assertIs<PersistentConversationMigrationPrepareResult.Prepared>(
            EncryptedPersistentConversationStore.prepareMigration(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                persistedAt = at(3)
            )
        )
        assertIs<PersistentConversationMigrationPrepareResult.AlreadyPrepared>(
            EncryptedPersistentConversationStore.prepareMigration(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                persistedAt = at(4)
            )
        )

        val nativeBackend = CountingIndexedBackend()
        openConversation(nativeBackend)
        assertIs<PersistentConversationMigrationPrepareResult.Rejected>(
            EncryptedPersistentConversationStore.prepareMigration(
                encryptedStore = encryptedStore(nativeBackend),
                activeDek = dekRef,
                persistedAt = at(5)
            )
        )
    }

    @Test
    fun mixed_mode_reopens_one_legacy_session_with_bounded_memory_and_no_global_scan() {
        val backend = CountingIndexedBackend()
        val encrypted = encryptedStore(backend)
        val sessionA = CognitiveConversationSessionId("mixed-legacy-A")
        val sessionB = CognitiveConversationSessionId("mixed-legacy-B")

        fun legacyChunkId(
            session: CognitiveConversationSessionId,
            firstSequence: Long
        ): PersistentEntityId {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((session.value + ":" + firstSequence).encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            return PersistentEntityId("conversation-chunk-$digest")
        }

        fun installChunk(
            session: CognitiveConversationSessionId,
            firstSequence: Long,
            userText: String,
            assistantText: String,
            second: Long
        ) {
            val payload = ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use { data ->
                    data.writeInt(0x434E5632)
                    val sessionBytes = session.value.encodeToByteArray()
                    data.writeInt(sessionBytes.size)
                    data.write(sessionBytes)
                    data.writeInt(2)
                    for ((sequence, role, content) in listOf(
                        Triple(
                            firstSequence,
                            CognitiveConversationRole.USER,
                            userText
                        ),
                        Triple(
                            firstSequence + 1L,
                            CognitiveConversationRole.ASSISTANT,
                            assistantText
                        )
                    )) {
                        data.writeLong(sequence)
                        data.writeInt(role.ordinal)
                        val bytes = content.encodeToByteArray()
                        data.writeInt(bytes.size)
                        data.write(bytes)
                    }
                }
                output.toByteArray()
            }
            assertIs<CognitiveEncryptionResult.Success<*>>(
                encrypted.install(
                    CognitivePersistentRecordDraft(
                        id = legacyChunkId(session, firstSequence),
                        schemaId = PersistentSchemaId(
                            "cognitive-conversation-session"
                        ),
                        schemaVersion = PersistentSchemaVersion(2),
                        plaintext = CognitivePlaintext(payload),
                        createdAt = at(second),
                        dek = dekRef
                    )
                )
            )
        }

        installChunk(sessionA, 1L, "a-u1", "a-r1", 1L)
        installChunk(sessionA, 3L, "a-u2", "a-r2", 2L)
        installChunk(sessionA, 5L, "a-u3", "a-r3", 3L)
        installChunk(sessionB, 1L, "b-u1", "b-r1", 4L)

        val mixedMarker = ConversationV3MigrationCodec.encodeMixedMarker(at(5))
        assertIs<CognitiveEncryptionResult.Success<*>>(
            encrypted.install(
                CognitivePersistentRecordDraft(
                    id = mixedMarker.id,
                    schemaId = mixedMarker.schemaId,
                    schemaVersion = mixedMarker.schemaVersion,
                    plaintext = CognitivePlaintext(mixedMarker.payload.copyBytes()),
                    createdAt = mixedMarker.createdAt,
                    dek = dekRef
                )
            )
        )

        backend.resetReadCounters()
        val mixed = assertIs<PersistentConversationOpenResult.Opened>(
            EncryptedPersistentConversationStore.open(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                maxRetainedMessages = 4,
                maxMessageChars = 1024
            )
        ).store

        val reopened = assertNotNull(mixed.reopen(sessionA))
        assertEquals(
            listOf(3L, 4L, 5L, 6L),
            reopened.messages.map { it.sequence.value }
        )
        assertEquals(0, backend.pageLoadCalls)
        assertFalse(backend.exactReadIds.contains(legacyChunkId(sessionB, 1L)))

        val history = assertIs<PersistentConversationHistoryResult.Found>(
            mixed.history(
                sessionId = sessionA,
                maxMessages = 10
            )
        ).snapshot
        assertEquals(
            (1L..6L).toList(),
            history.messages.map { it.sequence.value }
        )
        assertEquals(0, backend.pageLoadCalls)
        assertFalse(backend.exactReadIds.contains(legacyChunkId(sessionB, 1L)))

        assertIs<PersistentConversationAppendPairResult.Rejected>(
            mixed.appendPair(
                sessionA,
                msg(7L, CognitiveConversationRole.USER, "blocked-u"),
                msg(8L, CognitiveConversationRole.ASSISTANT, "blocked-r"),
                at(6)
            )
        )
    }

    @Test
    fun indexed_v2_archive_without_marker_reopens_through_legacy_fallback() {
        val backend = CountingIndexedBackend()
        val session = CognitiveConversationSessionId("indexed-v2-session")
        val payload = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x434E5632)
                val sessionBytes = session.value.encodeToByteArray()
                data.writeInt(sessionBytes.size)
                data.write(sessionBytes)
                data.writeInt(2)
                for ((sequence, role, content) in listOf(
                    Triple(1L, CognitiveConversationRole.USER, "old-user"),
                    Triple(2L, CognitiveConversationRole.ASSISTANT, "old-reply")
                )) {
                    data.writeLong(sequence)
                    data.writeInt(role.ordinal)
                    val contentBytes = content.encodeToByteArray()
                    data.writeInt(contentBytes.size)
                    data.write(contentBytes)
                }
            }
            output.toByteArray()
        }
        val chunkDigest = MessageDigest.getInstance("SHA-256")
            .digest((session.value + ":1").encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

        assertIs<CognitiveEncryptionResult.Success<*>>(
            encryptedStore(backend).install(
                CognitivePersistentRecordDraft(
                    id = PersistentEntityId("conversation-chunk-$chunkDigest"),
                    schemaId = PersistentSchemaId("cognitive-conversation-session"),
                    schemaVersion = PersistentSchemaVersion(2),
                    plaintext = CognitivePlaintext(payload),
                    createdAt = at(1),
                    dek = dekRef
                )
            )
        )

        backend.resetReadCounters()
        val reopened = openConversation(backend, maxRetained = 4)
        val snapshot = assertNotNull(reopened.reopen(session))
        assertEquals(listOf(1L, 2L), snapshot.messages.map { it.sequence.value })
        assertEquals(listOf("old-user", "old-reply"), snapshot.messages.map { it.content })
        assertFalse(backend.entries.containsKey(ConversationV3IndexCodec.MARKER_ID))
        assertTrue(backend.pageLoadCalls > 0)
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
    fun historical_chunk_respects_current_message_bound_when_read_lazily() {
        val backend = CountingIndexedBackend()
        val session = CognitiveConversationSessionId("historical-bound-v3")
        val writer = openConversation(backend, maxRetained = 2)

        assertIs<PersistentConversationAppendPairResult.Appended>(
            writer.appendPair(
                session,
                msg(1, CognitiveConversationRole.USER, "oversized"),
                msg(2, CognitiveConversationRole.ASSISTANT, "a1"),
                at(1)
            )
        )
        assertIs<PersistentConversationAppendPairResult.Appended>(
            writer.appendPair(
                session,
                msg(3, CognitiveConversationRole.USER, "u2"),
                msg(4, CognitiveConversationRole.ASSISTANT, "a2"),
                at(2)
            )
        )

        val reopened = assertIs<PersistentConversationOpenResult.Opened>(
            EncryptedPersistentConversationStore.open(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                maxRetainedMessages = 2,
                maxMessageChars = 4
            )
        ).store
        assertEquals(
            listOf(3L, 4L),
            assertNotNull(reopened.reopen(session)).messages.map { it.sequence.value }
        )
        assertIs<PersistentConversationHistoryResult.Corrupt>(
            reopened.history(
                sessionId = session,
                beforeSequenceExclusive = 3L,
                maxMessages = 2
            )
        )
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

    @Test
    fun mixed_v2_session_migrates_idempotently_without_deleting_source_rows() {
        val backend = CountingIndexedBackend()
        val encrypted = encryptedStore(backend)
        val session = CognitiveConversationSessionId("migrate-v2-session")

        fun legacyChunkId(firstSequence: Long): PersistentEntityId {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((session.value + ":" + firstSequence).encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            return PersistentEntityId("conversation-chunk-$digest")
        }

        fun installLegacyPair(
            firstSequence: Long,
            userText: String,
            assistantText: String,
            second: Long
        ) {
            val payload = ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use { data ->
                    data.writeInt(0x434E5632)
                    val sessionBytes = session.value.encodeToByteArray()
                    data.writeInt(sessionBytes.size)
                    data.write(sessionBytes)
                    data.writeInt(2)
                    for ((sequence, role, content) in listOf(
                        Triple(
                            firstSequence,
                            CognitiveConversationRole.USER,
                            userText
                        ),
                        Triple(
                            firstSequence + 1L,
                            CognitiveConversationRole.ASSISTANT,
                            assistantText
                        )
                    )) {
                        data.writeLong(sequence)
                        data.writeInt(role.ordinal)
                        val bytes = content.encodeToByteArray()
                        data.writeInt(bytes.size)
                        data.write(bytes)
                    }
                }
                output.toByteArray()
            }
            assertIs<CognitiveEncryptionResult.Success<*>>(
                encrypted.install(
                    CognitivePersistentRecordDraft(
                        id = legacyChunkId(firstSequence),
                        schemaId = PersistentSchemaId(
                            "cognitive-conversation-session"
                        ),
                        schemaVersion = PersistentSchemaVersion(2),
                        plaintext = CognitivePlaintext(payload),
                        createdAt = at(second),
                        dek = dekRef
                    )
                )
            )
        }

        installLegacyPair(1L, "u1", "a1", 1L)
        installLegacyPair(3L, "u2", "a2", 2L)
        val sourceOne = assertNotNull(backend.entries[legacyChunkId(1L)])
        val sourceThree = assertNotNull(backend.entries[legacyChunkId(3L)])

        assertIs<PersistentConversationMigrationPrepareResult.Prepared>(
            EncryptedPersistentConversationStore.prepareMigration(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                persistedAt = at(3)
            )
        )
        val mixed = assertIs<PersistentConversationOpenResult.Opened>(
            EncryptedPersistentConversationStore.open(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                maxRetainedMessages = 4,
                maxMessageChars = 1024
            )
        ).store

        backend.resetReadCounters()
        assertIs<PersistentConversationSessionMigrationResult.Migrated>(
            mixed.migrateV2Session(session, at(4))
        )
        assertEquals(0, backend.pageLoadCalls)
        assertEquals(sourceOne, assertNotNull(backend.entries[legacyChunkId(1L)]))
        assertEquals(sourceThree, assertNotNull(backend.entries[legacyChunkId(3L)]))
        assertTrue(
            backend.entries.containsKey(
                ConversationV3MigrationCodec.migrationLockId(session)
            )
        )
        assertTrue(
            backend.entries.containsKey(
                ConversationV3IndexCodec.headId(session)
            )
        )
        assertTrue(
            backend.entries.containsKey(
                ConversationV3MigrationCodec.migrationReceiptId(session)
            )
        )
        assertTrue(
            backend.entries.containsKey(
                ConversationV3IndexCodec.chunkId(session, 1L)
            )
        )
        assertTrue(
            backend.entries.containsKey(
                ConversationV3IndexCodec.chunkId(session, 3L)
            )
        )

        val reopened = assertNotNull(mixed.reopen(session))
        assertEquals(
            listOf(1L, 2L, 3L, 4L),
            reopened.messages.map { it.sequence.value }
        )
        assertIs<PersistentConversationSessionMigrationResult.AlreadyMigrated>(
            mixed.migrateV2Session(session, at(5))
        )

        backend.resetReadCounters()
        val proven =
            assertIs<PersistentConversationMigrationCompletenessResult.Proven>(
                mixed.proveMigrationCompleteness(
                    persistedAt = at(6),
                    pageSize = 2
                )
            ).proof
        assertEquals(2L, proven.legacyRecordCount)
        assertEquals(1L, proven.receiptCount)
        assertTrue(backend.pageLoadCalls > 1)
        assertTrue(
            backend.entries.containsKey(
                ConversationV3MigrationCodec.COMPLETENESS_PROOF_ID
            )
        )
        assertIs<
            PersistentConversationMigrationCompletenessResult.AlreadyProven
        >(
            mixed.proveMigrationCompleteness(
                persistedAt = at(7),
                pageSize = 2
            )
        )

        val mixedMarkerBefore = assertNotNull(
            backend.entries[
                ConversationV3MigrationCodec.MIXED_MARKER_ID
            ]
        )
        assertIs<PersistentConversationMigrationFinalizationResult.Finalized>(
            mixed.finalizeMigrationToNative(at(8))
        )
        assertFalse(
            backend.entries.containsKey(
                ConversationV3MigrationCodec.MIXED_MARKER_ID
            )
        )
        val nativeMarker = assertNotNull(
            backend.entries[ConversationV3IndexCodec.MARKER_ID]
        )
        assertEquals(
            mixedMarkerBefore.generation,
            nativeMarker.generation
        )
        assertEquals(
            sourceOne,
            assertNotNull(backend.entries[legacyChunkId(1L)])
        )
        assertEquals(
            sourceThree,
            assertNotNull(backend.entries[legacyChunkId(3L)])
        )

        val native = openConversation(backend, maxRetained = 6)
        assertEquals(
            listOf(1L, 2L, 3L, 4L),
            assertNotNull(native.reopen(session)).messages.map {
                it.sequence.value
            }
        )
        assertIs<PersistentConversationAppendPairResult.Appended>(
            native.appendPair(
                session,
                msg(5L, CognitiveConversationRole.USER, "u5"),
                msg(6L, CognitiveConversationRole.ASSISTANT, "a6"),
                at(9)
            )
        )
        assertIs<
            PersistentConversationMigrationFinalizationResult.AlreadyFinalized
        >(
            native.finalizeMigrationToNative(at(10))
        )
    }

    @Test
    fun native_finalization_requires_fresh_completeness_proof() {
        val backend = CountingIndexedBackend()
        val encrypted = encryptedStore(backend)
        val session = CognitiveConversationSessionId("finalization-proof-session")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((session.value + ":1").encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        val sourceId = PersistentEntityId("conversation-chunk-$digest")
        val payload = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x434E5632)
                val sessionBytes = session.value.encodeToByteArray()
                data.writeInt(sessionBytes.size)
                data.write(sessionBytes)
                data.writeInt(2)
                for ((sequence, role, content) in listOf(
                    Triple(1L, CognitiveConversationRole.USER, "u1"),
                    Triple(2L, CognitiveConversationRole.ASSISTANT, "a2")
                )) {
                    data.writeLong(sequence)
                    data.writeInt(role.ordinal)
                    val bytes = content.encodeToByteArray()
                    data.writeInt(bytes.size)
                    data.write(bytes)
                }
            }
            output.toByteArray()
        }
        assertIs<CognitiveEncryptionResult.Success<*>>(
            encrypted.install(
                CognitivePersistentRecordDraft(
                    id = sourceId,
                    schemaId = PersistentSchemaId(
                        "cognitive-conversation-session"
                    ),
                    schemaVersion = PersistentSchemaVersion(2),
                    plaintext = CognitivePlaintext(payload),
                    createdAt = at(1),
                    dek = dekRef
                )
            )
        )
        assertIs<PersistentConversationMigrationPrepareResult.Prepared>(
            EncryptedPersistentConversationStore.prepareMigration(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                persistedAt = at(2)
            )
        )
        val mixed = assertIs<PersistentConversationOpenResult.Opened>(
            EncryptedPersistentConversationStore.open(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                maxRetainedMessages = 4,
                maxMessageChars = 1024
            )
        ).store
        assertIs<PersistentConversationSessionMigrationResult.Migrated>(
            mixed.migrateV2Session(session, at(3))
        )

        assertIs<PersistentConversationMigrationFinalizationResult.MissingProof>(
            mixed.finalizeMigrationToNative(at(4))
        )
        assertIs<PersistentConversationMigrationCompletenessResult.Proven>(
            mixed.proveMigrationCompleteness(
                persistedAt = at(5),
                pageSize = 2
            )
        )

        val mutationPayload = byteArrayOf(1, 2, 3, 4)
        assertIs<CognitiveEncryptionResult.Success<*>>(
            encryptedStore(backend).install(
                CognitivePersistentRecordDraft(
                    id = PersistentEntityId("post-proof-mutation"),
                    schemaId = PersistentSchemaId("test-post-proof"),
                    schemaVersion = PersistentSchemaVersion(1),
                    plaintext = CognitivePlaintext(mutationPayload),
                    createdAt = at(6),
                    dek = dekRef
                )
            )
        )
        assertIs<PersistentConversationMigrationFinalizationResult.StaleProof>(
            mixed.finalizeMigrationToNative(at(7))
        )
        assertTrue(
            backend.entries.containsKey(
                ConversationV3MigrationCodec.MIXED_MARKER_ID
            )
        )
        assertFalse(
            backend.entries.containsKey(
                ConversationV3IndexCodec.MARKER_ID
            )
        )
    }

    @Test
    fun migration_receipt_is_recovered_after_head_publish_receipt_conflict() {
        val backend = CountingIndexedBackend()
        val encrypted = encryptedStore(backend)
        val session = CognitiveConversationSessionId("receipt-recovery-session")

        val digest = MessageDigest.getInstance("SHA-256")
            .digest((session.value + ":1").encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        val sourceId = PersistentEntityId("conversation-chunk-$digest")
        val payload = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x434E5632)
                val sessionBytes = session.value.encodeToByteArray()
                data.writeInt(sessionBytes.size)
                data.write(sessionBytes)
                data.writeInt(2)
                for ((sequence, role, content) in listOf(
                    Triple(1L, CognitiveConversationRole.USER, "receipt-u1"),
                    Triple(2L, CognitiveConversationRole.ASSISTANT, "receipt-a2")
                )) {
                    data.writeLong(sequence)
                    data.writeInt(role.ordinal)
                    val bytes = content.encodeToByteArray()
                    data.writeInt(bytes.size)
                    data.write(bytes)
                }
            }
            output.toByteArray()
        }
        assertIs<CognitiveEncryptionResult.Success<*>>(
            encrypted.install(
                CognitivePersistentRecordDraft(
                    id = sourceId,
                    schemaId = PersistentSchemaId("cognitive-conversation-session"),
                    schemaVersion = PersistentSchemaVersion(2),
                    plaintext = CognitivePlaintext(payload),
                    createdAt = at(1),
                    dek = dekRef
                )
            )
        )
        assertIs<PersistentConversationMigrationPrepareResult.Prepared>(
            EncryptedPersistentConversationStore.prepareMigration(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                persistedAt = at(2)
            )
        )
        val mixed = assertIs<PersistentConversationOpenResult.Opened>(
            EncryptedPersistentConversationStore.open(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                maxRetainedMessages = 4,
                maxMessageChars = 1024
            )
        ).store

        backend.failNextReceiptInstall = true
        assertIs<PersistentConversationSessionMigrationResult.Rejected>(
            mixed.migrateV2Session(session, at(3))
        )
        assertTrue(
            backend.entries.containsKey(
                ConversationV3IndexCodec.headId(session)
            )
        )
        assertFalse(
            backend.entries.containsKey(
                ConversationV3MigrationCodec.migrationReceiptId(session)
            )
        )

        backend.resetReadCounters()
        assertIs<PersistentConversationSessionMigrationResult.AlreadyMigrated>(
            mixed.migrateV2Session(session, at(4))
        )
        assertTrue(
            backend.entries.containsKey(
                ConversationV3MigrationCodec.migrationReceiptId(session)
            )
        )
        assertTrue(backend.exactReadIds.contains(sourceId))
        assertEquals(0, backend.pageLoadCalls)
    }

    @Test
    fun migration_lock_keeps_partial_v3_orphans_invisible_until_head_is_published() {
        val backend = CountingIndexedBackend()
        val encrypted = encryptedStore(backend)
        val session = CognitiveConversationSessionId("locked-partial-migration")

        fun legacyChunkId(firstSequence: Long): PersistentEntityId {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((session.value + ":" + firstSequence).encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            return PersistentEntityId("conversation-chunk-$digest")
        }

        fun installLegacyPair(
            firstSequence: Long,
            userText: String,
            assistantText: String,
            second: Long
        ) {
            val payload = ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use { data ->
                    data.writeInt(0x434E5632)
                    val sessionBytes = session.value.encodeToByteArray()
                    data.writeInt(sessionBytes.size)
                    data.write(sessionBytes)
                    data.writeInt(2)
                    for ((sequence, role, content) in listOf(
                        Triple(firstSequence, CognitiveConversationRole.USER, userText),
                        Triple(firstSequence + 1L, CognitiveConversationRole.ASSISTANT, assistantText)
                    )) {
                        data.writeLong(sequence)
                        data.writeInt(role.ordinal)
                        val bytes = content.encodeToByteArray()
                        data.writeInt(bytes.size)
                        data.write(bytes)
                    }
                }
                output.toByteArray()
            }
            assertIs<CognitiveEncryptionResult.Success<*>>(
                encrypted.install(
                    CognitivePersistentRecordDraft(
                        id = legacyChunkId(firstSequence),
                        schemaId = PersistentSchemaId(
                            "cognitive-conversation-session"
                        ),
                        schemaVersion = PersistentSchemaVersion(2),
                        plaintext = CognitivePlaintext(payload),
                        createdAt = at(second),
                        dek = dekRef
                    )
                )
            )
        }

        installLegacyPair(1L, "legacy-u1", "legacy-a1", 1L)
        installLegacyPair(3L, "legacy-u2", "legacy-a2", 2L)
        assertIs<PersistentConversationMigrationPrepareResult.Prepared>(
            EncryptedPersistentConversationStore.prepareMigration(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                persistedAt = at(3)
            )
        )

        val lock = ConversationV3MigrationCodec.encodeMigrationLock(
            ConversationV3MigrationLock(session),
            at(4)
        )
        assertIs<CognitiveEncryptionResult.Success<*>>(
            encryptedStore(backend).install(
                CognitivePersistentRecordDraft(
                    id = lock.id,
                    schemaId = lock.schemaId,
                    schemaVersion = lock.schemaVersion,
                    plaintext = CognitivePlaintext(lock.payload.copyBytes()),
                    createdAt = lock.createdAt,
                    dek = dekRef
                )
            )
        )

        val orphan = ConversationV3IndexCodec.encodeChunk(
            ConversationV3LinkedChunk(
                snapshot = CognitiveConversationContextSnapshot(
                    session,
                    listOf(
                        msg(1L, CognitiveConversationRole.USER, "legacy-u1"),
                        msg(2L, CognitiveConversationRole.ASSISTANT, "legacy-a1")
                    )
                ),
                previousChunkId = null
            ),
            at(5)
        )
        assertIs<CognitiveEncryptionResult.Success<*>>(
            encryptedStore(backend).install(
                CognitivePersistentRecordDraft(
                    id = orphan.id,
                    schemaId = orphan.schemaId,
                    schemaVersion = orphan.schemaVersion,
                    plaintext = CognitivePlaintext(orphan.payload.copyBytes()),
                    createdAt = orphan.createdAt,
                    dek = dekRef
                )
            )
        )

        backend.resetReadCounters()
        val mixed = assertIs<PersistentConversationOpenResult.Opened>(
            EncryptedPersistentConversationStore.open(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                maxRetainedMessages = 4,
                maxMessageChars = 1024
            )
        ).store
        val snapshot = assertNotNull(mixed.reopen(session))
        assertEquals(
            listOf("legacy-u1", "legacy-a1", "legacy-u2", "legacy-a2"),
            snapshot.messages.map { it.content }
        )
        assertFalse(
            backend.entries.containsKey(ConversationV3IndexCodec.headId(session))
        )
        assertEquals(0, backend.pageLoadCalls)

        val incomplete =
            assertIs<
                PersistentConversationMigrationCompletenessResult.Incomplete
            >(
                mixed.proveMigrationCompleteness(
                    persistedAt = at(6),
                    pageSize = 2
                )
            )
        assertTrue(incomplete.reason.contains("not accounted"))
        assertFalse(
            backend.entries.containsKey(
                ConversationV3MigrationCodec.COMPLETENESS_PROOF_ID
            )
        )
    }

    @Test
    fun truncated_v1_session_migrates_without_renumbering_or_deleting_source() {
        val backend = CountingIndexedBackend()
        val encrypted = encryptedStore(backend)
        val session = CognitiveConversationSessionId("truncated-v1-session")

        fun legacyRootId(): PersistentEntityId {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(session.value.encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            return PersistentEntityId("conversation-$digest")
        }

        fun legacyChunkId(firstSequence: Long): PersistentEntityId {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((session.value + ":" + firstSequence).encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            return PersistentEntityId("conversation-chunk-$digest")
        }

        val legacyPayload = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x434E5631)
                val sessionBytes = session.value.encodeToByteArray()
                data.writeInt(sessionBytes.size)
                data.write(sessionBytes)
                data.writeInt(4)
                for ((sequence, role, content) in listOf(
                    Triple(57L, CognitiveConversationRole.USER, "old-u57"),
                    Triple(58L, CognitiveConversationRole.ASSISTANT, "old-a58"),
                    Triple(59L, CognitiveConversationRole.USER, "old-u59"),
                    Triple(60L, CognitiveConversationRole.ASSISTANT, "old-a60")
                )) {
                    data.writeLong(sequence)
                    data.writeInt(role.ordinal)
                    val bytes = content.encodeToByteArray()
                    data.writeInt(bytes.size)
                    data.write(bytes)
                }
            }
            output.toByteArray()
        }
        assertIs<CognitiveEncryptionResult.Success<*>>(
            encrypted.install(
                CognitivePersistentRecordDraft(
                    id = legacyRootId(),
                    schemaId = PersistentSchemaId("cognitive-conversation-session"),
                    schemaVersion = PersistentSchemaVersion(1),
                    plaintext = CognitivePlaintext(legacyPayload),
                    createdAt = at(1),
                    dek = dekRef
                )
            )
        )

        val continuationPayload = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x434E5632)
                val sessionBytes = session.value.encodeToByteArray()
                data.writeInt(sessionBytes.size)
                data.write(sessionBytes)
                data.writeInt(2)
                for ((sequence, role, content) in listOf(
                    Triple(61L, CognitiveConversationRole.USER, "new-u61"),
                    Triple(62L, CognitiveConversationRole.ASSISTANT, "new-a62")
                )) {
                    data.writeLong(sequence)
                    data.writeInt(role.ordinal)
                    val bytes = content.encodeToByteArray()
                    data.writeInt(bytes.size)
                    data.write(bytes)
                }
            }
            output.toByteArray()
        }
        assertIs<CognitiveEncryptionResult.Success<*>>(
            encrypted.install(
                CognitivePersistentRecordDraft(
                    id = legacyChunkId(61L),
                    schemaId = PersistentSchemaId("cognitive-conversation-session"),
                    schemaVersion = PersistentSchemaVersion(2),
                    plaintext = CognitivePlaintext(continuationPayload),
                    createdAt = at(2),
                    dek = dekRef
                )
            )
        )

        val sourceRoot = assertNotNull(backend.entries[legacyRootId()])
        val sourceContinuation = assertNotNull(backend.entries[legacyChunkId(61L)])

        assertIs<PersistentConversationMigrationPrepareResult.Prepared>(
            EncryptedPersistentConversationStore.prepareMigration(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                persistedAt = at(3)
            )
        )
        val mixed = assertIs<PersistentConversationOpenResult.Opened>(
            EncryptedPersistentConversationStore.open(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                maxRetainedMessages = 8,
                maxMessageChars = 1024
            )
        ).store

        backend.resetReadCounters()
        assertIs<PersistentConversationSessionMigrationResult.Migrated>(
            mixed.migrateV1TruncatedSession(session, at(4))
        )

        assertEquals(0, backend.pageLoadCalls)
        assertEquals(sourceRoot, assertNotNull(backend.entries[legacyRootId()]))
        assertEquals(
            sourceContinuation,
            assertNotNull(backend.entries[legacyChunkId(61L)])
        )
        assertTrue(
            backend.entries.containsKey(
                ConversationV3MigrationCodec.truncatedRootId(session)
            )
        )
        assertTrue(
            backend.entries.containsKey(
                ConversationV3MigrationCodec.migrationReceiptId(session)
            )
        )
        assertEquals(
            ConversationV3MigrationCodec.TRUNCATED_ROOT_CHUNK_SCHEMA_ID,
            assertNotNull(
                backend.entries[
                    ConversationV3IndexCodec.chunkId(session, 57L)
                ]
            ).record.schemaId
        )

        val reopened = assertNotNull(mixed.reopen(session))
        assertEquals(
            listOf(57L, 58L, 59L, 60L, 61L, 62L),
            reopened.messages.map { it.sequence.value }
        )
        val history = assertIs<PersistentConversationHistoryResult.Found>(
            mixed.history(
                sessionId = session,
                maxMessages = 10
            )
        ).snapshot
        assertEquals(
            listOf(57L, 58L, 59L, 60L, 61L, 62L),
            history.messages.map { it.sequence.value }
        )
        assertFalse(history.messages.any { it.sequence.value < 57L })

        assertIs<PersistentConversationSessionMigrationResult.AlreadyMigrated>(
            mixed.migrateV1TruncatedSession(session, at(5))
        )
    }

    @Test
    fun migrated_truncated_root_requires_its_authenticated_boundary() {
        val backend = CountingIndexedBackend()
        val encrypted = encryptedStore(backend)
        val session = CognitiveConversationSessionId("truncated-boundary-required")
        val rootId = PersistentEntityId(
            "conversation-" +
                MessageDigest.getInstance("SHA-256")
                    .digest(session.value.encodeToByteArray())
                    .joinToString("") { "%02x".format(it) }
        )

        val payload = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x434E5631)
                val sessionBytes = session.value.encodeToByteArray()
                data.writeInt(sessionBytes.size)
                data.write(sessionBytes)
                data.writeInt(2)
                for ((sequence, role, content) in listOf(
                    Triple(9L, CognitiveConversationRole.USER, "u9"),
                    Triple(10L, CognitiveConversationRole.ASSISTANT, "a10")
                )) {
                    data.writeLong(sequence)
                    data.writeInt(role.ordinal)
                    val bytes = content.encodeToByteArray()
                    data.writeInt(bytes.size)
                    data.write(bytes)
                }
            }
            output.toByteArray()
        }
        assertIs<CognitiveEncryptionResult.Success<*>>(
            encrypted.install(
                CognitivePersistentRecordDraft(
                    id = rootId,
                    schemaId = PersistentSchemaId("cognitive-conversation-session"),
                    schemaVersion = PersistentSchemaVersion(1),
                    plaintext = CognitivePlaintext(payload),
                    createdAt = at(1),
                    dek = dekRef
                )
            )
        )
        assertIs<PersistentConversationMigrationPrepareResult.Prepared>(
            EncryptedPersistentConversationStore.prepareMigration(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                persistedAt = at(2)
            )
        )
        val mixed = assertIs<PersistentConversationOpenResult.Opened>(
            EncryptedPersistentConversationStore.open(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                maxRetainedMessages = 4,
                maxMessageChars = 1024
            )
        ).store
        assertIs<PersistentConversationSessionMigrationResult.Migrated>(
            mixed.migrateV1TruncatedSession(session, at(3))
        )

        backend.entries.remove(
            ConversationV3MigrationCodec.truncatedRootId(session)
        )
        val reconstructed = assertIs<PersistentConversationOpenResult.Opened>(
            EncryptedPersistentConversationStore.open(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                maxRetainedMessages = 4,
                maxMessageChars = 1024
            )
        ).store
        assertIs<PersistentConversationReopenResult.Corrupt>(
            reconstructed.reopenResult(session)
        )
    }

    @Test
    fun concurrent_native_marker_initialization_refreshes_and_accepts_committed_marker() {
        val backend = CountingIndexedBackend().apply {
            raceNextNativeMarkerInstall = true
        }

        val opened = openConversation(backend)

        assertEquals(1, backend.entries.size)
        assertTrue(
            backend.entries.containsKey(ConversationV3IndexCodec.MARKER_ID)
        )
        assertEquals(
            PersistentConversationReopenResult.Absent,
            opened.reopenResult(
                CognitiveConversationSessionId("marker-race-session")
            )
        )
        assertEquals(0, backend.fullLoadCalls)
        assertEquals(0, backend.fullCommitCalls)
    }

    @Test
    fun external_writer_conflict_requires_explicit_refresh_and_fresh_head_read() {
        val backend = CountingIndexedBackend()
        val first = openConversation(backend)
        val stale = openConversation(backend)
        val session = CognitiveConversationSessionId("external-writer-refresh")

        assertIs<PersistentConversationAppendPairResult.Appended>(
            first.appendPair(
                session,
                msg(1, CognitiveConversationRole.USER, "u1"),
                msg(2, CognitiveConversationRole.ASSISTANT, "a2"),
                at(1)
            )
        )

        assertIs<PersistentConversationAppendPairResult.Rejected>(
            stale.appendPair(
                session,
                msg(3, CognitiveConversationRole.USER, "u3"),
                msg(4, CognitiveConversationRole.ASSISTANT, "a4"),
                at(2)
            )
        )

        assertIs<PersistentConversationConflictRefreshResult.Refreshed>(
            stale.refreshAfterExternalWriterConflict(session)
        )
        val fresh = assertIs<PersistentConversationReopenResult.Found>(
            stale.reopenResult(session)
        ).snapshot
        assertEquals(listOf(1L, 2L), fresh.messages.map { it.sequence.value })

        val appended = assertIs<PersistentConversationAppendPairResult.Appended>(
            stale.appendPair(
                session,
                msg(3, CognitiveConversationRole.USER, "u3"),
                msg(4, CognitiveConversationRole.ASSISTANT, "a4"),
                at(3)
            )
        )
        assertEquals(
            listOf(1L, 2L, 3L, 4L),
            appended.snapshot.messages.map { it.sequence.value }
        )
    }

    @Test
    fun indexed_native_store_rejects_retention_below_one_complete_pair() {
        val backend = CountingIndexedBackend()
        val opened = EncryptedPersistentConversationStore.open(
            encryptedStore = encryptedStore(backend),
            activeDek = dekRef,
            maxRetainedMessages = 1,
            maxMessageChars = 1024
        )

        val incompatible = assertIs<PersistentConversationOpenResult.Incompatible>(opened)
        assertTrue(incompatible.reason.contains("at least two retained messages"))
        assertTrue(backend.exactReadIds.isEmpty())
        assertEquals(0, backend.fullLoadCalls)
        assertEquals(0, backend.fullCommitCalls)
        assertTrue(backend.entries.isEmpty())
    }

    private fun containsSubsequence(
        haystack: ByteArray,
        needle: ByteArray
    ): Boolean {
        if (needle.isEmpty()) return true
        if (needle.size > haystack.size) return false
        for (start in 0..haystack.size - needle.size) {
            if (needle.indices.all { offset ->
                    haystack[start + offset] == needle[offset]
                }
            ) {
                return true
            }
        }
        return false
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
        var failNextReceiptInstall = false
        var raceNextNativeMarkerInstall = false
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
            if (
                raceNextNativeMarkerInstall &&
                entry.record.id == ConversationV3IndexCodec.MARKER_ID
            ) {
                raceNextNativeMarkerInstall = false
                entries[entry.record.id] =
                    PersistentRecordSnapshot(entry.record, entry.generation)
                revision += 1L
                highWatermark = entry.generation.value
                return PersistentBackendMutationResult.Conflict
            }
            if (
                failNextReceiptInstall &&
                entry.record.id.value.startsWith(
                    "conversation-v3-migration-receipt-"
                )
            ) {
                failNextReceiptInstall = false
                return PersistentBackendMutationResult.Conflict
            }
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
