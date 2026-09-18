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
import kotlin.test.assertNull
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
import pro.liliya.core.encryption.CognitivePersistentRecordDraft
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentBackendLoadResult
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

class EncryptedPersistentConversationStoreContractTest {
    private val storeId = PersistentStoreId("conversation-continuity")
    private val profile = CognitiveEncryptionProfile.AES_256_GCM
    private val dekRef = CognitiveDekReference(CognitiveDekId("conversation-dek"), CognitiveDekGeneration(1))
    private val material = CognitiveDekMaterial(ByteArray(32) { (it * 11 + 7).toByte() })

    @Test
    fun accepted_turns_reopen_as_bounded_tail_with_full_encrypted_history() {
        val backend = InMemoryPersistentRecordBackend()
        val first = openConversation(backend, maxRetained = 3)
        val session = CognitiveConversationSessionId("private-session-A")

        assertIs<PersistentConversationAppendResult.Appended>(first.append(session, msg(1, CognitiveConversationRole.USER, "one"), at(1)))
        assertIs<PersistentConversationAppendResult.Appended>(first.append(session, msg(2, CognitiveConversationRole.ASSISTANT, "two"), at(2)))
        assertIs<PersistentConversationAppendResult.Appended>(first.append(session, msg(3, CognitiveConversationRole.USER, "three"), at(3)))
        assertIs<PersistentConversationAppendResult.Appended>(first.append(session, msg(4, CognitiveConversationRole.ASSISTANT, "four"), at(4)))

        val reopened = openConversation(backend, maxRetained = 3)
        val snapshot = reopened.reopen(session)!!
        assertEquals(listOf(2L, 3L, 4L), snapshot.messages.map { it.sequence.value })
        assertEquals(listOf("two", "three", "four"), snapshot.messages.map { it.content })
        val history = assertIs<PersistentConversationHistoryResult.Found>(
            reopened.history(session, maxMessages = 10)
        ).snapshot
        assertEquals(listOf("one", "two", "three", "four"), history.messages.map { it.content })
        assertEquals(1, reopened.sessionCount())
    }

    @Test
    fun pair_archive_survives_restart_and_supports_backward_pages_without_expanding_context() {
        val backend = InMemoryPersistentRecordBackend()
        val session = CognitiveConversationSessionId("long-private-session")
        val first = openConversation(backend, maxRetained = 4)
        repeat(20) { turn ->
            val sequence = turn.toLong() * 2L + 1L
            assertIs<PersistentConversationAppendPairResult.Appended>(
                first.appendPair(
                    session,
                    msg(sequence, CognitiveConversationRole.USER, "user-$turn"),
                    msg(sequence + 1L, CognitiveConversationRole.ASSISTANT, "reply-$turn"),
                    at(sequence)
                )
            )
        }
        assertEquals(listOf(37L, 38L, 39L, 40L), first.reopen(session)!!.messages.map { it.sequence.value })

        val reopened = openConversation(backend, maxRetained = 4)
        assertEquals(listOf(37L, 38L, 39L, 40L), reopened.reopen(session)!!.messages.map { it.sequence.value })
        val newest = assertIs<PersistentConversationHistoryResult.Found>(
            reopened.history(session, maxMessages = 6)
        ).snapshot.messages
        assertEquals((35L..40L).toList(), newest.map { it.sequence.value })
        val older = assertIs<PersistentConversationHistoryResult.Found>(
            reopened.history(session, beforeSequenceExclusive = 35L, maxMessages = 4)
        ).snapshot.messages
        assertEquals((31L..34L).toList(), older.map { it.sequence.value })
        val oldest = assertIs<PersistentConversationHistoryResult.Found>(
            reopened.history(session, beforeSequenceExclusive = 5L, maxMessages = 4)
        ).snapshot.messages
        assertEquals((1L..4L).toList(), oldest.map { it.sequence.value })
        assertEquals(4, reopened.reopen(session)!!.messages.size)
        assertEquals(20, assertIs<PersistentBackendLoadResult.Loaded>(backend.load(storeId)).state.entries.size)
    }

