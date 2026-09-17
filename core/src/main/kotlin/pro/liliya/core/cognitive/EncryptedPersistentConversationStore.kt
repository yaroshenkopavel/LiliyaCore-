package pro.liliya.core.cognitive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitivePersistentRecordDraft
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

sealed interface PersistentConversationOpenResult {
    data class Opened(val store: EncryptedPersistentConversationStore) : PersistentConversationOpenResult
    data object Corrupt : PersistentConversationOpenResult
    data class Incompatible(val reason: String) : PersistentConversationOpenResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) : PersistentConversationOpenResult
}

sealed interface PersistentConversationAppendResult {
    data class Appended(val snapshot: CognitiveConversationContextSnapshot) : PersistentConversationAppendResult
    data class AlreadyPresent(val snapshot: CognitiveConversationContextSnapshot) : PersistentConversationAppendResult
    data class Rejected(val reason: String) : PersistentConversationAppendResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) : PersistentConversationAppendResult
    data class Failed(val reason: String) : PersistentConversationAppendResult
}

/**
 * Encrypted durable owner for accepted conversation transcript only.
 *
 * Conversation history remains separate from learned Memory/Knowledge and has no Authority,
 * Capability, learning or execution dependency. Durable records contain transcript payload only
 * inside the authenticated encrypted envelope; plaintext persistence metadata uses a hash-derived
 * entity id rather than the conversation session id.
 */
class EncryptedPersistentConversationStore private constructor(
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val activeDek: CognitiveDekReference,
    private val maxRetainedMessages: Int,
    private val maxMessageChars: Int,
    restored: Map<CognitiveConversationSessionId, Entry>
) {
    private val entries = LinkedHashMap(restored)

    init {
        require(maxRetainedMessages > 0) { "maximum retained conversation messages must be positive" }
        require(maxMessageChars > 0) { "maximum conversation message chars must be positive" }
    }

    @Synchronized
    fun append(
        sessionId: CognitiveConversationSessionId,
        message: CognitiveConversationContextMessage,
        persistedAt: Instant
    ): PersistentConversationAppendResult {
        if (message.content.length > maxMessageChars) {
            return PersistentConversationAppendResult.Rejected("conversation message exceeds configured bound")
        }

        val current = entries[sessionId]
        if (current == null && message.sequence.value != 1L) {
            return PersistentConversationAppendResult.Rejected("new conversation must begin at sequence 1")
        }
        if (current != null) {
            val sameSequence = current.snapshot.messages.firstOrNull { it.sequence == message.sequence }
            if (sameSequence != null) {
                return if (sameSequence == message) {
                    PersistentConversationAppendResult.AlreadyPresent(current.snapshot)
                } else {
                    PersistentConversationAppendResult.Rejected("conversation sequence conflicts with durable history")
                }
            }
            val last = current.snapshot.messages.lastOrNull()?.sequence?.value ?: 0L
            if (message.sequence.value != last + 1L) {
                return PersistentConversationAppendResult.Rejected("conversation sequence must advance exactly once")
            }
        }

        val messages = ((current?.snapshot?.messages ?: emptyList()) + message)
            .takeLast(maxRetainedMessages)
        val replacement = CognitiveConversationContextSnapshot(sessionId, messages)
        val encoded = ConversationPersistentRecordCodec.encode(replacement, persistedAt)
        val bytes = encoded.payload.copyBytes()
        val draft = CognitivePersistentRecordDraft(
            id = encoded.id,
            schemaId = encoded.schemaId,
            schemaVersion = encoded.schemaVersion,
            plaintext = CognitivePlaintext(bytes),
            createdAt = encoded.createdAt,
            dek = activeDek
        )
        val persisted = try {
            if (current == null) {
                encryptedStore.install(draft)
            } else {
                encryptedStore.transitionExact(
                    sourceId = current.entityId,
                    sourceGeneration = current.generation,
                    replacement = draft
                )
            }
        } finally {
            bytes.fill(0)
        }

        return when (persisted) {
            is CognitiveEncryptionResult.Success -> {
                entries[sessionId] = Entry(
                    snapshot = replacement,
                    entityId = encoded.id,
                    generation = persisted.value.generation
                )
                PersistentConversationAppendResult.Appended(replacement)
            }
            is CognitiveEncryptionResult.Rejected ->
                PersistentConversationAppendResult.EncryptionUnavailable(persisted.category)
            is CognitiveEncryptionResult.Failed ->
                PersistentConversationAppendResult.Failed("encrypted conversation persistence failed")
        }
    }

    @Synchronized
    fun reopen(sessionId: CognitiveConversationSessionId): CognitiveConversationContextSnapshot? =
        entries[sessionId]?.snapshot

    @Synchronized
    fun sessionCount(): Int = entries.size

    private data class Entry(
        val snapshot: CognitiveConversationContextSnapshot,
        val entityId: PersistentEntityId,
        val generation: PersistentGeneration
    )

    companion object {
        fun open(
            encryptedStore: EncryptedPersistentRecordStore,
            activeDek: CognitiveDekReference,
            maxRetainedMessages: Int,
            maxMessageChars: Int
        ): PersistentConversationOpenResult {
            if (maxRetainedMessages <= 0 || maxMessageChars <= 0) {
                return PersistentConversationOpenResult.Incompatible("conversation persistence bounds must be positive")
            }
            val decrypted = when (val result = encryptedStore.decryptedSnapshotEntries()) {
                is CognitiveEncryptionResult.Success -> result.value
                is CognitiveEncryptionResult.Rejected ->
                    return PersistentConversationOpenResult.EncryptionUnavailable(result.category)
                is CognitiveEncryptionResult.Failed ->
                    return PersistentConversationOpenResult.EncryptionUnavailable(result.category)
            }
            val restored = LinkedHashMap<CognitiveConversationSessionId, Entry>()
            for (entry in decrypted) {
                when (val decoded = ConversationPersistentRecordCodec.decode(entry.record)) {
                    is ConversationDecodeResult.Decoded -> {
                        if (decoded.snapshot.messages.size > maxRetainedMessages ||
                            decoded.snapshot.messages.any { it.content.length > maxMessageChars }) {
                            return PersistentConversationOpenResult.Incompatible("durable conversation exceeds configured reconstruction bounds")
                        }
                        if (restored.put(
                                decoded.snapshot.sessionId,
                                Entry(decoded.snapshot, entry.record.id, entry.generation)
                            ) != null
                        ) return PersistentConversationOpenResult.Corrupt
                    }
                    ConversationDecodeResult.Corrupt -> return PersistentConversationOpenResult.Corrupt
                    is ConversationDecodeResult.Incompatible ->
                        return PersistentConversationOpenResult.Incompatible(decoded.reason)
                }
            }
            return PersistentConversationOpenResult.Opened(
                EncryptedPersistentConversationStore(
                    encryptedStore,
                    activeDek,
                    maxRetainedMessages,
                    maxMessageChars,
                    restored
                )
            )
        }
    }
}

