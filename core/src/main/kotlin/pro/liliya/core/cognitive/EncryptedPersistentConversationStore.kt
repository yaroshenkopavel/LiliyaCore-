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
import pro.liliya.core.encryption.EncryptedPersistentRecordPageResult
import pro.liliya.core.persistence.PersistentBackendPageOrder
import pro.liliya.core.persistence.PersistentBackendPageRequest
import pro.liliya.core.persistence.PersistentRecordSnapshot
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecordOwnership
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

internal sealed interface PersistentConversationMigrationFinalizationResult {
    data object Finalized : PersistentConversationMigrationFinalizationResult
    data object AlreadyFinalized : PersistentConversationMigrationFinalizationResult
    data object MissingProof : PersistentConversationMigrationFinalizationResult
    data class StaleProof(val reason: String) :
        PersistentConversationMigrationFinalizationResult
    data object Corrupt : PersistentConversationMigrationFinalizationResult
    data class Incompatible(val reason: String) :
        PersistentConversationMigrationFinalizationResult
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : PersistentConversationMigrationFinalizationResult
    data class Rejected(val reason: String) :
        PersistentConversationMigrationFinalizationResult
    data class Failed(val reason: String) :
        PersistentConversationMigrationFinalizationResult
}

internal sealed interface PersistentConversationMigrationCompletenessResult {
    data class Proven(
        val proof: ConversationV3MigrationCompletenessProof
    ) : PersistentConversationMigrationCompletenessResult

    data class AlreadyProven(
        val proof: ConversationV3MigrationCompletenessProof
    ) : PersistentConversationMigrationCompletenessResult

    data class Incomplete(val reason: String) :
        PersistentConversationMigrationCompletenessResult

    data object Corrupt : PersistentConversationMigrationCompletenessResult

    data class Incompatible(val reason: String) :
        PersistentConversationMigrationCompletenessResult

    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : PersistentConversationMigrationCompletenessResult

    data class Failed(val reason: String) :
        PersistentConversationMigrationCompletenessResult
}

sealed interface PersistentConversationSessionMigrationResult {
    data object Migrated : PersistentConversationSessionMigrationResult
    data object AlreadyMigrated : PersistentConversationSessionMigrationResult
    data object Absent : PersistentConversationSessionMigrationResult
    data class Rejected(val reason: String) : PersistentConversationSessionMigrationResult
    data object Corrupt : PersistentConversationSessionMigrationResult
    data class Incompatible(val reason: String) : PersistentConversationSessionMigrationResult
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : PersistentConversationSessionMigrationResult
}

sealed interface PersistentConversationMigrationPrepareResult {
    data object Prepared : PersistentConversationMigrationPrepareResult
    data object AlreadyPrepared : PersistentConversationMigrationPrepareResult
    data class Rejected(val reason: String) : PersistentConversationMigrationPrepareResult
    data object Corrupt : PersistentConversationMigrationPrepareResult
    data class Incompatible(val reason: String) : PersistentConversationMigrationPrepareResult
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : PersistentConversationMigrationPrepareResult
}

sealed interface PersistentConversationOpenResult {
    data class Opened(val store: EncryptedPersistentConversationStore) : PersistentConversationOpenResult
    data object Corrupt : PersistentConversationOpenResult
    data class Incompatible(val reason: String) : PersistentConversationOpenResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) : PersistentConversationOpenResult
}

sealed interface PersistentConversationConflictRefreshResult {
    data object Unchanged : PersistentConversationConflictRefreshResult
    data object Refreshed : PersistentConversationConflictRefreshResult
    data object ReopenRequired : PersistentConversationConflictRefreshResult
    data object Corrupt : PersistentConversationConflictRefreshResult
    data class Incompatible(val reason: String) :
        PersistentConversationConflictRefreshResult
    data class Failed(val reason: String) :
        PersistentConversationConflictRefreshResult
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

    /**
     * Explicit recovery barrier after an external-writer persistence conflict.
     * The rejected append is never replayed here. A caller must re-read durable state and rebuild
     * the next domain mutation. Mixed mode requires reopening because its format marker may change.
     */
    @Synchronized
    fun refreshAfterExternalWriterConflict(
        sessionId: CognitiveConversationSessionId
    ): PersistentConversationConflictRefreshResult {
        nativeV3?.let { return it.refreshAfterExternalWriterConflict(sessionId) }
        if (mixedV3 != null) {
            return PersistentConversationConflictRefreshResult.ReopenRequired
        }
        return PersistentConversationConflictRefreshResult.Incompatible(
            "conversation metadata refresh requires indexed native-v3 mode"
        )
    }

    @Synchronized
    internal fun finalizeMigrationToNative(
        persistedAt: Instant
    ): PersistentConversationMigrationFinalizationResult {
        if (nativeV3 != null && mixedV3 == null) {
            return PersistentConversationMigrationFinalizationResult.AlreadyFinalized
        }
        val mixed = mixedV3
            ?: return PersistentConversationMigrationFinalizationResult.Incompatible(
                "native finalization requires mixed mode"
            )
        return mixed.finalizeMigrationToNative(persistedAt)
    }

    @Synchronized
    internal fun proveMigrationCompleteness(
        persistedAt: Instant,
        pageSize: Int = 256
    ): PersistentConversationMigrationCompletenessResult {
        require(pageSize in 1..PersistentBackendPageRequest.MAX_PAGE_SIZE) {
            "migration completeness page size is out of bounds"
        }
        val mixed = mixedV3
            ?: return PersistentConversationMigrationCompletenessResult.Incompatible(
                "migration completeness proof requires mixed mode"
            )
        return mixed.proveMigrationCompleteness(persistedAt, pageSize)
    }

    @Synchronized
    fun migrateV1TruncatedSession(
        sessionId: CognitiveConversationSessionId,
        persistedAt: Instant
    ): PersistentConversationSessionMigrationResult {
        val mixed = mixedV3
            ?: return PersistentConversationSessionMigrationResult.Rejected(
                "conversation v1 migration requires mixed mode"
            )
        return mixed.migrateV1TruncatedSession(sessionId, persistedAt)
    }