    @Test
    fun legacy_bounded_tail_remains_readable_and_new_turns_extend_archive() {
        val backend = InMemoryPersistentRecordBackend()
        val session = CognitiveConversationSessionId("old-format-session")
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x434E5631)
                val idBytes = session.value.encodeToByteArray()
                data.writeInt(idBytes.size)
                data.write(idBytes)
                data.writeInt(2)
                for ((sequence, role, content) in listOf(
                    Triple(5L, CognitiveConversationRole.USER, "legacy-user"),
                    Triple(6L, CognitiveConversationRole.ASSISTANT, "legacy-reply")
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
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(session.value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        assertIs<CognitiveEncryptionResult.Success<*>>(
            encryptedStore(backend, resolver(material)).install(
                CognitivePersistentRecordDraft(
                    PersistentEntityId("conversation-$digest"),
                    PersistentSchemaId("cognitive-conversation-session"),
                    PersistentSchemaVersion(1), CognitivePlaintext(bytes), at(1), dekRef
                )
            )
        )

        val upgraded = openConversation(backend, maxRetained = 4)
        assertIs<PersistentConversationAppendPairResult.Appended>(
            upgraded.appendPair(
                session,
                msg(7, CognitiveConversationRole.USER, "new-user"),
                msg(8, CognitiveConversationRole.ASSISTANT, "new-reply"), at(2)
            )
        )
        val reopened = openConversation(backend, maxRetained = 4)
        assertEquals(listOf(5L, 6L, 7L, 8L), reopened.reopen(session)!!.messages.map { it.sequence.value })
        assertEquals(listOf("legacy-user", "legacy-reply"),
            assertIs<PersistentConversationHistoryResult.Found>(
                reopened.history(session, beforeSequenceExclusive = 7L, maxMessages = 2)
            ).snapshot.messages.map { it.content }
        )
    }

    @Test
    fun exact_duplicate_is_idempotent_but_conflict_and_gap_fail_closed() {
        val backend = InMemoryPersistentRecordBackend()
        val store = openConversation(backend, maxRetained = 4)
        val session = CognitiveConversationSessionId("session-B")
        val first = msg(1, CognitiveConversationRole.USER, "hello")

        assertIs<PersistentConversationAppendResult.Appended>(store.append(session, first, at(1)))
        assertIs<PersistentConversationAppendResult.AlreadyPresent>(store.append(session, first, at(2)))
        assertIs<PersistentConversationAppendResult.Rejected>(
            store.append(session, msg(1, CognitiveConversationRole.USER, "changed"), at(3))
        )
        assertIs<PersistentConversationAppendResult.Rejected>(
            store.append(session, msg(3, CognitiveConversationRole.ASSISTANT, "gap"), at(4))
        )
        assertEquals(listOf(1L), store.reopen(session)!!.messages.map { it.sequence.value })
    }

    @Test
    fun transcript_and_session_id_are_not_plaintext_in_durable_backend() {
        val backend = InMemoryPersistentRecordBackend()
        val store = openConversation(backend, maxRetained = 4)
        val session = CognitiveConversationSessionId("sensitive-session-name")
        val secret = "highly private transcript phrase"

        assertIs<PersistentConversationAppendResult.Appended>(
            store.append(session, msg(1, CognitiveConversationRole.USER, secret), at(1))
        )

        val loaded = assertIs<PersistentBackendLoadResult.Loaded>(backend.load(storeId))
        assertEquals(1, loaded.state.entries.size)
        val durable = loaded.state.entries.values.single()
        val payload = durable.record.payload.copyBytes()
        assertFalse(containsSubsequence(payload, secret.encodeToByteArray()))
        assertFalse(containsSubsequence(payload, session.value.encodeToByteArray()))
        assertFalse(durable.record.id.value.contains(session.value))
    }

    @Test
    fun authenticated_transcript_tamper_fails_reopen_closed() {
        val backend = InMemoryPersistentRecordBackend()
        val store = openConversation(backend, maxRetained = 4)
        val session = CognitiveConversationSessionId("session-tamper")
        assertIs<PersistentConversationAppendResult.Appended>(
            store.append(session, msg(1, CognitiveConversationRole.USER, "authenticated transcript"), at(1))
        )

        val current = assertIs<PersistentBackendLoadResult.Loaded>(backend.load(storeId))
        val (id, entry) = current.state.entries.entries.single().let { it.key to it.value }
        val bytes = entry.record.payload.copyBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        val tampered = current.state.copy(
            entries = current.state.entries + (
                id to entry.copy(
                    record = entry.record.copy(
                        payload = pro.liliya.core.persistence.PersistentPayload(bytes)
                    )
                )
            )
        )
        backend.forceLoad(storeId, PersistentBackendLoadResult.Loaded(current.revision, tampered))

        val reopened = EncryptedPersistentConversationStore.open(
            encryptedStore(backend, resolver(material)),
            dekRef,
            maxRetainedMessages = 4,
            maxMessageChars = 1024
        )
        val rejected = assertIs<PersistentConversationOpenResult.EncryptionUnavailable>(reopened)
        assertTrue(
            rejected.category == CognitiveEncryptionFailureCategory.CIPHERTEXT_AUTHENTICATION_FAILED ||
                rejected.category == CognitiveEncryptionFailureCategory.MALFORMED_ENVELOPE
        )
    }

    @Test
    fun missing_dek_fails_reopen_closed_instead_of_returning_empty_history() {
        val backend = InMemoryPersistentRecordBackend()
        val store = openConversation(backend, maxRetained = 4)
        val session = CognitiveConversationSessionId("session-C")
        assertIs<PersistentConversationAppendResult.Appended>(
            store.append(session, msg(1, CognitiveConversationRole.USER, "must survive"), at(1))
        )

        val unavailable = EncryptedPersistentConversationStore.open(
            encryptedStore(
                backend,
                object : CognitiveDekMaterialResolver {
                    override fun resolve(reference: CognitiveDekReference): CognitiveEncryptionResult<CognitiveDekMaterial> =
                        CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.DEK_MISSING)
                }
            ),
            dekRef,
            maxRetainedMessages = 4,
            maxMessageChars = 1024
        )
        assertIs<PersistentConversationOpenResult.EncryptionUnavailable>(unavailable)
    }

    @Test
    fun typed_reopen_matches_legacy_nullable_contract_before_lazy_restore() {
        val backend = InMemoryPersistentRecordBackend()
        val store = openConversation(backend, maxRetained = 4)
        val session = CognitiveConversationSessionId("typed-reopen")

        assertEquals(
            PersistentConversationReopenResult.Absent,
            store.reopenResult(session)
        )
        assertNull(store.reopen(session))

        assertIs<PersistentConversationAppendResult.Appended>(
            store.append(
                session,
                msg(1, CognitiveConversationRole.USER, "hello"),
                at(1)
            )
        )
        val found = assertIs<PersistentConversationReopenResult.Found>(
            store.reopenResult(session)
        )
        assertEquals(listOf(1L), found.snapshot.messages.map { it.sequence.value })
        assertEquals(found.snapshot, store.reopen(session))
    }

    @Test
    fun unknown_session_reopen_is_absent_and_does_not_manufacture_state() {
        val backend = InMemoryPersistentRecordBackend()
        val store = openConversation(backend, maxRetained = 4)
        assertNull(store.reopen(CognitiveConversationSessionId("never-created")))
        assertEquals(0, store.sessionCount())
    }

    private fun openConversation(
        backend: InMemoryPersistentRecordBackend,
        maxRetained: Int
    ): EncryptedPersistentConversationStore = assertIs<PersistentConversationOpenResult.Opened>(
        EncryptedPersistentConversationStore.open(
            encryptedStore(backend, resolver(material)),
            dekRef,
            maxRetainedMessages = maxRetained,
            maxMessageChars = 1024
        )
    ).store

    private fun encryptedStore(
        backend: InMemoryPersistentRecordBackend,
        resolver: CognitiveDekMaterialResolver
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
            correlationIds = CorrelationIdGenerator { "conversation-${sequence.incrementAndGet()}" }
        )
    }

