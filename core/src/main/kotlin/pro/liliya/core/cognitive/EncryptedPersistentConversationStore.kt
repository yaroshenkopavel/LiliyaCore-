package pro.liliya.core.cognitive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.TreeMap
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitivePersistentRecordDraft
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecordOwnership
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

sealed interface PersistentConversationAppendPairResult {
    data class Appended(val snapshot: CognitiveConversationContextSnapshot) : PersistentConversationAppendPairResult
    data class AlreadyPresent(val snapshot: CognitiveConversationContextSnapshot) : PersistentConversationAppendPairResult
    data class Rejected(val reason: String) : PersistentConversationAppendPairResult
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : PersistentConversationAppendPairResult
    data class Failed(val reason: String) : PersistentConversationAppendPairResult
}

sealed interface PersistentConversationHistoryResult {
    data class Found(val snapshot: CognitiveConversationContextSnapshot) : PersistentConversationHistoryResult
    data object Absent : PersistentConversationHistoryResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) : PersistentConversationHistoryResult
    data object Corrupt : PersistentConversationHistoryResult
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
            val last = current.lastSequence
            if (message.sequence.value != last + 1L) {
                return PersistentConversationAppendResult.Rejected("conversation sequence must advance exactly once")
            }
        }

        val messages = ((current?.snapshot?.messages ?: emptyList()) + message)
            .takeLast(maxRetainedMessages)
        val replacement = CognitiveConversationContextSnapshot(sessionId, messages)
        val encoded = ConversationPersistentRecordCodec.encodeChunk(
            CognitiveConversationContextSnapshot(sessionId, listOf(message)), persistedAt
        )
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
            encryptedStore.install(draft)
        } finally {
            bytes.fill(0)
        }

        return when (persisted) {
            is CognitiveEncryptionResult.Success -> {
                val chunks = TreeMap(current?.chunks ?: emptyMap())
                chunks[message.sequence.value] = Chunk(encoded.id, message.sequence.value, message.sequence.value)
                entries[sessionId] = Entry(replacement, message.sequence.value, current?.legacyMessages ?: emptyList(), chunks)
                PersistentConversationAppendResult.Appended(replacement)
            }
            is CognitiveEncryptionResult.Rejected ->
                PersistentConversationAppendResult.EncryptionUnavailable(persisted.category)
            is CognitiveEncryptionResult.Failed ->
                PersistentConversationAppendResult.Failed("encrypted conversation persistence failed")
        }
    }

    @Synchronized
    fun appendPair(
        sessionId: CognitiveConversationSessionId,
        user: CognitiveConversationContextMessage,
        assistant: CognitiveConversationContextMessage,
        persistedAt: Instant
    ): PersistentConversationAppendPairResult {
        if (user.role != CognitiveConversationRole.USER ||
            assistant.role != CognitiveConversationRole.ASSISTANT) {
            return PersistentConversationAppendPairResult.Rejected(
                "conversation pair must be USER then ASSISTANT"
            )
        }
        if (user.content.length > maxMessageChars || assistant.content.length > maxMessageChars) {
            return PersistentConversationAppendPairResult.Rejected(
                "conversation pair message exceeds configured bound"
            )
        }
        if (assistant.sequence.value != user.sequence.value + 1L) {
            return PersistentConversationAppendPairResult.Rejected(
                "conversation pair sequences must be adjacent"
            )
        }

        val current = entries[sessionId]
        if (current == null && user.sequence.value != 1L) {
            return PersistentConversationAppendPairResult.Rejected(
                "new conversation pair must begin at sequence 1"
            )
        }
        if (current != null) {
            val existingUser = current.snapshot.messages.firstOrNull { it.sequence == user.sequence }
            val existingAssistant = current.snapshot.messages.firstOrNull { it.sequence == assistant.sequence }
            if (existingUser != null || existingAssistant != null) {
                return if (existingUser == user && existingAssistant == assistant) {
                    PersistentConversationAppendPairResult.AlreadyPresent(current.snapshot)
                } else {
                    PersistentConversationAppendPairResult.Rejected(
                        "conversation pair sequence conflicts with durable history"
                    )
                }
            }
            val last = current.lastSequence
            if (user.sequence.value != last + 1L) {
                return PersistentConversationAppendPairResult.Rejected(
                    "conversation pair must advance exactly from durable history"
                )
            }
        }

        val retained = ((current?.snapshot?.messages ?: emptyList()) + user + assistant)
            .toMutableList()
        while (retained.size > maxRetainedMessages) {
            if (retained.size < 2) {
                return PersistentConversationAppendPairResult.Rejected(
                    "conversation pair retention cannot preserve complete pairs"
                )
            }
            retained.removeAt(0)
            retained.removeAt(0)
        }
        val replacement = CognitiveConversationContextSnapshot(sessionId, retained)
        val chunk = CognitiveConversationContextSnapshot(sessionId, listOf(user, assistant))
        val persisted = persistChunk(chunk, persistedAt)
        return when (persisted) {
            is CognitiveEncryptionResult.Success -> {
                val chunks = TreeMap(current?.chunks ?: emptyMap())
                chunks[user.sequence.value] = Chunk(persisted.value.record.id, user.sequence.value, assistant.sequence.value)
                entries[sessionId] = Entry(replacement, assistant.sequence.value, current?.legacyMessages ?: emptyList(), chunks)
                PersistentConversationAppendPairResult.Appended(replacement)
            }
            is CognitiveEncryptionResult.Rejected ->
                PersistentConversationAppendPairResult.EncryptionUnavailable(persisted.category)
            is CognitiveEncryptionResult.Failed ->
                PersistentConversationAppendPairResult.Failed(
                    "encrypted conversation pair persistence failed"
                )
        }
    }

    @Synchronized
    fun reopen(sessionId: CognitiveConversationSessionId): CognitiveConversationContextSnapshot? =
        entries[sessionId]?.snapshot

    @Synchronized
    fun sessionCount(): Int = entries.size

    /** Reads a page of authenticated history, without adding older messages to the model context. */
    @Synchronized
    fun history(
        sessionId: CognitiveConversationSessionId,
        beforeSequenceExclusive: Long = Long.MAX_VALUE,
        maxMessages: Int
    ): PersistentConversationHistoryResult {
        require(maxMessages > 0) { "history page size must be positive" }
        val entry = entries[sessionId] ?: return PersistentConversationHistoryResult.Absent
        val selected = ArrayList<CognitiveConversationContextMessage>()
        for (chunk in entry.chunks.descendingMap().values) {
            if (selected.size >= maxMessages) break
            if (chunk.firstSequence >= beforeSequenceExclusive) continue
            val plaintext = when (val opened = encryptedStore.open(chunk.id)) {
                is CognitiveEncryptionResult.Success -> opened.value
                is CognitiveEncryptionResult.Rejected -> return PersistentConversationHistoryResult.EncryptionUnavailable(opened.category)
                is CognitiveEncryptionResult.Failed -> return PersistentConversationHistoryResult.EncryptionUnavailable(opened.category)
            }
            val raw = encryptedStore.inspect(chunk.id)
                ?: return PersistentConversationHistoryResult.Corrupt
            val record = raw.record.copy(payload = PersistentPayload(plaintext.copyBytes()))
            val decoded = ConversationPersistentRecordCodec.decode(record)
            if (decoded !is ConversationDecodeResult.Decoded || !decoded.chunk ||
                decoded.snapshot.sessionId != sessionId ||
                decoded.snapshot.messages.first().sequence.value != chunk.firstSequence ||
                decoded.snapshot.messages.last().sequence.value != chunk.lastSequence) {
                return PersistentConversationHistoryResult.Corrupt
            }
            selected += decoded.snapshot.messages.asReversed().filter { it.sequence.value < beforeSequenceExclusive }
            if (selected.size >= maxMessages) break
        }
        for (message in entry.legacyMessages.asReversed()) {
            if (selected.size >= maxMessages) break
            if (message.sequence.value < beforeSequenceExclusive) selected += message
        }
        return PersistentConversationHistoryResult.Found(
            CognitiveConversationContextSnapshot(sessionId, selected.sortedBy { it.sequence.value }.takeLast(maxMessages))
        )
    }

    private fun persistChunk(
        chunk: CognitiveConversationContextSnapshot,
        persistedAt: Instant
    ): CognitiveEncryptionResult<PersistentRecordOwnership> {
        val encoded = ConversationPersistentRecordCodec.encodeChunk(chunk, persistedAt)
        val bytes = encoded.payload.copyBytes()
        val draft = CognitivePersistentRecordDraft(
            id = encoded.id,
            schemaId = encoded.schemaId,
            schemaVersion = encoded.schemaVersion,
            plaintext = CognitivePlaintext(bytes),
            createdAt = encoded.createdAt,
            dek = activeDek
        )
        return try {
            encryptedStore.install(draft)
        } finally {
            bytes.fill(0)
        }
    }

    private data class Entry(
        val snapshot: CognitiveConversationContextSnapshot,
        val lastSequence: Long,
        val legacyMessages: List<CognitiveConversationContextMessage>,
        val chunks: TreeMap<Long, Chunk>
    )

    private data class Chunk(val id: PersistentEntityId, val firstSequence: Long, val lastSequence: Long)

    private class Pending {
        var legacy: List<CognitiveConversationContextMessage>? = null
        val chunks = TreeMap<Long, Pair<Chunk, List<CognitiveConversationContextMessage>>>()
    }

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
            val pending = LinkedHashMap<CognitiveConversationSessionId, Pending>()
            for (entry in decrypted) {
                when (val decoded = ConversationPersistentRecordCodec.decode(entry.record)) {
                    is ConversationDecodeResult.Decoded -> {
                        val messages = decoded.snapshot.messages
                        if (messages.any { it.content.length > maxMessageChars }) {
                            return PersistentConversationOpenResult.Incompatible("durable conversation exceeds configured reconstruction bounds")
                        }
                        val owner = pending.getOrPut(decoded.snapshot.sessionId) { Pending() }
                        if (decoded.chunk) {
                            if (messages.isEmpty() || messages.size > 2) return PersistentConversationOpenResult.Corrupt
                            val first = messages.first().sequence.value
                            val last = messages.last().sequence.value
                            if (last != first + messages.size - 1L || owner.chunks.put(
                                    first, Chunk(entry.record.id, first, last) to messages
                                ) != null
                            ) return PersistentConversationOpenResult.Corrupt
                        } else {
                            if (owner.legacy != null) return PersistentConversationOpenResult.Corrupt
                            owner.legacy = messages
                        }
                    }
                    ConversationDecodeResult.Corrupt -> return PersistentConversationOpenResult.Corrupt
                    is ConversationDecodeResult.Incompatible ->
                        return PersistentConversationOpenResult.Incompatible(decoded.reason)
                }
            }
            val restored = LinkedHashMap<CognitiveConversationSessionId, Entry>()
            for ((sessionId, owner) in pending) {
                val legacy = owner.legacy ?: emptyList()
                var lastSequence = legacy.lastOrNull()?.sequence?.value ?: 0L
                val tail = ArrayDeque(legacy.takeLast(maxRetainedMessages))
                val chunks = TreeMap<Long, Chunk>()
                for ((first, item) in owner.chunks) {
                    val (chunk, messages) = item
                    if (first != lastSequence + 1L) return PersistentConversationOpenResult.Corrupt
                    for (message in messages) {
                        tail.addLast(message)
                        if (tail.size > maxRetainedMessages) tail.removeFirst()
                    }
                    lastSequence = chunk.lastSequence
                    chunks[first] = chunk
                }
                restored[sessionId] = Entry(
                    CognitiveConversationContextSnapshot(sessionId, tail.toList()),
                    lastSequence, legacy, chunks
                )
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
    data class Decoded(val snapshot: CognitiveConversationContextSnapshot, val chunk: Boolean) : ConversationDecodeResult
    data object Corrupt : ConversationDecodeResult
    data class Incompatible(val reason: String) : ConversationDecodeResult
}