    fun migrateV2Session(
        sessionId: CognitiveConversationSessionId,
        persistedAt: Instant
    ): PersistentConversationSessionMigrationResult {
        val mixed = mixedV3
            ?: return PersistentConversationSessionMigrationResult.Rejected(
                "conversation v2 migration requires mixed mode"
            )
        return mixed.migrateV2Session(sessionId, persistedAt)
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
        fun prepareMigration(
            encryptedStore: EncryptedPersistentRecordStore,
            activeDek: CognitiveDekReference,
            persistedAt: Instant
        ): PersistentConversationMigrationPrepareResult {
            if (!encryptedStore.supportsIndexedLazyMode()) {
                return PersistentConversationMigrationPrepareResult.Rejected(
                    "conversation migration requires indexed lazy persistence"
                )
            }
            if (encryptedStore.entryCount() == 0L) {
                return PersistentConversationMigrationPrepareResult.Rejected(
                    "empty conversation store does not require migration"
                )
            }

            fun readMarker(
                id: PersistentEntityId,
                decode: (PersistentRecord) -> Any
            ): Any? {
                val plaintext = when (val opened = encryptedStore.open(id)) {
                    is CognitiveEncryptionResult.Success -> opened.value
                    is CognitiveEncryptionResult.Rejected ->
                        return if (
                            opened.category ==
                                CognitiveEncryptionFailureCategory.INVALID_REQUEST
                        ) {
                            null
                        } else {
                            PersistentConversationMigrationPrepareResult.EncryptionUnavailable(
                                opened.category
                            )
                        }
                    is CognitiveEncryptionResult.Failed ->
                        return PersistentConversationMigrationPrepareResult.EncryptionUnavailable(
                            opened.category
                        )
                }
                val raw = encryptedStore.inspect(id)
                    ?: return PersistentConversationMigrationPrepareResult.Corrupt
                return decode(
                    raw.record.copy(
                        payload = PersistentPayload(plaintext.copyBytes())
                    )
                )
            }

            when (
                val native = readMarker(
                    ConversationV3IndexCodec.MARKER_ID
                ) { record -> ConversationV3IndexCodec.decodeMarker(record) }
            ) {
                null -> Unit
                is PersistentConversationMigrationPrepareResult ->
                    return native
                is ConversationV3DecodeResult.Decoded<*> ->
                    return PersistentConversationMigrationPrepareResult.Rejected(
                        "native conversation v3 store does not require migration"
                    )
                ConversationV3DecodeResult.Corrupt ->
                    return PersistentConversationMigrationPrepareResult.Corrupt
                is ConversationV3DecodeResult.Incompatible ->
                    return PersistentConversationMigrationPrepareResult.Incompatible(
                        native.reason
                    )
                else -> return PersistentConversationMigrationPrepareResult.Corrupt
            }

            when (
                val mixed = readMarker(
                    ConversationV3MigrationCodec.MIXED_MARKER_ID
                ) { record ->
                    ConversationV3MigrationCodec.decodeMixedMarker(record)
                }
            ) {
                null -> Unit
                is PersistentConversationMigrationPrepareResult ->
                    return mixed
                is ConversationV3MigrationDecodeResult.Decoded<*> ->
                    return PersistentConversationMigrationPrepareResult.AlreadyPrepared
                ConversationV3MigrationDecodeResult.Corrupt ->
                    return PersistentConversationMigrationPrepareResult.Corrupt
                is ConversationV3MigrationDecodeResult.Incompatible ->
                    return PersistentConversationMigrationPrepareResult.Incompatible(
                        mixed.reason
                    )
                else -> return PersistentConversationMigrationPrepareResult.Corrupt
            }

            val marker =
                ConversationV3MigrationCodec.encodeMixedMarker(persistedAt)
            val bytes = marker.payload.copyBytes()
            val draft = CognitivePersistentRecordDraft(
                id = marker.id,
                schemaId = marker.schemaId,
                schemaVersion = marker.schemaVersion,
                plaintext = CognitivePlaintext(bytes),
                createdAt = marker.createdAt,
                dek = activeDek
            )
            return try {
                when (val installed = encryptedStore.install(draft)) {
                    is CognitiveEncryptionResult.Success ->
                        PersistentConversationMigrationPrepareResult.Prepared
                    is CognitiveEncryptionResult.Rejected ->
                        if (
                            installed.category ==
                                CognitiveEncryptionFailureCategory.PERSISTENCE_CONFLICT
                        ) {
                            PersistentConversationMigrationPrepareResult.Rejected(
                                "conversation migration preparation conflict"
                            )
                        } else {
                            PersistentConversationMigrationPrepareResult.EncryptionUnavailable(
                                installed.category
                            )
                        }
                    is CognitiveEncryptionResult.Failed ->
                        PersistentConversationMigrationPrepareResult.EncryptionUnavailable(
                            installed.category
                        )
                }
            } finally {
                bytes.fill(0)
            }
        }

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
                                activeDek = activeDek,
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
    private val activeDek: CognitiveDekReference,
    private val nativeV3: ConversationV3NativeRuntime,
    private val maxRetainedMessages: Int,
    private val maxMessageChars: Int
) {
    fun finalizeMigrationToNative(
        persistedAt: Instant
    ): PersistentConversationMigrationFinalizationResult {
        val metadata = encryptedStore.indexedMetadataSnapshot()
            ?: return PersistentConversationMigrationFinalizationResult.Incompatible(
                "native finalization requires indexed metadata"
            )

        val proofPlaintext = when (
            val opened = encryptedStore.open(
                ConversationV3MigrationCodec.COMPLETENESS_PROOF_ID
            )
        ) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected ->
                return if (
                    opened.category ==
                        CognitiveEncryptionFailureCategory.INVALID_REQUEST
                ) {
                    PersistentConversationMigrationFinalizationResult.MissingProof
                } else {
                    PersistentConversationMigrationFinalizationResult
                        .EncryptionUnavailable(opened.category)
                }
            is CognitiveEncryptionResult.Failed ->
                return PersistentConversationMigrationFinalizationResult
                    .EncryptionUnavailable(opened.category)
        }
        val proofRaw = encryptedStore.inspect(
            ConversationV3MigrationCodec.COMPLETENESS_PROOF_ID
        ) ?: return PersistentConversationMigrationFinalizationResult.Corrupt
        val proof = when (
            val decoded =
                ConversationV3MigrationCodec.decodeCompletenessProof(
                    proofRaw.record.copy(
                        payload = PersistentPayload(
                            proofPlaintext.copyBytes()
                        )
                    )
                )
        ) {
            is ConversationV3MigrationDecodeResult.Decoded -> decoded.value
            ConversationV3MigrationDecodeResult.Corrupt ->
                return PersistentConversationMigrationFinalizationResult.Corrupt
            is ConversationV3MigrationDecodeResult.Incompatible ->
                return PersistentConversationMigrationFinalizationResult
                    .Incompatible(decoded.reason)
        }

        if (
            metadata.revision != proof.auditedRevision + 1L ||
            metadata.highWatermark != proof.auditedHighWatermark + 1L ||
            metadata.entryCount != proof.auditedEntryCount + 1L ||
            proofRaw.generation.value != proof.auditedHighWatermark + 1L
        ) {
            return PersistentConversationMigrationFinalizationResult.StaleProof(
                "migration completeness proof is not fresh"
            )
        }

        val mixedPlaintext = when (
            val opened = encryptedStore.open(
                ConversationV3MigrationCodec.MIXED_MARKER_ID
            )
        ) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected ->
                if (
                    opened.category ==
                        CognitiveEncryptionFailureCategory.INVALID_REQUEST
                ) {
                    return when (
                        val native = encryptedStore.open(
                            ConversationV3IndexCodec.MARKER_ID
                        )
                    ) {
                        is CognitiveEncryptionResult.Success ->
                            PersistentConversationMigrationFinalizationResult
                                .AlreadyFinalized
                        is CognitiveEncryptionResult.Rejected ->
                            if (
                                native.category ==
                                    CognitiveEncryptionFailureCategory.INVALID_REQUEST
                            ) {
                                PersistentConversationMigrationFinalizationResult
                                    .Corrupt
                            } else {
                                PersistentConversationMigrationFinalizationResult
                                    .EncryptionUnavailable(native.category)
                            }
                        is CognitiveEncryptionResult.Failed ->
                            PersistentConversationMigrationFinalizationResult
                                .EncryptionUnavailable(native.category)
                    }
                } else {
                    return PersistentConversationMigrationFinalizationResult
                        .EncryptionUnavailable(opened.category)
                }
            is CognitiveEncryptionResult.Failed ->
                return PersistentConversationMigrationFinalizationResult
                    .EncryptionUnavailable(opened.category)
        }
        val mixedRaw = encryptedStore.inspect(
            ConversationV3MigrationCodec.MIXED_MARKER_ID
        ) ?: return PersistentConversationMigrationFinalizationResult.Corrupt
        when (
            ConversationV3MigrationCodec.decodeMixedMarker(
                mixedRaw.record.copy(
                    payload = PersistentPayload(mixedPlaintext.copyBytes())
                )
            )
        ) {
            is ConversationV3MigrationDecodeResult.Decoded -> Unit
            ConversationV3MigrationDecodeResult.Corrupt ->
                return PersistentConversationMigrationFinalizationResult.Corrupt
            is ConversationV3MigrationDecodeResult.Incompatible ->
                return PersistentConversationMigrationFinalizationResult
                    .Incompatible(
                        "conversation mixed marker is incompatible"
                    )
        }

        when (val native = encryptedStore.open(ConversationV3IndexCodec.MARKER_ID)) {
            is CognitiveEncryptionResult.Success ->
                return PersistentConversationMigrationFinalizationResult.Corrupt
            is CognitiveEncryptionResult.Rejected ->
                if (
                    native.category !=
                        CognitiveEncryptionFailureCategory.INVALID_REQUEST
                ) {
                    return PersistentConversationMigrationFinalizationResult
                        .EncryptionUnavailable(native.category)
                }
            is CognitiveEncryptionResult.Failed ->
                return PersistentConversationMigrationFinalizationResult
                    .EncryptionUnavailable(native.category)
        }

