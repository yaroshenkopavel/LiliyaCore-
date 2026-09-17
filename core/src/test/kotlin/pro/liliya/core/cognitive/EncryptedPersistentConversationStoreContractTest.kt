package pro.liliya.core.cognitive

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
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentBackendLoadResult
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

class EncryptedPersistentConversationStoreContractTest {
    private val storeId = PersistentStoreId("conversation-continuity")
    private val profile = CognitiveEncryptionProfile.AES_256_GCM
    private val dekRef = CognitiveDekReference(CognitiveDekId("conversation-dek"), CognitiveDekGeneration(1))
    private val material = CognitiveDekMaterial(ByteArray(32) { (it * 11 + 7).toByte() })

    @Test
    fun accepted_turns_reopen_as_bounded_tail_without_becoming_memory() {
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
        assertEquals(1, reopened.sessionCount())
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
}
