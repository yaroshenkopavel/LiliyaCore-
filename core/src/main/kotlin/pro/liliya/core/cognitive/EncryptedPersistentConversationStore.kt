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

sealed interface PersistentConversationReopenResult {
    data class Found(
        val snapshot: CognitiveConversationContextSnapshot
    ) : PersistentConversationReopenResult

    data object Absent : PersistentConversationReopenResult
    data object Corrupt : PersistentConversationReopenResult
    data class Incompatible(val reason: String) : PersistentConversationReopenResult
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : PersistentConversationReopenResult
}

sealed interface PersistentConversationSessionCountResult {
    data class Count(val value: Int) : PersistentConversationSessionCountResult
    data object Corrupt : PersistentConversationSessionCountResult
    data class Incompatible(val reason: String) : PersistentConversationSessionCountResult
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : PersistentConversationSessionCountResult
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
    restored: Map<CognitiveConversationSessionId, Entry>,
    private val nativeV3: ConversationV3NativeRuntime? = null,
    private val mixedV3: ConversationV3MixedSessionRuntime? = null
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
        mixedV3?.let {
            return PersistentConversationAppendResult.Rejected(
                "mixed conversation write migration is not enabled"
            )
        }
        nativeV3?.let { return it.append(sessionId, message, persistedAt) }

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
        mixedV3?.let {
            return PersistentConversationAppendPairResult.Rejected(
                "mixed conversation write migration is not enabled"
            )
        }
        nativeV3?.let {
            return it.appendPair(sessionId, user, assistant, persistedAt)
        }

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
    fun reopenResult(
        sessionId: CognitiveConversationSessionId
    ): PersistentConversationReopenResult {
        mixedV3?.let { return it.reopenResult(sessionId) }
        nativeV3?.let { return it.reopenResult(sessionId) }
        val snapshot = entries[sessionId]?.snapshot
            ?: return PersistentConversationReopenResult.Absent
        return PersistentConversationReopenResult.Found(snapshot)
    }

    @Synchronized
    fun reopen(sessionId: CognitiveConversationSessionId): CognitiveConversationContextSnapshot? =
        when (val result = reopenResult(sessionId)) {
            is PersistentConversationReopenResult.Found -> result.snapshot
            PersistentConversationReopenResult.Absent -> null
            PersistentConversationReopenResult.Corrupt ->
                throw IllegalStateException("durable conversation is corrupt")
            is PersistentConversationReopenResult.Incompatible ->
                throw IllegalStateException(result.reason)
            is PersistentConversationReopenResult.EncryptionUnavailable ->
                throw IllegalStateException(
                    "durable conversation encryption is unavailable: " + result.category.name
                )
        }

    @Synchronized
    fun sessionCountResult(): PersistentConversationSessionCountResult =
        mixedV3?.let {
            PersistentConversationSessionCountResult.Incompatible(
                "mixed conversation session count requires migration catalogue"
            )
        } ?: nativeV3?.sessionCountResult()
            ?: PersistentConversationSessionCountResult.Count(entries.size)

    @Synchronized
    fun sessionCount(): Int =
        when (val result = sessionCountResult()) {
            is PersistentConversationSessionCountResult.Count -> result.value
            PersistentConversationSessionCountResult.Corrupt ->
                throw IllegalStateException("durable conversation store is corrupt")
            is PersistentConversationSessionCountResult.Incompatible ->
                throw IllegalStateException(result.reason)
            is PersistentConversationSessionCountResult.EncryptionUnavailable ->
                throw IllegalStateException(
                    "durable conversation encryption is unavailable: " + result.category.name
                )
        }