        val marker = ConversationV3IndexCodec.encodeMarker(persistedAt)
        val bytes = marker.payload.copyBytes()
        val draft = CognitivePersistentRecordDraft(
            id = marker.id,
            schemaId = marker.schemaId,
            schemaVersion = marker.schemaVersion,
            plaintext = CognitivePlaintext(bytes),
            createdAt = marker.createdAt,
            dek = activeDek
        )
        return try {
            when (
                encryptedStore.transitionExact(
                    sourceId = mixedRaw.record.id,
                    sourceGeneration = mixedRaw.generation,
                    replacement = draft
                )
            ) {
                is CognitiveEncryptionResult.Success ->
                    PersistentConversationMigrationFinalizationResult.Finalized
                is CognitiveEncryptionResult.Rejected ->
                    PersistentConversationMigrationFinalizationResult.StaleProof(
                        "migration completeness proof became stale before native finalization"
                    )
                is CognitiveEncryptionResult.Failed ->
                    PersistentConversationMigrationFinalizationResult.Failed(
                        "conversation native marker transition failed"
                    )
            }
        } finally {
            bytes.fill(0)
        }
    }

    fun proveMigrationCompleteness(
        persistedAt: Instant,
        pageSize: Int
    ): PersistentConversationMigrationCompletenessResult {
        val metadata = encryptedStore.indexedMetadataSnapshot()
            ?: return PersistentConversationMigrationCompletenessResult.Incompatible(
                "migration completeness proof requires indexed metadata"
            )

        when (
            val existing =
                encryptedStore.open(
                    ConversationV3MigrationCodec.COMPLETENESS_PROOF_ID
                )
        ) {
            is CognitiveEncryptionResult.Success -> {
                val raw = encryptedStore.inspect(
                    ConversationV3MigrationCodec.COMPLETENESS_PROOF_ID
                ) ?: return PersistentConversationMigrationCompletenessResult.Corrupt
                val proof = when (
                    val decoded =
                        ConversationV3MigrationCodec.decodeCompletenessProof(
                            raw.record.copy(
                                payload = PersistentPayload(
                                    existing.value.copyBytes()
                                )
                            )
                        )
                ) {
                    is ConversationV3MigrationDecodeResult.Decoded ->
                        decoded.value
                    ConversationV3MigrationDecodeResult.Corrupt ->
                        return PersistentConversationMigrationCompletenessResult.Corrupt
                    is ConversationV3MigrationDecodeResult.Incompatible ->
                        return PersistentConversationMigrationCompletenessResult.Incompatible(
                            decoded.reason
                        )
                }
                return if (
                    metadata.revision == proof.auditedRevision + 1L &&
                    metadata.highWatermark ==
                        proof.auditedHighWatermark + 1L &&
                    metadata.entryCount == proof.auditedEntryCount + 1L &&
                    raw.generation.value ==
                        proof.auditedHighWatermark + 1L
                ) {
                    PersistentConversationMigrationCompletenessResult
                        .AlreadyProven(proof)
                } else {
                    PersistentConversationMigrationCompletenessResult
                        .Incomplete(
                            "existing migration completeness proof is stale"
                        )
                }
            }
            is CognitiveEncryptionResult.Rejected ->
                if (
                    existing.category !=
                        CognitiveEncryptionFailureCategory.INVALID_REQUEST
                ) {
                    return PersistentConversationMigrationCompletenessResult
                        .EncryptionUnavailable(existing.category)
                }
            is CognitiveEncryptionResult.Failed ->
                return PersistentConversationMigrationCompletenessResult
                    .EncryptionUnavailable(existing.category)
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var cursor: pro.liliya.core.persistence.PersistentBackendPageCursor? =
            null
        var scannedCount = 0L
        var legacyRecordCount = 0L
        var receiptCount = 0L

        while (true) {
            val page = when (
                val loaded = encryptedStore.decryptedPageResult(
                    PersistentBackendPageRequest(
                        limit = pageSize,
                        order = PersistentBackendPageOrder.OLDEST_FIRST,
                        cursorExclusive = cursor
                    )
                )
            ) {
                EncryptedPersistentRecordPageResult.Empty -> break
                EncryptedPersistentRecordPageResult.Corrupt ->
                    return PersistentConversationMigrationCompletenessResult.Corrupt
                is EncryptedPersistentRecordPageResult.Incompatible ->
                    return PersistentConversationMigrationCompletenessResult
                        .Incompatible(loaded.reason)
                is EncryptedPersistentRecordPageResult.EncryptionUnavailable ->
                    return PersistentConversationMigrationCompletenessResult
                        .EncryptionUnavailable(loaded.category)
                is EncryptedPersistentRecordPageResult.Failed ->
                    return PersistentConversationMigrationCompletenessResult
                        .Failed(loaded.reason)
                is EncryptedPersistentRecordPageResult.Loaded -> loaded
            }

            for (snapshot in page.entries) {
                scannedCount += 1L
                updateCompletenessDigest(digest, snapshot)
                val record = snapshot.record

                if (
                    record.schemaId ==
                        PersistentSchemaId("cognitive-conversation-session")
                ) {
                    legacyRecordCount += 1L
                    val decoded = when (
                        val result =
                            ConversationPersistentRecordCodec.decode(record)
                    ) {
                        is ConversationDecodeResult.Decoded -> result
                        ConversationDecodeResult.Corrupt ->
                            return PersistentConversationMigrationCompletenessResult
                                .Corrupt
                        is ConversationDecodeResult.Incompatible ->
                            return PersistentConversationMigrationCompletenessResult
                                .Incompatible(result.reason)
                    }
                    val receipt = when (
                        val read =
                            readMigrationReceipt(decoded.snapshot.sessionId)
                    ) {
                        is MigrationReceiptAuditRead.Found -> read.receipt
                        MigrationReceiptAuditRead.Missing ->
                            return PersistentConversationMigrationCompletenessResult
                                .Incomplete(
                                    "legacy conversation is not accounted by migration receipt"
                                )
                        MigrationReceiptAuditRead.Corrupt ->
                            return PersistentConversationMigrationCompletenessResult
                                .Corrupt
                        is MigrationReceiptAuditRead.Incompatible ->
                            return PersistentConversationMigrationCompletenessResult
                                .Incompatible(read.reason)
                        is MigrationReceiptAuditRead.EncryptionUnavailable ->
                            return PersistentConversationMigrationCompletenessResult
                                .EncryptionUnavailable(read.category)
                    }
                    val coverage = validateLegacyReceiptCoverage(
                        record = record,
                        decoded = decoded,
                        receipt = receipt
                    )
                    if (coverage != null) return coverage
                } else if (
                    record.schemaId ==
                        ConversationV3MigrationCodec.MIGRATION_RECEIPT_SCHEMA_ID
                ) {
                    receiptCount += 1L
                    val receipt = when (
                        val decoded =
                            ConversationV3MigrationCodec.decodeMigrationReceipt(
                                record
                            )
                    ) {
                        is ConversationV3MigrationDecodeResult.Decoded ->
                            decoded.value
                        ConversationV3MigrationDecodeResult.Corrupt ->
                            return PersistentConversationMigrationCompletenessResult
                                .Corrupt
                        is ConversationV3MigrationDecodeResult.Incompatible ->
                            return PersistentConversationMigrationCompletenessResult
                                .Incompatible(decoded.reason)
                    }
                    validateReceiptSourceAndHead(receipt)?.let {
                        return it
                    }
                } else if (
                    record.id ==
                        ConversationV3MigrationCodec.COMPLETENESS_PROOF_ID
                ) {
                    return PersistentConversationMigrationCompletenessResult.Corrupt
                }
            }

            val next = page.nextCursor ?: break
            if (next == cursor) {
                return PersistentConversationMigrationCompletenessResult.Corrupt
            }
            cursor = next
        }

        if (scannedCount != metadata.entryCount) {
            return PersistentConversationMigrationCompletenessResult.Incomplete(
                "migration completeness scan did not cover indexed entry count"
            )
        }

        val proof = ConversationV3MigrationCompletenessProof(
            auditedRevision = metadata.revision,
            auditedHighWatermark = metadata.highWatermark,
            auditedEntryCount = metadata.entryCount,
            legacyRecordCount = legacyRecordCount,
            receiptCount = receiptCount,
            digestHex = digest.digest().joinToString("") {
                "%02x".format(it)
            }
        )
        val record =
            ConversationV3MigrationCodec.encodeCompletenessProof(
                proof,
                persistedAt
            )
        val bytes = record.payload.copyBytes()
        val draft = CognitivePersistentRecordDraft(
            id = record.id,
            schemaId = record.schemaId,
            schemaVersion = record.schemaVersion,
            plaintext = CognitivePlaintext(bytes),
            createdAt = record.createdAt,
            dek = activeDek
        )
        return try {
            when (val installed = encryptedStore.install(draft)) {
                is CognitiveEncryptionResult.Success ->
                    PersistentConversationMigrationCompletenessResult.Proven(
                        proof
                    )
                is CognitiveEncryptionResult.Rejected ->
                    if (
                        installed.category ==
                            CognitiveEncryptionFailureCategory.PERSISTENCE_CONFLICT
                    ) {
                        PersistentConversationMigrationCompletenessResult
                            .Incomplete(
                                "conversation store changed before completeness proof commit"
                            )
                    } else {
                        PersistentConversationMigrationCompletenessResult
                            .EncryptionUnavailable(installed.category)
                    }
                is CognitiveEncryptionResult.Failed ->
                    PersistentConversationMigrationCompletenessResult.Failed(
                        "migration completeness proof persistence failed"
                    )
            }
        } finally {
            bytes.fill(0)
        }
    }

    fun migrateV1TruncatedSession(
        sessionId: CognitiveConversationSessionId,
        persistedAt: Instant
    ): PersistentConversationSessionMigrationResult {
        val nativeAlreadyPresent = when (
            val native = nativeV3.reopenResult(sessionId)
        ) {
            is PersistentConversationReopenResult.Found -> true
            PersistentConversationReopenResult.Absent -> false
            PersistentConversationReopenResult.Corrupt ->
                return PersistentConversationSessionMigrationResult.Corrupt
            is PersistentConversationReopenResult.Incompatible ->
                return PersistentConversationSessionMigrationResult.Incompatible(
                    native.reason
                )
            is PersistentConversationReopenResult.EncryptionUnavailable ->
                return PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                    native.category
                )
        }

        val sourceId = ConversationPersistentRecordCodec.entityId(sessionId)
        val source = when (val legacy = readLegacy(sourceId)) {
            is ConversationV3LegacyExactRead.Found -> legacy.decoded
            ConversationV3LegacyExactRead.Missing ->
                return PersistentConversationSessionMigrationResult.Absent
            ConversationV3LegacyExactRead.Corrupt ->
                return PersistentConversationSessionMigrationResult.Corrupt
            is ConversationV3LegacyExactRead.Incompatible ->
                return PersistentConversationSessionMigrationResult.Incompatible(
                    legacy.reason
                )
            is ConversationV3LegacyExactRead.EncryptionUnavailable ->
                return PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                    legacy.category
                )
        }

        if (source.chunk ||
            source.snapshot.sessionId != sessionId ||
            source.snapshot.messages.isEmpty() ||
            !validateMessages(
                source.snapshot.messages,
                allowTruncatedStart = true
            )
        ) {
            return PersistentConversationSessionMigrationResult.Corrupt
        }

        val firstRetained = source.snapshot.messages.first().sequence.value
        if (firstRetained <= 1L) {
            return PersistentConversationSessionMigrationResult.Rejected(
                "conversation v1 source is not a truncated-root history"
            )
        }

        when (val locked = installMigrationLock(sessionId, persistedAt)) {
            is PersistentConversationSessionMigrationResult.Migrated -> Unit
            is PersistentConversationSessionMigrationResult.AlreadyMigrated -> Unit
            else -> return locked
        }

        val boundary = ConversationV3TruncatedRootBoundary(
            sessionId = sessionId,
            firstRetainedSequence = firstRetained,
            sourceLegacyEntityId = sourceId
        )
        when (val installed = installTruncatedRootBoundary(boundary, persistedAt)) {
            is PersistentConversationSessionMigrationResult.Migrated -> Unit
            is PersistentConversationSessionMigrationResult.AlreadyMigrated -> Unit
            else -> return installed
        }

        var previousV3Id: PersistentEntityId? = null
        var latestV3Id: PersistentEntityId? = null
        var lastSequence = firstRetained - 1L
        var rootPending = true

        fun stageMessages(
            messages: List<CognitiveConversationContextMessage>
        ): PersistentConversationSessionMigrationResult? {
            var offset = 0
            while (offset < messages.size) {
                val endExclusive = minOf(offset + 2, messages.size)
                val part = messages.subList(offset, endExclusive)
                val snapshot = CognitiveConversationContextSnapshot(
                    sessionId,
                    part
                )

                val staged = if (rootPending) {
                    nativeV3.stageLockedTruncatedRootChunk(
                        boundary = boundary,
                        snapshot = snapshot,
                        persistedAt = persistedAt
                    )
                } else {
                    nativeV3.stageLockedMigrationChunk(
                        linked = ConversationV3LinkedChunk(
                            snapshot = snapshot,
                            previousChunkId = previousV3Id
                        ),
                        persistedAt = persistedAt
                    )
                }
                when (staged) {
                    ConversationV3LockedMigrationResult.Ready,
                    ConversationV3LockedMigrationResult.AlreadyMigrated -> Unit
                    is ConversationV3LockedMigrationResult.Rejected ->
                        return PersistentConversationSessionMigrationResult.Rejected(
                            staged.reason
                        )
                    ConversationV3LockedMigrationResult.Corrupt ->
                        return PersistentConversationSessionMigrationResult.Corrupt
                    is ConversationV3LockedMigrationResult.Incompatible ->
                        return PersistentConversationSessionMigrationResult.Incompatible(
                            staged.reason
                        )
                    is ConversationV3LockedMigrationResult.EncryptionUnavailable ->
                        return PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                            staged.category
                        )
                }

                val first = part.first().sequence.value
                val currentId = ConversationV3IndexCodec.chunkId(
                    sessionId,
                    first
                )
                previousV3Id = currentId
                latestV3Id = currentId
                lastSequence = part.last().sequence.value
                rootPending = false
                offset = endExclusive
            }
            return null
        }

        stageMessages(source.snapshot.messages)?.let { return it }

        if (lastSequence == Long.MAX_VALUE) {
            return PersistentConversationSessionMigrationResult.Corrupt
        }
        var nextSequence = lastSequence + 1L
        while (true) {
            val continuationId =
                ConversationPersistentRecordCodec.chunkId(
                    sessionId,
                    nextSequence
                )
            when (val continuation = readLegacy(continuationId)) {
                ConversationV3LegacyExactRead.Missing -> break
                ConversationV3LegacyExactRead.Corrupt ->
                    return PersistentConversationSessionMigrationResult.Corrupt
                is ConversationV3LegacyExactRead.Incompatible ->
                    return PersistentConversationSessionMigrationResult.Incompatible(
                        continuation.reason
                    )
                is ConversationV3LegacyExactRead.EncryptionUnavailable ->
                    return PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                        continuation.category
                    )
                is ConversationV3LegacyExactRead.Found -> {
                    val decoded = continuation.decoded
                    val messages = decoded.snapshot.messages
                    if (!decoded.chunk ||
                        decoded.snapshot.sessionId != sessionId ||
                        messages.isEmpty() ||
                        messages.first().sequence.value != nextSequence ||
                        !validateMessages(
                            messages,
                            allowTruncatedStart = false
                        )
                    ) {
                        return PersistentConversationSessionMigrationResult.Corrupt
                    }
                    stageMessages(messages)?.let { return it }
                    if (lastSequence == Long.MAX_VALUE) {
                        return PersistentConversationSessionMigrationResult.Corrupt
                    }
                    nextSequence = lastSequence + 1L
                }
            }
        }

        val latest = latestV3Id
            ?: return PersistentConversationSessionMigrationResult.Corrupt
        when (
            val published = nativeV3.publishLockedMigrationHead(
                sessionId = sessionId,
                lastSequence = lastSequence,
                latestChunkId = latest,
                persistedAt = persistedAt
            )
        ) {
            ConversationV3LockedMigrationResult.Ready,
            ConversationV3LockedMigrationResult.AlreadyMigrated -> Unit
            is ConversationV3LockedMigrationResult.Rejected ->
                return PersistentConversationSessionMigrationResult.Rejected(
                    published.reason
                )
            ConversationV3LockedMigrationResult.Corrupt ->
                return PersistentConversationSessionMigrationResult.Corrupt
            is ConversationV3LockedMigrationResult.Incompatible ->
                return PersistentConversationSessionMigrationResult.Incompatible(
                    published.reason
                )
            is ConversationV3LockedMigrationResult.EncryptionUnavailable ->
                return PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                    published.category
                )
        }
        val receipt = ConversationV3MigrationReceipt(
            sessionId = sessionId,
            sourceKind = ConversationV3MigrationSourceKind.V1_TRUNCATED,
            sourceLegacyEntityId = sourceId,
            targetHeadId = ConversationV3IndexCodec.headId(sessionId)
        )
        return when (val accounted = installMigrationReceipt(receipt, persistedAt)) {
            is PersistentConversationSessionMigrationResult.Migrated ->
                if (nativeAlreadyPresent) {
                    PersistentConversationSessionMigrationResult.AlreadyMigrated
                } else {
                    accounted
                }
            else -> accounted
        }
    }

    fun migrateV2Session(
        sessionId: CognitiveConversationSessionId,
        persistedAt: Instant
    ): PersistentConversationSessionMigrationResult {
        val nativeAlreadyPresent = when (
            val native = nativeV3.reopenResult(sessionId)
        ) {
            is PersistentConversationReopenResult.Found -> true
            PersistentConversationReopenResult.Absent -> false
            PersistentConversationReopenResult.Corrupt ->
                return PersistentConversationSessionMigrationResult.Corrupt
            is PersistentConversationReopenResult.Incompatible ->
                return PersistentConversationSessionMigrationResult.Incompatible(
                    native.reason
                )
            is PersistentConversationReopenResult.EncryptionUnavailable ->
                return PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                    native.category
                )
        }

        when (
            val legacyRoot =
                readLegacy(ConversationPersistentRecordCodec.entityId(sessionId))
        ) {
            is ConversationV3LegacyExactRead.Found ->
                return PersistentConversationSessionMigrationResult.Rejected(
                    "conversation v1 migration requires truncated-root handling"
                )
            ConversationV3LegacyExactRead.Missing -> Unit
            ConversationV3LegacyExactRead.Corrupt ->
                return PersistentConversationSessionMigrationResult.Corrupt
            is ConversationV3LegacyExactRead.Incompatible ->
                return PersistentConversationSessionMigrationResult.Incompatible(
                    legacyRoot.reason
                )
            is ConversationV3LegacyExactRead.EncryptionUnavailable ->
                return PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                    legacyRoot.category
                )
        }

        val firstId = ConversationPersistentRecordCodec.chunkId(sessionId, 1L)
        when (val first = readLegacy(firstId)) {
            ConversationV3LegacyExactRead.Missing ->
                return PersistentConversationSessionMigrationResult.Absent
            ConversationV3LegacyExactRead.Corrupt ->
                return PersistentConversationSessionMigrationResult.Corrupt
            is ConversationV3LegacyExactRead.Incompatible ->
                return PersistentConversationSessionMigrationResult.Incompatible(
                    first.reason
                )
            is ConversationV3LegacyExactRead.EncryptionUnavailable ->
                return PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                    first.category
                )
            is ConversationV3LegacyExactRead.Found ->
                if (!first.decoded.chunk ||
                    first.decoded.snapshot.sessionId != sessionId ||
                    first.decoded.snapshot.messages.isEmpty() ||
                    first.decoded.snapshot.messages.first().sequence.value != 1L
                ) {
                    return PersistentConversationSessionMigrationResult.Corrupt
                }
        }

        when (val locked = installMigrationLock(sessionId, persistedAt)) {
            is PersistentConversationSessionMigrationResult.Migrated -> Unit
            is PersistentConversationSessionMigrationResult.AlreadyMigrated -> Unit
            else -> return locked
        }

        var nextSequence = 1L
        var previousV3Id: PersistentEntityId? = null
        var latestV3Id: PersistentEntityId? = null
        var lastSequence = 0L

        while (true) {
            val sourceId =
                ConversationPersistentRecordCodec.chunkId(sessionId, nextSequence)
            when (val source = readLegacy(sourceId)) {
                ConversationV3LegacyExactRead.Missing -> break
                ConversationV3LegacyExactRead.Corrupt ->
                    return PersistentConversationSessionMigrationResult.Corrupt
                is ConversationV3LegacyExactRead.Incompatible ->
                    return PersistentConversationSessionMigrationResult.Incompatible(
                        source.reason
                    )
                is ConversationV3LegacyExactRead.EncryptionUnavailable ->
                    return PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                        source.category
                    )
                is ConversationV3LegacyExactRead.Found -> {
                    val decoded = source.decoded
                    val messages = decoded.snapshot.messages
                    if (!decoded.chunk ||
                        decoded.snapshot.sessionId != sessionId ||
                        messages.isEmpty() ||
                        messages.first().sequence.value != nextSequence ||
                        !validateMessages(messages, allowTruncatedStart = false)
                    ) {
                        return PersistentConversationSessionMigrationResult.Corrupt
                    }

                    val linked = ConversationV3LinkedChunk(
                        snapshot = decoded.snapshot,
                        previousChunkId = previousV3Id
                    )
                    when (
                        val staged =
                            nativeV3.stageLockedMigrationChunk(
                                linked = linked,
                                persistedAt = persistedAt
                            )
                    ) {
                        ConversationV3LockedMigrationResult.Ready -> Unit
                        ConversationV3LockedMigrationResult.AlreadyMigrated ->
                            Unit
                        is ConversationV3LockedMigrationResult.Rejected ->
                            return PersistentConversationSessionMigrationResult.Rejected(
                                staged.reason
                            )
                        ConversationV3LockedMigrationResult.Corrupt ->
                            return PersistentConversationSessionMigrationResult.Corrupt
                        is ConversationV3LockedMigrationResult.Incompatible ->
                            return PersistentConversationSessionMigrationResult.Incompatible(
                                staged.reason
                            )
                        is ConversationV3LockedMigrationResult.EncryptionUnavailable ->
                            return PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                                staged.category
                            )
                    }

                    latestV3Id = ConversationV3IndexCodec.chunkId(
                        sessionId,
                        messages.first().sequence.value
                    )
                    previousV3Id = latestV3Id
                    lastSequence = messages.last().sequence.value
                    if (lastSequence == Long.MAX_VALUE) {
                        return PersistentConversationSessionMigrationResult.Corrupt
                    }
                    nextSequence = lastSequence + 1L
                }
            }
        }

        val latest = latestV3Id
            ?: return PersistentConversationSessionMigrationResult.Absent
        when (
            val published = nativeV3.publishLockedMigrationHead(
                sessionId = sessionId,
                lastSequence = lastSequence,
                latestChunkId = latest,
                persistedAt = persistedAt
            )
        ) {
            ConversationV3LockedMigrationResult.Ready,
            ConversationV3LockedMigrationResult.AlreadyMigrated -> Unit
            is ConversationV3LockedMigrationResult.Rejected ->
                return PersistentConversationSessionMigrationResult.Rejected(
                    published.reason
                )
            ConversationV3LockedMigrationResult.Corrupt ->
                return PersistentConversationSessionMigrationResult.Corrupt
            is ConversationV3LockedMigrationResult.Incompatible ->
                return PersistentConversationSessionMigrationResult.Incompatible(
                    published.reason
                )
            is ConversationV3LockedMigrationResult.EncryptionUnavailable ->
                return PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                    published.category
                )
        }
        val receipt = ConversationV3MigrationReceipt(
            sessionId = sessionId,
            sourceKind = ConversationV3MigrationSourceKind.V2_COMPLETE,
            sourceLegacyEntityId = firstId,
            targetHeadId = ConversationV3IndexCodec.headId(sessionId)
        )
        return when (val accounted = installMigrationReceipt(receipt, persistedAt)) {
            is PersistentConversationSessionMigrationResult.Migrated ->
                if (nativeAlreadyPresent) {
                    PersistentConversationSessionMigrationResult.AlreadyMigrated
                } else {
                    accounted
                }
            else -> accounted
        }
    }

    private sealed interface MigrationReceiptAuditRead {
        data class Found(
            val receipt: ConversationV3MigrationReceipt
        ) : MigrationReceiptAuditRead

        data object Missing : MigrationReceiptAuditRead
        data object Corrupt : MigrationReceiptAuditRead

        data class Incompatible(
            val reason: String
        ) : MigrationReceiptAuditRead

        data class EncryptionUnavailable(
            val category: CognitiveEncryptionFailureCategory
        ) : MigrationReceiptAuditRead
    }

    private fun readMigrationReceipt(
        sessionId: CognitiveConversationSessionId
    ): MigrationReceiptAuditRead {
        val id = ConversationV3MigrationCodec.migrationReceiptId(sessionId)
        val plaintext = when (val opened = encryptedStore.open(id)) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected ->
                return if (
                    opened.category ==
                        CognitiveEncryptionFailureCategory.INVALID_REQUEST
                ) {
                    MigrationReceiptAuditRead.Missing
                } else {
                    MigrationReceiptAuditRead.EncryptionUnavailable(
                        opened.category
                    )
                }
            is CognitiveEncryptionResult.Failed ->
                return MigrationReceiptAuditRead.EncryptionUnavailable(
                    opened.category
                )
        }
        val raw = encryptedStore.inspect(id)
            ?: return MigrationReceiptAuditRead.Corrupt
        return when (
            val decoded =
                ConversationV3MigrationCodec.decodeMigrationReceipt(
                    raw.record.copy(
                        payload = PersistentPayload(
                            plaintext.copyBytes()
                        )
                    )
                )
        ) {
            is ConversationV3MigrationDecodeResult.Decoded ->
                if (decoded.value.sessionId == sessionId) {
                    MigrationReceiptAuditRead.Found(decoded.value)
                } else {
                    MigrationReceiptAuditRead.Corrupt
                }
            ConversationV3MigrationDecodeResult.Corrupt ->
                MigrationReceiptAuditRead.Corrupt
            is ConversationV3MigrationDecodeResult.Incompatible ->
                MigrationReceiptAuditRead.Incompatible(decoded.reason)
        }
    }

    private fun validateLegacyReceiptCoverage(
        record: PersistentRecord,
        decoded: ConversationDecodeResult.Decoded,
        receipt: ConversationV3MigrationReceipt
    ): PersistentConversationMigrationCompletenessResult? {
        val sessionId = decoded.snapshot.sessionId
        if (receipt.sessionId != sessionId ||
            receipt.targetHeadId != ConversationV3IndexCodec.headId(sessionId)
        ) {
            return PersistentConversationMigrationCompletenessResult.Corrupt
        }

        return if (decoded.chunk) {
            val expectedSource = when (receipt.sourceKind) {
                ConversationV3MigrationSourceKind.V2_COMPLETE ->
                    ConversationPersistentRecordCodec.chunkId(sessionId, 1L)
                ConversationV3MigrationSourceKind.V1_TRUNCATED ->
                    ConversationPersistentRecordCodec.entityId(sessionId)
            }
            if (receipt.sourceLegacyEntityId != expectedSource) {
                PersistentConversationMigrationCompletenessResult.Corrupt
            } else {
                null
            }
        } else {
            if (receipt.sourceKind !=
                ConversationV3MigrationSourceKind.V1_TRUNCATED ||
                receipt.sourceLegacyEntityId != record.id ||
                record.id !=
                    ConversationPersistentRecordCodec.entityId(sessionId)
            ) {
                PersistentConversationMigrationCompletenessResult.Corrupt
            } else {
                null
            }
        }
    }

    private fun validateReceiptSourceAndHead(
        receipt: ConversationV3MigrationReceipt
    ): PersistentConversationMigrationCompletenessResult? {
        val sessionId = receipt.sessionId
        var latestV3Id: PersistentEntityId? = null
        var lastSequence = 0L

        when (receipt.sourceKind) {
            ConversationV3MigrationSourceKind.V2_COMPLETE -> {
                val expectedSource =
                    ConversationPersistentRecordCodec.chunkId(
                        sessionId,
                        1L
                    )
                if (receipt.sourceLegacyEntityId != expectedSource) {
                    return PersistentConversationMigrationCompletenessResult
                        .Corrupt
                }

                var nextSequence = 1L
                var foundAny = false
                while (true) {
                    val sourceId =
                        ConversationPersistentRecordCodec.chunkId(
                            sessionId,
                            nextSequence
                        )
                    when (val source = readLegacy(sourceId)) {
                        ConversationV3LegacyExactRead.Missing -> break
                        ConversationV3LegacyExactRead.Corrupt ->
                            return PersistentConversationMigrationCompletenessResult
                                .Corrupt
                        is ConversationV3LegacyExactRead.Incompatible ->
                            return PersistentConversationMigrationCompletenessResult
                                .Incompatible(source.reason)
                        is ConversationV3LegacyExactRead.EncryptionUnavailable ->
                            return PersistentConversationMigrationCompletenessResult
                                .EncryptionUnavailable(source.category)
                        is ConversationV3LegacyExactRead.Found -> {
                            val decoded = source.decoded
                            val messages = decoded.snapshot.messages
                            if (!decoded.chunk ||
                                decoded.snapshot.sessionId != sessionId ||
                                messages.isEmpty() ||
                                messages.first().sequence.value != nextSequence ||
                                !validateMessages(
                                    messages,
                                    allowTruncatedStart = false
                                )
                            ) {
                                return PersistentConversationMigrationCompletenessResult
                                    .Corrupt
                            }
                            foundAny = true
                            latestV3Id =
                                ConversationV3IndexCodec.chunkId(
                                    sessionId,
                                    messages.first().sequence.value
                                )
                            lastSequence =
                                messages.last().sequence.value
                            if (lastSequence == Long.MAX_VALUE) {
                                return PersistentConversationMigrationCompletenessResult
                                    .Corrupt
                            }
                            nextSequence = lastSequence + 1L
                        }
                    }
                }
                if (!foundAny) {
                    return PersistentConversationMigrationCompletenessResult
                        .Incomplete(
                            "migration receipt source v2 chain is missing"
                        )
                }
            }

            ConversationV3MigrationSourceKind.V1_TRUNCATED -> {
                val expectedSource =
                    ConversationPersistentRecordCodec.entityId(sessionId)
                if (receipt.sourceLegacyEntityId != expectedSource) {
                    return PersistentConversationMigrationCompletenessResult
                        .Corrupt
                }

                val root = when (
                    val source = readLegacy(expectedSource)
                ) {
                    is ConversationV3LegacyExactRead.Found ->
                        source.decoded
                    ConversationV3LegacyExactRead.Missing ->
                        return PersistentConversationMigrationCompletenessResult
                            .Incomplete(
                                "migration receipt source v1 root is missing"
                            )
                    ConversationV3LegacyExactRead.Corrupt ->
                        return PersistentConversationMigrationCompletenessResult
                            .Corrupt
                    is ConversationV3LegacyExactRead.Incompatible ->
                        return PersistentConversationMigrationCompletenessResult
                            .Incompatible(source.reason)
                    is ConversationV3LegacyExactRead.EncryptionUnavailable ->
                        return PersistentConversationMigrationCompletenessResult
                            .EncryptionUnavailable(source.category)
                }
                val rootMessages = root.snapshot.messages
                if (root.chunk ||
                    root.snapshot.sessionId != sessionId ||
                    rootMessages.isEmpty() ||
                    rootMessages.first().sequence.value <= 1L ||
                    !validateMessages(
                        rootMessages,
                        allowTruncatedStart = true
                    )
                ) {
                    return PersistentConversationMigrationCompletenessResult
                        .Corrupt
                }

                val boundaryId =
                    ConversationV3MigrationCodec.truncatedRootId(
                        sessionId
                    )
                val boundaryPlaintext = when (
                    val opened = encryptedStore.open(boundaryId)
                ) {
                    is CognitiveEncryptionResult.Success -> opened.value
                    is CognitiveEncryptionResult.Rejected ->
                        return if (
                            opened.category ==
                                CognitiveEncryptionFailureCategory.INVALID_REQUEST
                        ) {
                            PersistentConversationMigrationCompletenessResult
                                .Incomplete(
                                    "truncated migration boundary is missing"
                                )
                        } else {
                            PersistentConversationMigrationCompletenessResult
                                .EncryptionUnavailable(opened.category)
                        }
                    is CognitiveEncryptionResult.Failed ->
                        return PersistentConversationMigrationCompletenessResult
                            .EncryptionUnavailable(opened.category)
                }
                val boundaryRaw = encryptedStore.inspect(boundaryId)
                    ?: return PersistentConversationMigrationCompletenessResult
                        .Corrupt
                val boundary = when (
                    val decoded =
                        ConversationV3MigrationCodec.decodeTruncatedRoot(
                            boundaryRaw.record.copy(
                                payload = PersistentPayload(
                                    boundaryPlaintext.copyBytes()
                                )
                            )
                        )
                ) {
                    is ConversationV3MigrationDecodeResult.Decoded ->
                        decoded.value
                    ConversationV3MigrationDecodeResult.Corrupt ->
                        return PersistentConversationMigrationCompletenessResult
                            .Corrupt
                    is ConversationV3MigrationDecodeResult.Incompatible ->
                        return PersistentConversationMigrationCompletenessResult
                            .Incompatible(decoded.reason)
                }
                if (boundary.sessionId != sessionId ||
                    boundary.firstRetainedSequence !=
                        rootMessages.first().sequence.value ||
                    boundary.sourceLegacyEntityId != expectedSource
                ) {
                    return PersistentConversationMigrationCompletenessResult
                        .Corrupt
                }

                var offset = 0
                while (offset < rootMessages.size) {
                    val partEnd =
                        minOf(offset + 2, rootMessages.size)
                    val first =
                        rootMessages[offset].sequence.value
                    latestV3Id =
                        ConversationV3IndexCodec.chunkId(
                            sessionId,
                            first
                        )
                    lastSequence =
                        rootMessages[partEnd - 1].sequence.value
                    offset = partEnd
                }

                if (lastSequence == Long.MAX_VALUE) {
                    return PersistentConversationMigrationCompletenessResult
                        .Corrupt
                }
                var nextSequence = lastSequence + 1L
                while (true) {
                    val sourceId =
                        ConversationPersistentRecordCodec.chunkId(
                            sessionId,
                            nextSequence
                        )
                    when (val source = readLegacy(sourceId)) {
                        ConversationV3LegacyExactRead.Missing -> break
                        ConversationV3LegacyExactRead.Corrupt ->
                            return PersistentConversationMigrationCompletenessResult
                                .Corrupt
                        is ConversationV3LegacyExactRead.Incompatible ->
                            return PersistentConversationMigrationCompletenessResult
                                .Incompatible(source.reason)
                        is ConversationV3LegacyExactRead.EncryptionUnavailable ->
                            return PersistentConversationMigrationCompletenessResult
                                .EncryptionUnavailable(source.category)
                        is ConversationV3LegacyExactRead.Found -> {
                            val decoded = source.decoded
                            val messages = decoded.snapshot.messages
                            if (!decoded.chunk ||
                                decoded.snapshot.sessionId != sessionId ||
                                messages.isEmpty() ||
                                messages.first().sequence.value != nextSequence ||
                                !validateMessages(
                                    messages,
                                    allowTruncatedStart = false
                                )
                            ) {
                                return PersistentConversationMigrationCompletenessResult
                                    .Corrupt
                            }
                            latestV3Id =
                                ConversationV3IndexCodec.chunkId(
                                    sessionId,
                                    messages.first().sequence.value
                                )
                            lastSequence =
                                messages.last().sequence.value
                            if (lastSequence == Long.MAX_VALUE) {
                                return PersistentConversationMigrationCompletenessResult
                                    .Corrupt
                            }
                            nextSequence = lastSequence + 1L
                        }
                    }
                }
            }
        }

        val latest = latestV3Id
            ?: return PersistentConversationMigrationCompletenessResult
                .Incomplete(
                    "migration receipt has no retained source history"
                )
        if (!validateMigrationHead(
                sessionId = sessionId,
                lastSequence = lastSequence,
                latestChunkId = latest
            )
        ) {
            return PersistentConversationMigrationCompletenessResult
                .Incomplete(
                    "migration receipt target head does not match source history"
                )
        }

        return when (val reopened = nativeV3.reopenResult(sessionId)) {
            is PersistentConversationReopenResult.Found -> null
            PersistentConversationReopenResult.Absent ->
                PersistentConversationMigrationCompletenessResult.Incomplete(
                    "migration receipt target session is absent"
                )
            PersistentConversationReopenResult.Corrupt ->
                PersistentConversationMigrationCompletenessResult.Corrupt
            is PersistentConversationReopenResult.Incompatible ->
                PersistentConversationMigrationCompletenessResult.Incompatible(
                    reopened.reason
                )
            is PersistentConversationReopenResult.EncryptionUnavailable ->
                PersistentConversationMigrationCompletenessResult
                    .EncryptionUnavailable(reopened.category)
        }
    }

    private fun validateMigrationHead(
        sessionId: CognitiveConversationSessionId,
        lastSequence: Long,
        latestChunkId: PersistentEntityId
    ): Boolean {
        val headId = ConversationV3IndexCodec.headId(sessionId)
        val plaintext = when (val opened = encryptedStore.open(headId)) {
            is CognitiveEncryptionResult.Success -> opened.value
            else -> return false
        }
        val raw = encryptedStore.inspect(headId) ?: return false
        return when (
            val decoded =
                ConversationV3IndexCodec.decodeHead(
                    raw.record.copy(
                        payload = PersistentPayload(
                            plaintext.copyBytes()
                        )
                    )
                )
        ) {
            is ConversationV3DecodeResult.Decoded ->
                decoded.value.sessionId == sessionId &&
                    decoded.value.lastSequence == lastSequence &&
                    decoded.value.latestChunkId == latestChunkId
            else -> false
        }
    }

    private fun updateCompletenessDigest(
        digest: MessageDigest,
        snapshot: PersistentRecordSnapshot
    ) {
        val record = snapshot.record
        val payload = record.payload.copyBytes()
        val encoded = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                fun writeString(value: String) {
                    val bytes =
                        value.toByteArray(StandardCharsets.UTF_8)
                    data.writeInt(bytes.size)
                    data.write(bytes)
                }
                writeString(record.id.value)
                writeString(record.schemaId.value)
                data.writeInt(record.schemaVersion.value)
                data.writeLong(snapshot.generation.value)
                data.writeLong(record.createdAt.epochSecond)
                data.writeInt(record.createdAt.nano)
                data.writeInt(payload.size)
                data.write(payload)
            }
            output.toByteArray()
        }
        digest.update(encoded)
        payload.fill(0)
        encoded.fill(0)
    }

    private fun installMigrationReceipt(
        receipt: ConversationV3MigrationReceipt,
        persistedAt: Instant
    ): PersistentConversationSessionMigrationResult {
        val record = ConversationV3MigrationCodec.encodeMigrationReceipt(
            receipt,
            persistedAt
        )
        when (val existing = encryptedStore.open(record.id)) {
            is CognitiveEncryptionResult.Success -> {
                val raw = encryptedStore.inspect(record.id)
                    ?: return PersistentConversationSessionMigrationResult.Corrupt
                return when (
                    val decoded =
                        ConversationV3MigrationCodec.decodeMigrationReceipt(
                            raw.record.copy(
                                payload = PersistentPayload(
                                    existing.value.copyBytes()
                                )
                            )
                        )
                ) {
                    is ConversationV3MigrationDecodeResult.Decoded ->
                        if (decoded.value == receipt) {
                            PersistentConversationSessionMigrationResult.AlreadyMigrated
                        } else {
                            PersistentConversationSessionMigrationResult.Corrupt
                        }
                    ConversationV3MigrationDecodeResult.Corrupt ->
                        PersistentConversationSessionMigrationResult.Corrupt
                    is ConversationV3MigrationDecodeResult.Incompatible ->
                        PersistentConversationSessionMigrationResult.Incompatible(
                            decoded.reason
                        )
                }
            }
            is CognitiveEncryptionResult.Rejected ->
                if (
                    existing.category !=
                        CognitiveEncryptionFailureCategory.INVALID_REQUEST
                ) {
                    return PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                        existing.category
                    )
                }
            is CognitiveEncryptionResult.Failed ->
                return PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                    existing.category
                )
        }

        val bytes = record.payload.copyBytes()
        val draft = CognitivePersistentRecordDraft(
            id = record.id,
            schemaId = record.schemaId,
            schemaVersion = record.schemaVersion,
            plaintext = CognitivePlaintext(bytes),
            createdAt = record.createdAt,
            dek = activeDek
        )
        return try {
            when (val installed = encryptedStore.install(draft)) {
                is CognitiveEncryptionResult.Success ->
                    PersistentConversationSessionMigrationResult.Migrated
                is CognitiveEncryptionResult.Rejected ->
                    if (
                        installed.category ==
                            CognitiveEncryptionFailureCategory.PERSISTENCE_CONFLICT
                    ) {
                        PersistentConversationSessionMigrationResult.Rejected(
                            "conversation migration receipt conflict"
                        )
                    } else {
                        PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                            installed.category
                        )
                    }
                is CognitiveEncryptionResult.Failed ->
                    PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                        installed.category
                    )
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun installTruncatedRootBoundary(
        boundary: ConversationV3TruncatedRootBoundary,
        persistedAt: Instant
    ): PersistentConversationSessionMigrationResult {
        val record = ConversationV3MigrationCodec.encodeTruncatedRoot(
            boundary,
            persistedAt
        )

        when (val existing = encryptedStore.open(record.id)) {
            is CognitiveEncryptionResult.Success -> {
                val raw = encryptedStore.inspect(record.id)
                    ?: return PersistentConversationSessionMigrationResult.Corrupt
                return when (
                    val decoded =
                        ConversationV3MigrationCodec.decodeTruncatedRoot(
                            raw.record.copy(
                                payload = PersistentPayload(
                                    existing.value.copyBytes()
                                )
                            )
                        )
                ) {
                    is ConversationV3MigrationDecodeResult.Decoded ->
                        if (decoded.value == boundary) {
                            PersistentConversationSessionMigrationResult.AlreadyMigrated
                        } else {
                            PersistentConversationSessionMigrationResult.Corrupt
                        }
                    ConversationV3MigrationDecodeResult.Corrupt ->
                        PersistentConversationSessionMigrationResult.Corrupt
                    is ConversationV3MigrationDecodeResult.Incompatible ->
                        PersistentConversationSessionMigrationResult.Incompatible(
                            decoded.reason
                        )
                }
            }
            is CognitiveEncryptionResult.Rejected ->
                if (
                    existing.category !=
                        CognitiveEncryptionFailureCategory.INVALID_REQUEST
                ) {
                    return PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                        existing.category
                    )
                }
            is CognitiveEncryptionResult.Failed ->
                return PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                    existing.category
                )
        }

        val bytes = record.payload.copyBytes()
        val draft = CognitivePersistentRecordDraft(
            id = record.id,
            schemaId = record.schemaId,
            schemaVersion = record.schemaVersion,
            plaintext = CognitivePlaintext(bytes),
            createdAt = record.createdAt,
            dek = activeDek
        )
        return try {
            when (val installed = encryptedStore.install(draft)) {
                is CognitiveEncryptionResult.Success ->
                    PersistentConversationSessionMigrationResult.Migrated
                is CognitiveEncryptionResult.Rejected ->
                    if (
                        installed.category ==
                            CognitiveEncryptionFailureCategory.PERSISTENCE_CONFLICT
                    ) {
                        PersistentConversationSessionMigrationResult.Rejected(
                            "conversation truncated-root boundary conflict"
                        )
                    } else {
                        PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                            installed.category
                        )
                    }
                is CognitiveEncryptionResult.Failed ->
                    PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                        installed.category
                    )
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun installMigrationLock(
        sessionId: CognitiveConversationSessionId,
        persistedAt: Instant
    ): PersistentConversationSessionMigrationResult {
        val lockRecord = ConversationV3MigrationCodec.encodeMigrationLock(
            ConversationV3MigrationLock(sessionId),
            persistedAt
        )
        val existing = encryptedStore.open(lockRecord.id)
        when (existing) {
            is CognitiveEncryptionResult.Success -> {
                val raw = encryptedStore.inspect(lockRecord.id)
                    ?: return PersistentConversationSessionMigrationResult.Corrupt
                val decoded = ConversationV3MigrationCodec.decodeMigrationLock(
                    raw.record.copy(
                        payload = PersistentPayload(existing.value.copyBytes())
                    )
                )
                return when (decoded) {
                    is ConversationV3MigrationDecodeResult.Decoded ->
                        if (decoded.value.sessionId == sessionId) {
                            PersistentConversationSessionMigrationResult.AlreadyMigrated
                        } else {
                            PersistentConversationSessionMigrationResult.Corrupt
                        }
                    ConversationV3MigrationDecodeResult.Corrupt ->
                        PersistentConversationSessionMigrationResult.Corrupt
                    is ConversationV3MigrationDecodeResult.Incompatible ->
                        PersistentConversationSessionMigrationResult.Incompatible(
                            decoded.reason
                        )
                }
            }
            is CognitiveEncryptionResult.Rejected ->
                if (
                    existing.category !=
                        CognitiveEncryptionFailureCategory.INVALID_REQUEST
                ) {
                    return PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                        existing.category
                    )
                }
            is CognitiveEncryptionResult.Failed ->
                return PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                    existing.category
                )
        }

        val bytes = lockRecord.payload.copyBytes()
        val draft = CognitivePersistentRecordDraft(
            id = lockRecord.id,
            schemaId = lockRecord.schemaId,
            schemaVersion = lockRecord.schemaVersion,
            plaintext = CognitivePlaintext(bytes),
            createdAt = lockRecord.createdAt,
            dek = activeDek
        )
        return try {
            when (val installed = encryptedStore.install(draft)) {
                is CognitiveEncryptionResult.Success ->
                    PersistentConversationSessionMigrationResult.Migrated
                is CognitiveEncryptionResult.Rejected ->
                    if (
                        installed.category ==
                            CognitiveEncryptionFailureCategory.PERSISTENCE_CONFLICT
                    ) {
                        PersistentConversationSessionMigrationResult.Rejected(
                            "conversation migration lock conflict"
                        )
                    } else {
                        PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                            installed.category
                        )
                    }
                is CognitiveEncryptionResult.Failed ->
                    PersistentConversationSessionMigrationResult.EncryptionUnavailable(
                        installed.category
                    )
            }
        } finally {
            bytes.fill(0)
        }
    }

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