internal data class ConversationLinkedChunk(
    val snapshot: CognitiveConversationContextSnapshot,
    val previousChunkId: PersistentEntityId?
)

internal data class ConversationSessionHead(
    val sessionId: CognitiveConversationSessionId,
    val lastSequence: Long,
    val latestChunkId: PersistentEntityId
)

internal sealed interface ConversationLinkedChunkDecodeResult {
    data class Decoded(val chunk: ConversationLinkedChunk) : ConversationLinkedChunkDecodeResult
    data object Corrupt : ConversationLinkedChunkDecodeResult
    data class Incompatible(val reason: String) : ConversationLinkedChunkDecodeResult
}

internal sealed interface ConversationHeadDecodeResult {
    data class Decoded(val head: ConversationSessionHead) : ConversationHeadDecodeResult
    data object Corrupt : ConversationHeadDecodeResult
    data class Incompatible(val reason: String) : ConversationHeadDecodeResult
}

internal object ConversationPersistentRecordCodec {
    private val schemaId = PersistentSchemaId("cognitive-conversation-session")
    private val legacyVersion = PersistentSchemaVersion(1)
    private val chunkVersion = PersistentSchemaVersion(2)
    private val linkedChunkVersion = PersistentSchemaVersion(3)
    private val headVersion = PersistentSchemaVersion(4)
    private const val LEGACY_MAGIC = 0x434E5631
    private const val CHUNK_MAGIC = 0x434E5632
    private const val LINKED_CHUNK_MAGIC = 0x434E5633
    private const val HEAD_MAGIC = 0x434E5648