    /** Reads a page of authenticated history, without adding older messages to the model context. */
    @Synchronized
    fun history(
        sessionId: CognitiveConversationSessionId,
        beforeSequenceExclusive: Long = Long.MAX_VALUE,
        maxMessages: Int
    ): PersistentConversationHistoryResult {
        mixedV3?.let {
            return it.history(
                sessionId = sessionId,
                beforeSequenceExclusive = beforeSequenceExclusive,
                maxMessages = maxMessages
            )
        }
        nativeV3?.let {
            return it.history(
                sessionId = sessionId,
                beforeSequenceExclusive = beforeSequenceExclusive,
                maxMessages = maxMessages
            )
        }

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

            when (
                val native = ConversationV3NativeRuntime.detectOrInitialize(
                    encryptedStore = encryptedStore,
                    activeDek = activeDek,
                    maxRetainedMessages = maxRetainedMessages,
                    maxMessageChars = maxMessageChars
                )
            ) {
                is ConversationV3NativeDecision.Native ->
                    return PersistentConversationOpenResult.Opened(
                        EncryptedPersistentConversationStore(
                            encryptedStore = encryptedStore,
                            activeDek = activeDek,
                            maxRetainedMessages = maxRetainedMessages,
                            maxMessageChars = maxMessageChars,
                            restored = emptyMap(),
                            nativeV3 = native.runtime
                        )
                    )
                is ConversationV3NativeDecision.Mixed ->
                    return PersistentConversationOpenResult.Opened(
                        EncryptedPersistentConversationStore(
                            encryptedStore = encryptedStore,
                            activeDek = activeDek,
                            maxRetainedMessages = maxRetainedMessages,
                            maxMessageChars = maxMessageChars,
                            restored = emptyMap(),
                            mixedV3 = ConversationV3MixedSessionRuntime(
                                encryptedStore = encryptedStore,
                                nativeV3 = native.runtime,
                                maxRetainedMessages = maxRetainedMessages,
                                maxMessageChars = maxMessageChars
                            )
                        )
                    )
                ConversationV3NativeDecision.LegacyFallback -> Unit
                ConversationV3NativeDecision.Corrupt ->
                    return PersistentConversationOpenResult.Corrupt
                is ConversationV3NativeDecision.Incompatible ->
                    return PersistentConversationOpenResult.Incompatible(native.reason)
                is ConversationV3NativeDecision.EncryptionUnavailable ->
                    return PersistentConversationOpenResult.EncryptionUnavailable(native.category)
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

private sealed interface ConversationV3LegacyExactRead {
    data class Found(val decoded: ConversationDecodeResult.Decoded) :
        ConversationV3LegacyExactRead
    data object Missing : ConversationV3LegacyExactRead
    data object Corrupt : ConversationV3LegacyExactRead
    data class Incompatible(val reason: String) : ConversationV3LegacyExactRead
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : ConversationV3LegacyExactRead
}

private class ConversationV3MixedSessionRuntime(
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val nativeV3: ConversationV3NativeRuntime,
    private val maxRetainedMessages: Int,
    private val maxMessageChars: Int
) {
    fun reopenResult(
        sessionId: CognitiveConversationSessionId
    ): PersistentConversationReopenResult {
        when (val native = nativeV3.reopenResult(sessionId)) {
            is PersistentConversationReopenResult.Found -> return native
            PersistentConversationReopenResult.Absent -> Unit
            PersistentConversationReopenResult.Corrupt -> return native
            is PersistentConversationReopenResult.Incompatible -> return native
            is PersistentConversationReopenResult.EncryptionUnavailable -> return native
        }

        val tail = ArrayDeque<CognitiveConversationContextMessage>()
        var foundAny = false
        var nextSequence = 1L

        when (val legacy = readLegacy(ConversationPersistentRecordCodec.entityId(sessionId))) {
            is ConversationV3LegacyExactRead.Found -> {
                if (legacy.decoded.chunk ||
                    legacy.decoded.snapshot.sessionId != sessionId ||
                    !validateMessages(legacy.decoded.snapshot.messages, allowTruncatedStart = true)
                ) {
                    return PersistentConversationReopenResult.Corrupt
                }
                val messages = legacy.decoded.snapshot.messages
                if (messages.isNotEmpty()) {
                    foundAny = true
                    nextSequence = messages.last().sequence.value + 1L
                    if (nextSequence <= 0L) return PersistentConversationReopenResult.Corrupt
                    retainTail(tail, messages)
                }
            }
            ConversationV3LegacyExactRead.Missing -> Unit
            ConversationV3LegacyExactRead.Corrupt ->
                return PersistentConversationReopenResult.Corrupt
            is ConversationV3LegacyExactRead.Incompatible ->
                return PersistentConversationReopenResult.Incompatible(legacy.reason)
            is ConversationV3LegacyExactRead.EncryptionUnavailable ->
                return PersistentConversationReopenResult.EncryptionUnavailable(legacy.category)
        }

        while (true) {
            val id = ConversationPersistentRecordCodec.chunkId(sessionId, nextSequence)
            when (val chunk = readLegacy(id)) {
                is ConversationV3LegacyExactRead.Found -> {
                    val messages = chunk.decoded.snapshot.messages
                    if (!chunk.decoded.chunk ||
                        chunk.decoded.snapshot.sessionId != sessionId ||
                        messages.isEmpty() ||
                        !validateMessages(messages, allowTruncatedStart = false) ||
                        messages.first().sequence.value != nextSequence
                    ) {
                        return PersistentConversationReopenResult.Corrupt
                    }
                    foundAny = true
                    retainTail(tail, messages)
                    nextSequence = messages.last().sequence.value + 1L
                    if (nextSequence <= 0L) return PersistentConversationReopenResult.Corrupt
                }
                ConversationV3LegacyExactRead.Missing -> break
                ConversationV3LegacyExactRead.Corrupt ->
                    return PersistentConversationReopenResult.Corrupt
                is ConversationV3LegacyExactRead.Incompatible ->
                    return PersistentConversationReopenResult.Incompatible(chunk.reason)
                is ConversationV3LegacyExactRead.EncryptionUnavailable ->
                    return PersistentConversationReopenResult.EncryptionUnavailable(chunk.category)
            }
        }

        return if (!foundAny) {
            PersistentConversationReopenResult.Absent
        } else {
            PersistentConversationReopenResult.Found(
                CognitiveConversationContextSnapshot(sessionId, tail.toList())
            )
        }
    }

    fun history(
        sessionId: CognitiveConversationSessionId,
        beforeSequenceExclusive: Long,
        maxMessages: Int
    ): PersistentConversationHistoryResult {
        require(maxMessages > 0) { "history page size must be positive" }

        when (val native = nativeV3.reopenResult(sessionId)) {
            is PersistentConversationReopenResult.Found ->
                return nativeV3.history(
                    sessionId,
                    beforeSequenceExclusive,
                    maxMessages
                )
            PersistentConversationReopenResult.Absent -> Unit
            PersistentConversationReopenResult.Corrupt ->
                return PersistentConversationHistoryResult.Corrupt
            is PersistentConversationReopenResult.Incompatible ->
                return PersistentConversationHistoryResult.Corrupt
            is PersistentConversationReopenResult.EncryptionUnavailable ->
                return PersistentConversationHistoryResult.EncryptionUnavailable(native.category)
        }

        val selected = ArrayDeque<CognitiveConversationContextMessage>()
        var foundAny = false
        var nextSequence = 1L

        fun consider(messages: List<CognitiveConversationContextMessage>) {
            for (message in messages) {
                if (message.sequence.value < beforeSequenceExclusive) {
                    selected.addLast(message)
                    while (selected.size > maxMessages) selected.removeFirst()
                }
            }
        }

        when (val legacy = readLegacy(ConversationPersistentRecordCodec.entityId(sessionId))) {
            is ConversationV3LegacyExactRead.Found -> {
                if (legacy.decoded.chunk ||
                    legacy.decoded.snapshot.sessionId != sessionId ||
                    !validateMessages(legacy.decoded.snapshot.messages, allowTruncatedStart = true)
                ) {
                    return PersistentConversationHistoryResult.Corrupt
                }
                val messages = legacy.decoded.snapshot.messages
                if (messages.isNotEmpty()) {
                    foundAny = true
                    consider(messages)
                    nextSequence = messages.last().sequence.value + 1L
                    if (nextSequence <= 0L) return PersistentConversationHistoryResult.Corrupt
                }
            }
            ConversationV3LegacyExactRead.Missing -> Unit
            ConversationV3LegacyExactRead.Corrupt ->
                return PersistentConversationHistoryResult.Corrupt
            is ConversationV3LegacyExactRead.Incompatible ->
                return PersistentConversationHistoryResult.Corrupt
            is ConversationV3LegacyExactRead.EncryptionUnavailable ->
                return PersistentConversationHistoryResult.EncryptionUnavailable(legacy.category)
        }

        while (true) {
            val id = ConversationPersistentRecordCodec.chunkId(sessionId, nextSequence)
            when (val chunk = readLegacy(id)) {
                is ConversationV3LegacyExactRead.Found -> {
                    val messages = chunk.decoded.snapshot.messages
                    if (!chunk.decoded.chunk ||
                        chunk.decoded.snapshot.sessionId != sessionId ||
                        messages.isEmpty() ||
                        !validateMessages(messages, allowTruncatedStart = false) ||
                        messages.first().sequence.value != nextSequence
                    ) {
                        return PersistentConversationHistoryResult.Corrupt
                    }
                    foundAny = true
                    consider(messages)
                    nextSequence = messages.last().sequence.value + 1L
                    if (nextSequence <= 0L) return PersistentConversationHistoryResult.Corrupt
                }
                ConversationV3LegacyExactRead.Missing -> break
                ConversationV3LegacyExactRead.Corrupt ->
                    return PersistentConversationHistoryResult.Corrupt
                is ConversationV3LegacyExactRead.Incompatible ->
                    return PersistentConversationHistoryResult.Corrupt
                is ConversationV3LegacyExactRead.EncryptionUnavailable ->
                    return PersistentConversationHistoryResult.EncryptionUnavailable(chunk.category)
            }
        }

        return if (!foundAny) {
            PersistentConversationHistoryResult.Absent
        } else {
            PersistentConversationHistoryResult.Found(
                CognitiveConversationContextSnapshot(sessionId, selected.toList())
            )
        }
    }

    private fun validateMessages(
        messages: List<CognitiveConversationContextMessage>,
        allowTruncatedStart: Boolean
    ): Boolean {
        if (messages.any { it.content.length > maxMessageChars }) return false
        if (messages.isEmpty()) return true
        val first = messages.first().sequence.value
        if (first <= 0L) return false
        if (!allowTruncatedStart && first <= 0L) return false
        for (index in 1 until messages.size) {
            if (messages[index].sequence.value != messages[index - 1].sequence.value + 1L) {
                return false
            }
        }
        return true
    }

    private fun retainTail(
        tail: ArrayDeque<CognitiveConversationContextMessage>,
        messages: List<CognitiveConversationContextMessage>
    ) {
        for (message in messages) {
            tail.addLast(message)
            while (tail.size > maxRetainedMessages) tail.removeFirst()
        }
    }

    private fun readLegacy(id: PersistentEntityId): ConversationV3LegacyExactRead {
        val plaintext = when (val opened = encryptedStore.open(id)) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected ->
                return if (
                    opened.category == CognitiveEncryptionFailureCategory.INVALID_REQUEST
                ) {
                    ConversationV3LegacyExactRead.Missing
                } else {
                    ConversationV3LegacyExactRead.EncryptionUnavailable(opened.category)
                }
            is CognitiveEncryptionResult.Failed ->
                return ConversationV3LegacyExactRead.EncryptionUnavailable(opened.category)
        }
        val raw = encryptedStore.inspect(id)
            ?: return ConversationV3LegacyExactRead.Corrupt
        val record = raw.record.copy(
            payload = PersistentPayload(plaintext.copyBytes())
        )
        return when (val decoded = ConversationPersistentRecordCodec.decode(record)) {
            is ConversationDecodeResult.Decoded ->
                ConversationV3LegacyExactRead.Found(decoded)
            ConversationDecodeResult.Corrupt ->
                ConversationV3LegacyExactRead.Corrupt
            is ConversationDecodeResult.Incompatible ->
                ConversationV3LegacyExactRead.Incompatible(decoded.reason)
        }
    }
}

private sealed interface ConversationDecodeResult {
    data class Decoded(val snapshot: CognitiveConversationContextSnapshot, val chunk: Boolean) : ConversationDecodeResult
    data object Corrupt : ConversationDecodeResult
    data class Incompatible(val reason: String) : ConversationDecodeResult
}

private object ConversationPersistentRecordCodec {
    private val schemaId = PersistentSchemaId("cognitive-conversation-session")
    private val legacyVersion = PersistentSchemaVersion(1)
    private val chunkVersion = PersistentSchemaVersion(2)
    private const val LEGACY_MAGIC = 0x434E5631
    private const val CHUNK_MAGIC = 0x434E5632

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

    fun entityId(sessionId: CognitiveConversationSessionId): PersistentEntityId {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sessionId.value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return PersistentEntityId("conversation-$digest")
    }

    fun chunkId(sessionId: CognitiveConversationSessionId, firstSequence: Long): PersistentEntityId {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((sessionId.value + ":" + firstSequence).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return PersistentEntityId("conversation-chunk-$digest")
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