private sealed interface ConversationDecodeResult {
    data class Decoded(val snapshot: CognitiveConversationContextSnapshot) : ConversationDecodeResult
    data object Corrupt : ConversationDecodeResult
    data class Incompatible(val reason: String) : ConversationDecodeResult
}

private object ConversationPersistentRecordCodec {
    private val schemaId = PersistentSchemaId("cognitive-conversation-session")
    private val schemaVersion = PersistentSchemaVersion(1)
    private const val MAGIC = 0x434E5631

    fun encode(snapshot: CognitiveConversationContextSnapshot, persistedAt: Instant): PersistentRecord {
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeString(snapshot.sessionId.value)
                data.writeInt(snapshot.messages.size)
                snapshot.messages.forEach { message ->
                    data.writeLong(message.sequence.value)
                    data.writeInt(message.role.ordinal)
                    data.writeString(message.content)
                }
            }
            output.toByteArray()
        }
        return PersistentRecord(
            id = entityId(snapshot.sessionId),
            schemaId = schemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(bytes),
            createdAt = persistedAt
        )
    }

    fun decode(record: PersistentRecord): ConversationDecodeResult {
        if (record.schemaId != schemaId) return ConversationDecodeResult.Incompatible("conversation schema id mismatch")
        if (record.schemaVersion != schemaVersion) return ConversationDecodeResult.Incompatible("conversation schema version mismatch")
        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) return ConversationDecodeResult.Corrupt
            val sessionId = CognitiveConversationSessionId(data.readString(input))
            if (record.id != entityId(sessionId)) return ConversationDecodeResult.Corrupt
            val count = data.readInt()
            if (count < 0 || count > 1_000_000) return ConversationDecodeResult.Corrupt
            val messages = ArrayList<CognitiveConversationContextMessage>(count)
            repeat(count) {
                val sequence = CognitiveConversationSequence(data.readLong())
                val roleOrdinal = data.readInt()
                val role = CognitiveConversationRole.entries.getOrNull(roleOrdinal)
                    ?: return ConversationDecodeResult.Corrupt
                val content = data.readString(input)
                messages += CognitiveConversationContextMessage(sequence, role, content)
            }
            if (input.available() != 0) return ConversationDecodeResult.Corrupt
            ConversationDecodeResult.Decoded(CognitiveConversationContextSnapshot(sessionId, messages))
        } catch (_: EOFException) {
            ConversationDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            ConversationDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            ConversationDecodeResult.Corrupt
        }
    }

    private fun entityId(sessionId: CognitiveConversationSessionId): PersistentEntityId {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sessionId.value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return PersistentEntityId("conversation-$digest")
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(input: ByteArrayInputStream): String {
        val length = readInt()
        if (length < 0 || length > input.available()) throw EOFException()
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