    private fun msg(sequence: Long, role: CognitiveConversationRole, content: String) =
        CognitiveConversationContextMessage(CognitiveConversationSequence(sequence), role, content)

    private fun at(second: Long): Instant = Instant.ofEpochSecond(1_800_000_000L + second)

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

        private fun tag(key: ByteArray, nonce: ByteArray, aad: ByteArray, cipher: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(key)
            digest.update(nonce)
            digest.update(aad)
            digest.update(cipher)
            return digest.digest().copyOf(16)
        }
    }

    private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        if (needle.size > haystack.size) return false
        for (start in 0..haystack.size - needle.size) {
            if (needle.indices.all { offset -> haystack[start + offset] == needle[offset] }) return true
        }
        return false
    }

    @Test
    fun linked_chunk_v3_round_trips_predecessor_and_contiguous_messages() {
        val session = CognitiveConversationSessionId("codec-v3-linked")
        val previous = ConversationPersistentRecordCodec.chunkEntityId(session, 1L)
        val snapshot = CognitiveConversationContextSnapshot(
            session,
            listOf(
                msg(3, CognitiveConversationRole.USER, "u2"),
                msg(4, CognitiveConversationRole.ASSISTANT, "a2")
            )
        )
        val encoded = ConversationPersistentRecordCodec.encodeLinkedChunk(
            snapshot = snapshot,
            persistedAt = at(3),
            previousChunkId = previous
        )

        val decoded = assertIs<ConversationLinkedChunkDecodeResult.Decoded>(
            ConversationPersistentRecordCodec.decodeLinkedChunk(encoded)
        ).chunk

        assertEquals(session, decoded.snapshot.sessionId)
        assertEquals(listOf(3L, 4L), decoded.snapshot.messages.map { it.sequence.value })
        assertEquals(previous, decoded.previousChunkId)
        assertEquals(
            ConversationPersistentRecordCodec.chunkEntityId(session, 3L),
            encoded.id
        )
        assertFalse(encoded.id.value.contains(session.value))
    }

    @Test
    fun linked_chunk_v3_has_distinct_identity_from_v2_for_non_destructive_migration() {
        val session = CognitiveConversationSessionId("codec-v3-migration")
        val snapshot = CognitiveConversationContextSnapshot(
            session,
            listOf(msg(1, CognitiveConversationRole.USER, "legacy-and-v3"))
        )
        val v2 = ConversationPersistentRecordCodec.encodeChunk(snapshot, at(1))
        val v3 = ConversationPersistentRecordCodec.encodeLinkedChunk(
            snapshot,
            at(1),
            previousChunkId = null
        )

        assertNotEquals(v2.id, v3.id)
        assertNotEquals(v2.schemaId, v3.schemaId)
        assertEquals(
            ConversationPersistentRecordCodec.chunkEntityId(session, 1L),
            v3.id
        )
    }

    @Test
    fun conversation_v3_format_marker_round_trips_and_has_constant_non_session_identity() {
        val encoded = ConversationPersistentRecordCodec.encodeFormatMarker(at(1))
        val decoded = assertIs<ConversationFormatMarkerDecodeResult.Decoded>(
            ConversationPersistentRecordCodec.decodeFormatMarker(encoded)
        ).marker

        assertEquals(3, decoded.formatEpoch)
        assertEquals(
            ConversationPersistentRecordCodec.formatMarkerEntityId(),
            encoded.id
        )
        assertEquals("conversation-format-v3", encoded.id.value)
    }

    @Test
    fun conversation_head_v3_round_trips_latest_chunk_without_plaintext_session_id() {
        val session = CognitiveConversationSessionId("codec-v3-head")
        val latest = ConversationPersistentRecordCodec.chunkEntityId(session, 9L)
        val encoded = ConversationPersistentRecordCodec.encodeHead(
            sessionId = session,
            lastSequence = 10L,
            latestChunkId = latest,
            persistedAt = at(10)
        )

        val decoded = assertIs<ConversationHeadDecodeResult.Decoded>(
            ConversationPersistentRecordCodec.decodeHead(encoded)
        ).head

        assertEquals(session, decoded.sessionId)
        assertEquals(10L, decoded.lastSequence)
        assertEquals(latest, decoded.latestChunkId)
        assertEquals(
            ConversationPersistentRecordCodec.headEntityId(session),
            encoded.id
        )
        assertFalse(encoded.id.value.contains(session.value))
    }

    @Test
    fun linked_chunk_and_head_v3_fail_closed_on_structural_or_entity_id_mismatch() {
        val session = CognitiveConversationSessionId("codec-v3-corrupt")
        val linked = ConversationPersistentRecordCodec.encodeLinkedChunk(
            CognitiveConversationContextSnapshot(
                session,
                listOf(msg(1, CognitiveConversationRole.USER, "hello"))
            ),
            at(1),
            previousChunkId = null
        )
        assertEquals(
            ConversationLinkedChunkDecodeResult.Corrupt,
            ConversationPersistentRecordCodec.decodeLinkedChunk(
                linked.copy(id = PersistentEntityId("wrong-linked-id"))
            )
        )

        val head = ConversationPersistentRecordCodec.encodeHead(
            sessionId = session,
            lastSequence = 1L,
            latestChunkId = linked.id,
            persistedAt = at(2)
        )
        val bytes = head.payload.copyBytes().copyOf(head.payload.size - 1)
        val truncated = head.copy(payload = PersistentPayload(bytes))
        assertEquals(
            ConversationHeadDecodeResult.Corrupt,
            ConversationPersistentRecordCodec.decodeHead(truncated)
        )
    }

    @Test
    fun completed_pair_appends_atomically_and_reopens_exactly() {
        val backend = InMemoryPersistentRecordBackend()
        val store = openConversation(backend, maxRetained = 8)
        val session = CognitiveConversationSessionId("atomic-pair")

        val result = assertIs<PersistentConversationAppendPairResult.Appended>(
            store.appendPair(
                sessionId = session,
                user = msg(1, CognitiveConversationRole.USER, "hello"),
                assistant = msg(2, CognitiveConversationRole.ASSISTANT, "reply"),
                persistedAt = at(1)
            )
        )

        assertEquals(listOf(1L, 2L), result.snapshot.messages.map { it.sequence.value })

        val reopened = openConversation(backend, maxRetained = 8).reopen(session)
        requireNotNull(reopened)
        assertEquals(
            listOf("hello", "reply"),
            reopened.messages.map { it.content }
        )
    }

    @Test
    fun exact_completed_pair_retry_is_idempotent_and_conflict_is_rejected() {
        val backend = InMemoryPersistentRecordBackend()
        val store = openConversation(backend, maxRetained = 8)
        val session = CognitiveConversationSessionId("atomic-pair-idempotent")
        val user = msg(1, CognitiveConversationRole.USER, "hello")
        val assistant = msg(2, CognitiveConversationRole.ASSISTANT, "reply")

        assertIs<PersistentConversationAppendPairResult.Appended>(
            store.appendPair(session, user, assistant, at(1))
        )
        assertIs<PersistentConversationAppendPairResult.AlreadyPresent>(
            store.appendPair(session, user, assistant, at(2))
        )
        assertIs<PersistentConversationAppendPairResult.Rejected>(
            store.appendPair(
                session,
                user,
                msg(2, CognitiveConversationRole.ASSISTANT, "different"),
                at(3)
            )
        )

        val reopened = requireNotNull(openConversation(backend, maxRetained = 8).reopen(session))
        assertEquals(listOf("hello", "reply"), reopened.messages.map { it.content })
    }

    @Test
    fun completed_pair_requires_user_then_assistant_adjacent_sequence() {
        val backend = InMemoryPersistentRecordBackend()
        val store = openConversation(backend, maxRetained = 8)
        val session = CognitiveConversationSessionId("atomic-pair-validation")

        assertIs<PersistentConversationAppendPairResult.Rejected>(
            store.appendPair(
                session,
                msg(1, CognitiveConversationRole.ASSISTANT, "wrong role"),
                msg(2, CognitiveConversationRole.USER, "wrong role"),
                at(1)
            )
        )
        assertIs<PersistentConversationAppendPairResult.Rejected>(
            store.appendPair(
                session,
                msg(1, CognitiveConversationRole.USER, "user"),
                msg(3, CognitiveConversationRole.ASSISTANT, "gap"),
                at(2)
            )
        )
        assertNull(store.reopen(session))
    }


    @Test
    fun atomic_pair_retention_never_leaves_partial_pair_when_bound_is_odd() {
        val backend = InMemoryPersistentRecordBackend()
        val store = openConversation(backend, maxRetained = 3)
        val session = CognitiveConversationSessionId("odd-pair-bound")

        assertIs<PersistentConversationAppendPairResult.Appended>(
            store.appendPair(
                session,
                msg(1, CognitiveConversationRole.USER, "u1"),
                msg(2, CognitiveConversationRole.ASSISTANT, "a1"),
                at(1)
            )
        )
        val second = assertIs<PersistentConversationAppendPairResult.Appended>(
            store.appendPair(
                session,
                msg(3, CognitiveConversationRole.USER, "u2"),
                msg(4, CognitiveConversationRole.ASSISTANT, "a2"),
                at(2)
            )
        )

        assertEquals(listOf(3L, 4L), second.snapshot.messages.map { it.sequence.value })
        assertEquals(
            listOf(CognitiveConversationRole.USER, CognitiveConversationRole.ASSISTANT),
            second.snapshot.messages.map { it.role }
        )
    }

}