    fun encodeChunk(snapshot: CognitiveConversationContextSnapshot, persistedAt: Instant): PersistentRecord {
        require(snapshot.messages.size in 1..2)
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(CHUNK_MAGIC)
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
            id = chunkId(snapshot.sessionId, snapshot.messages.first().sequence.value),
            schemaId = schemaId,
            schemaVersion = chunkVersion,
            payload = PersistentPayload(bytes),
            createdAt = persistedAt
        )
    }

    fun encodeLinkedChunk(
        snapshot: CognitiveConversationContextSnapshot,
        persistedAt: Instant,
        previousChunkId: PersistentEntityId?
    ): PersistentRecord {
        require(snapshot.messages.size in 1..2)
        val firstSequence = snapshot.messages.first().sequence.value
        require(firstSequence > 0L)
        require(
            snapshot.messages.last().sequence.value ==
                firstSequence + snapshot.messages.size - 1L
        ) { "conversation linked chunk sequences must be contiguous" }

        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(LINKED_CHUNK_MAGIC)
                data.writeString(snapshot.sessionId.value)
                if (previousChunkId == null) {
                    data.writeInt(0)
                } else {
                    data.writeInt(1)
                    data.writeString(previousChunkId.value)
                }
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
            id = chunkId(snapshot.sessionId, firstSequence),
            schemaId = schemaId,
            schemaVersion = linkedChunkVersion,
            payload = PersistentPayload(bytes),
            createdAt = persistedAt
        )
    }

    fun encodeHead(
        sessionId: CognitiveConversationSessionId,
        lastSequence: Long,
        latestChunkId: PersistentEntityId,
        persistedAt: Instant
    ): PersistentRecord {
        require(lastSequence > 0L) { "conversation head sequence must be positive" }
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(HEAD_MAGIC)
                data.writeString(sessionId.value)
                data.writeLong(lastSequence)
                data.writeString(latestChunkId.value)
            }
            output.toByteArray()
        }
        return PersistentRecord(
            id = headId(sessionId),
            schemaId = schemaId,
            schemaVersion = headVersion,
            payload = PersistentPayload(bytes),
            createdAt = persistedAt
        )
    }

    fun decodeLinkedChunk(record: PersistentRecord): ConversationLinkedChunkDecodeResult {
        if (record.schemaId != schemaId) {
            return ConversationLinkedChunkDecodeResult.Incompatible(
                "conversation schema id mismatch"
            )
        }
        if (record.schemaVersion != linkedChunkVersion) {
            return ConversationLinkedChunkDecodeResult.Incompatible(
                "conversation linked chunk schema version mismatch"
            )
        }
        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != LINKED_CHUNK_MAGIC) {
                return ConversationLinkedChunkDecodeResult.Corrupt
            }
            val sessionId = CognitiveConversationSessionId(data.readString(input))
            val previousMarker = data.readInt()
            val previousChunkId = when (previousMarker) {
                0 -> null
                1 -> PersistentEntityId(data.readString(input))
                else -> return ConversationLinkedChunkDecodeResult.Corrupt
            }
            val count = data.readInt()
            if (count !in 1..2) return ConversationLinkedChunkDecodeResult.Corrupt
            val messages = ArrayList<CognitiveConversationContextMessage>(count)
            repeat(count) {
                val sequence = CognitiveConversationSequence(data.readLong())
                val role = CognitiveConversationRole.entries.getOrNull(data.readInt())
                    ?: return ConversationLinkedChunkDecodeResult.Corrupt
                messages += CognitiveConversationContextMessage(
                    sequence,
                    role,
                    data.readString(input)
                )
            }
            if (input.available() != 0) return ConversationLinkedChunkDecodeResult.Corrupt
            val firstSequence = messages.first().sequence.value
            if (firstSequence <= 0L ||
                messages.last().sequence.value != firstSequence + messages.size - 1L ||
                record.id != chunkId(sessionId, firstSequence)
            ) {
                return ConversationLinkedChunkDecodeResult.Corrupt
            }
            ConversationLinkedChunkDecodeResult.Decoded(
                ConversationLinkedChunk(
                    snapshot = CognitiveConversationContextSnapshot(sessionId, messages),
                    previousChunkId = previousChunkId
                )
            )
        } catch (_: EOFException) {
            ConversationLinkedChunkDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            ConversationLinkedChunkDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            ConversationLinkedChunkDecodeResult.Corrupt
        }
    }

    fun decodeHead(record: PersistentRecord): ConversationHeadDecodeResult {
        if (record.schemaId != schemaId) {
            return ConversationHeadDecodeResult.Incompatible("conversation schema id mismatch")
        }
        if (record.schemaVersion != headVersion) {
            return ConversationHeadDecodeResult.Incompatible(
                "conversation head schema version mismatch"
            )
        }
        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != HEAD_MAGIC) return ConversationHeadDecodeResult.Corrupt
            val sessionId = CognitiveConversationSessionId(data.readString(input))
            val lastSequence = data.readLong()
            if (lastSequence <= 0L) return ConversationHeadDecodeResult.Corrupt
            val latestChunkId = PersistentEntityId(data.readString(input))
            if (input.available() != 0 || record.id != headId(sessionId)) {
                return ConversationHeadDecodeResult.Corrupt
            }
            ConversationHeadDecodeResult.Decoded(
                ConversationSessionHead(sessionId, lastSequence, latestChunkId)
            )
        } catch (_: EOFException) {
            ConversationHeadDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            ConversationHeadDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            ConversationHeadDecodeResult.Corrupt
        }
    }

    fun chunkEntityId(
        sessionId: CognitiveConversationSessionId,
        firstSequence: Long
    ): PersistentEntityId = chunkId(sessionId, firstSequence)

    fun headEntityId(
        sessionId: CognitiveConversationSessionId
    ): PersistentEntityId = headId(sessionId)

    fun decode(record: PersistentRecord): ConversationDecodeResult {
        if (record.schemaId != schemaId) return ConversationDecodeResult.Incompatible("conversation schema id mismatch")
        if (record.schemaVersion != legacyVersion && record.schemaVersion != chunkVersion) {
            return ConversationDecodeResult.Incompatible("conversation schema version mismatch")
        }
        val chunk = record.schemaVersion == chunkVersion
        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != (if (chunk) CHUNK_MAGIC else LEGACY_MAGIC)) {
                return ConversationDecodeResult.Corrupt
            }
            val sessionId = CognitiveConversationSessionId(data.readString(input))
            val count = data.readInt()
            if (count < 0 || count > if (chunk) 2 else 1_000_000) return ConversationDecodeResult.Corrupt
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
            val expectedId = if (chunk && messages.isNotEmpty()) chunkId(sessionId, messages.first().sequence.value)
                else entityId(sessionId)
            if (record.id != expectedId) return ConversationDecodeResult.Corrupt
            ConversationDecodeResult.Decoded(CognitiveConversationContextSnapshot(sessionId, messages), chunk)
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

    private fun chunkId(sessionId: CognitiveConversationSessionId, firstSequence: Long): PersistentEntityId {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((sessionId.value + ":" + firstSequence).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return PersistentEntityId("conversation-chunk-$digest")
    }

    private fun headId(sessionId: CognitiveConversationSessionId): PersistentEntityId {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("head:" + sessionId.value).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return PersistentEntityId("conversation-head-$digest")
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
