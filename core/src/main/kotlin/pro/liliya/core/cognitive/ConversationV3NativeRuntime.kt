package pro.liliya.core.cognitive

import java.time.Instant
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitivePersistentRecordDraft
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentMetadataRefreshResult
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordOwnership
import pro.liliya.core.persistence.PersistentRecordSnapshot

internal sealed interface ConversationV3NativeDecision {
    data class Native(val runtime: ConversationV3NativeRuntime) : ConversationV3NativeDecision
    data class Mixed(val runtime: ConversationV3NativeRuntime) : ConversationV3NativeDecision
    data object LegacyFallback : ConversationV3NativeDecision
    data object Corrupt : ConversationV3NativeDecision
    data class Incompatible(val reason: String) : ConversationV3NativeDecision
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : ConversationV3NativeDecision
}

private sealed interface ConversationV3RecordRead {
    data class Found(
        val record: PersistentRecord,
        val generation: PersistentGeneration
    ) : ConversationV3RecordRead

    data object Missing : ConversationV3RecordRead
    data object Corrupt : ConversationV3RecordRead
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : ConversationV3RecordRead
}

private sealed interface ConversationV3SessionLoad {
    data class Found(val entry: ConversationV3NativeEntry) : ConversationV3SessionLoad
    data object Absent : ConversationV3SessionLoad
    data object Corrupt : ConversationV3SessionLoad
    data class Incompatible(val reason: String) : ConversationV3SessionLoad
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : ConversationV3SessionLoad
}

private sealed interface ConversationV3PersistResult {
    data class Persisted(val ownership: PersistentRecordOwnership) : ConversationV3PersistResult
    data class Rejected(val reason: String) : ConversationV3PersistResult
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : ConversationV3PersistResult
    data class Failed(val reason: String) : ConversationV3PersistResult
}

internal sealed interface ConversationV3LockedMigrationResult {
    data object Ready : ConversationV3LockedMigrationResult
    data object AlreadyMigrated : ConversationV3LockedMigrationResult
    data class Rejected(val reason: String) : ConversationV3LockedMigrationResult
    data object Corrupt : ConversationV3LockedMigrationResult
    data class Incompatible(val reason: String) : ConversationV3LockedMigrationResult
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : ConversationV3LockedMigrationResult
}

private data class ConversationV3NativeEntry(
    val snapshot: CognitiveConversationContextSnapshot,
    val lastSequence: Long,
    val latestChunkId: PersistentEntityId,
    val headGeneration: PersistentGeneration
)

/**
 * Native lazy conversation archive for indexed persistence.
 *
 * Startup reads only the fixed encrypted format marker. Session reopen reads one deterministic
 * encrypted head and then walks linked immutable chunks backward until the working-context budget
 * is satisfied. Legacy v1/v2 archives are intentionally handled by the existing scan path.
 */
internal class ConversationV3NativeRuntime private constructor(
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val activeDek: CognitiveDekReference,
    private val maxRetainedMessages: Int,
    private val maxMessageChars: Int
) {
    private val cache = LinkedHashMap<CognitiveConversationSessionId, ConversationV3NativeEntry>()

    @Synchronized
    fun refreshAfterExternalWriterConflict(
        sessionId: CognitiveConversationSessionId
    ): PersistentConversationConflictRefreshResult {
        cache.remove(sessionId)
        return when (val refreshed = encryptedStore.refreshIndexedMetadata()) {
            EncryptedPersistentMetadataRefreshResult.Unchanged ->
                PersistentConversationConflictRefreshResult.Unchanged
            is EncryptedPersistentMetadataRefreshResult.Refreshed ->
                PersistentConversationConflictRefreshResult.Refreshed
            EncryptedPersistentMetadataRefreshResult.Corrupt ->
                PersistentConversationConflictRefreshResult.Corrupt
            is EncryptedPersistentMetadataRefreshResult.Incompatible ->
                PersistentConversationConflictRefreshResult.Incompatible(
                    refreshed.reason
                )
            is EncryptedPersistentMetadataRefreshResult.Failed ->
                PersistentConversationConflictRefreshResult.Failed(
                    refreshed.reason
                )
        }
    }

    @Synchronized
    fun stageLockedTruncatedRootChunk(
        boundary: ConversationV3TruncatedRootBoundary,
        snapshot: CognitiveConversationContextSnapshot,
        persistedAt: Instant
    ): ConversationV3LockedMigrationResult {
        when (val lock = readMigrationLock(snapshot.sessionId)) {
            ConversationV3LockedMigrationResult.Ready -> Unit
            else -> return lock
        }
        if (snapshot.sessionId != boundary.sessionId ||
            snapshot.messages.isEmpty() ||
            snapshot.messages.size > 2 ||
            snapshot.messages.first().sequence.value !=
                boundary.firstRetainedSequence ||
            snapshot.messages.any { it.content.length > maxMessageChars }
        ) {
            return ConversationV3LockedMigrationResult.Corrupt
        }

        when (
            val boundaryRead = readPlainRecord(
                ConversationV3MigrationCodec.truncatedRootId(
                    snapshot.sessionId
                )
            )
        ) {
            is ConversationV3RecordRead.Found ->
                when (
                    val decoded =
                        ConversationV3MigrationCodec.decodeTruncatedRoot(
                            boundaryRead.record
                        )
                ) {
                    is ConversationV3MigrationDecodeResult.Decoded ->
                        if (decoded.value != boundary) {
                            return ConversationV3LockedMigrationResult.Corrupt
                        }
                    ConversationV3MigrationDecodeResult.Corrupt ->
                        return ConversationV3LockedMigrationResult.Corrupt
                    is ConversationV3MigrationDecodeResult.Incompatible ->
                        return ConversationV3LockedMigrationResult.Incompatible(
                            decoded.reason
                        )
                }
            ConversationV3RecordRead.Missing,
            ConversationV3RecordRead.Corrupt ->
                return ConversationV3LockedMigrationResult.Corrupt
            is ConversationV3RecordRead.EncryptionUnavailable ->
                return ConversationV3LockedMigrationResult.EncryptionUnavailable(
                    boundaryRead.category
                )
        }

        val expected = ConversationV3TruncatedRootChunk(snapshot)
        val record = ConversationV3MigrationCodec.encodeTruncatedRootChunk(
            expected,
            persistedAt
        )
        val draft = draft(record)
        return when (val installed = encryptedStore.install(draft)) {
            is CognitiveEncryptionResult.Success ->
                ConversationV3LockedMigrationResult.Ready
            is CognitiveEncryptionResult.Rejected ->
                if (
                    installed.category !=
                        CognitiveEncryptionFailureCategory.PERSISTENCE_CONFLICT
                ) {
                    ConversationV3LockedMigrationResult.EncryptionUnavailable(
                        installed.category
                    )
                } else {
                    when (val read = readPlainRecord(record.id)) {
                        is ConversationV3RecordRead.Found ->
                            when (
                                val decoded =
                                    ConversationV3MigrationCodec.decodeTruncatedRootChunk(
                                        read.record
                                    )
                            ) {
                                is ConversationV3MigrationDecodeResult.Decoded ->
                                    if (decoded.value == expected) {
                                        ConversationV3LockedMigrationResult.Ready
                                    } else {
                                        ConversationV3LockedMigrationResult.Rejected(
                                            "conversation truncated-root chunk conflicts with durable history"
                                        )
                                    }
                                else ->
                                    ConversationV3LockedMigrationResult.Rejected(
                                        "conversation truncated-root chunk conflicts with durable history"
                                    )
                            }
                        else ->
                            ConversationV3LockedMigrationResult.Rejected(
                                "conversation truncated-root chunk conflicts with durable history"
                            )
                    }
                }
            is CognitiveEncryptionResult.Failed ->
                ConversationV3LockedMigrationResult.Rejected(
                    "encrypted conversation truncated-root persistence failed"
                )
        }
    }

    @Synchronized
    fun stageLockedMigrationChunk(
        linked: ConversationV3LinkedChunk,
        persistedAt: Instant
    ): ConversationV3LockedMigrationResult {
        when (val lock = readMigrationLock(linked.snapshot.sessionId)) {
            ConversationV3LockedMigrationResult.Ready -> Unit
            else -> return lock
        }
        if (linked.snapshot.messages.any { it.content.length > maxMessageChars }) {
            return ConversationV3LockedMigrationResult.Incompatible(
                "durable conversation exceeds configured reconstruction bounds"
            )
        }
        val record = ConversationV3IndexCodec.encodeChunk(linked, persistedAt)
        return when (val persisted = persistChunkOrValidateExisting(record, linked)) {
            is ConversationV3PersistResult.Persisted ->
                ConversationV3LockedMigrationResult.Ready
            is ConversationV3PersistResult.Rejected ->
                ConversationV3LockedMigrationResult.Rejected(persisted.reason)
            is ConversationV3PersistResult.EncryptionUnavailable ->
                ConversationV3LockedMigrationResult.EncryptionUnavailable(
                    persisted.category
                )
            is ConversationV3PersistResult.Failed ->
                ConversationV3LockedMigrationResult.Rejected(persisted.reason)
        }
    }

    @Synchronized
    fun publishLockedMigrationHead(
        sessionId: CognitiveConversationSessionId,
        lastSequence: Long,
        latestChunkId: PersistentEntityId,
        persistedAt: Instant
    ): ConversationV3LockedMigrationResult {
        when (val lock = readMigrationLock(sessionId)) {
            ConversationV3LockedMigrationResult.Ready -> Unit
            else -> return lock
        }

        val existing = when (val read = readPlainRecord(ConversationV3IndexCodec.headId(sessionId))) {
            is ConversationV3RecordRead.Found ->
                when (val decoded = ConversationV3IndexCodec.decodeHead(read.record)) {
                    is ConversationV3DecodeResult.Decoded -> decoded.value
                    ConversationV3DecodeResult.Corrupt ->
                        return ConversationV3LockedMigrationResult.Corrupt
                    is ConversationV3DecodeResult.Incompatible ->
                        return ConversationV3LockedMigrationResult.Incompatible(
                            decoded.reason
                        )
                }
            ConversationV3RecordRead.Missing -> null
            ConversationV3RecordRead.Corrupt ->
                return ConversationV3LockedMigrationResult.Corrupt
            is ConversationV3RecordRead.EncryptionUnavailable ->
                return ConversationV3LockedMigrationResult.EncryptionUnavailable(
                    read.category
                )
        }
        if (existing != null) {
            return if (
                existing.sessionId == sessionId &&
                existing.lastSequence == lastSequence &&
                existing.latestChunkId == latestChunkId
            ) {
                ConversationV3LockedMigrationResult.AlreadyMigrated
            } else {
                ConversationV3LockedMigrationResult.Rejected(
                    "conversation v3 head conflicts with migration source"
                )
            }
        }

        val head = ConversationV3SessionHead(
            sessionId = sessionId,
            lastSequence = lastSequence,
            latestChunkId = latestChunkId
        )
        return when (
            val persisted = persistHead(
                head = head,
                previousGeneration = null,
                persistedAt = persistedAt
            )
        ) {
            is ConversationV3PersistResult.Persisted -> {
                cache.remove(sessionId)
                ConversationV3LockedMigrationResult.Ready
            }
            is ConversationV3PersistResult.Rejected ->
                ConversationV3LockedMigrationResult.Rejected(persisted.reason)
            is ConversationV3PersistResult.EncryptionUnavailable ->
                ConversationV3LockedMigrationResult.EncryptionUnavailable(
                    persisted.category
                )
            is ConversationV3PersistResult.Failed ->
                ConversationV3LockedMigrationResult.Rejected(persisted.reason)
        }
    }

    @Synchronized
    fun append(
        sessionId: CognitiveConversationSessionId,
        message: CognitiveConversationContextMessage,
        persistedAt: Instant
    ): PersistentConversationAppendResult {
        if (message.content.length > maxMessageChars) {
            return PersistentConversationAppendResult.Rejected(
                "conversation message exceeds configured bound"
            )
        }

        val current = when (val loaded = loadSession(sessionId)) {
            is ConversationV3SessionLoad.Found -> loaded.entry
            ConversationV3SessionLoad.Absent -> null
            ConversationV3SessionLoad.Corrupt ->
                return PersistentConversationAppendResult.Failed(
                    "conversation v3 archive is corrupt"
                )
            is ConversationV3SessionLoad.Incompatible ->
                return PersistentConversationAppendResult.Failed(loaded.reason)
            is ConversationV3SessionLoad.EncryptionUnavailable ->
                return PersistentConversationAppendResult.EncryptionUnavailable(loaded.category)
        }

        if (current == null && message.sequence.value != 1L) {
            return PersistentConversationAppendResult.Rejected(
                "new conversation must begin at sequence 1"
            )
        }
        if (current != null) {
            val existing = current.snapshot.messages.firstOrNull {
                it.sequence == message.sequence
            }
            if (existing != null) {
                return if (existing == message) {
                    PersistentConversationAppendResult.AlreadyPresent(current.snapshot)
                } else {
                    PersistentConversationAppendResult.Rejected(
                        "conversation sequence conflicts with durable history"
                    )
                }
            }
            if (message.sequence.value != current.lastSequence + 1L) {
                return PersistentConversationAppendResult.Rejected(
                    "conversation sequence must advance exactly once"
                )
            }
        }

        val linked = ConversationV3LinkedChunk(
            snapshot = CognitiveConversationContextSnapshot(sessionId, listOf(message)),
            previousChunkId = current?.latestChunkId
        )
        val chunkRecord = ConversationV3IndexCodec.encodeChunk(linked, persistedAt)
        when (val persisted = persistChunkOrValidateExisting(chunkRecord, linked)) {
            is ConversationV3PersistResult.Persisted -> Unit
            is ConversationV3PersistResult.Rejected -> {
                cache.remove(sessionId)
                return PersistentConversationAppendResult.Rejected(persisted.reason)
            }
            is ConversationV3PersistResult.EncryptionUnavailable -> {
                cache.remove(sessionId)
                return PersistentConversationAppendResult.EncryptionUnavailable(persisted.category)
            }
            is ConversationV3PersistResult.Failed -> {
                cache.remove(sessionId)
                return PersistentConversationAppendResult.Failed(persisted.reason)
            }
        }

        val head = ConversationV3SessionHead(
            sessionId = sessionId,
            lastSequence = message.sequence.value,
            latestChunkId = chunkRecord.id
        )
        val headOwnership = when (
            val persisted = persistHead(
                head = head,
                previousGeneration = current?.headGeneration,
                persistedAt = persistedAt
            )
        ) {
            is ConversationV3PersistResult.Persisted -> persisted.ownership
            is ConversationV3PersistResult.Rejected -> {
                cache.remove(sessionId)
                return PersistentConversationAppendResult.Rejected(persisted.reason)
            }
            is ConversationV3PersistResult.EncryptionUnavailable -> {
                cache.remove(sessionId)
                return PersistentConversationAppendResult.EncryptionUnavailable(persisted.category)
            }
            is ConversationV3PersistResult.Failed -> {
                cache.remove(sessionId)
                return PersistentConversationAppendResult.Failed(persisted.reason)
            }
        }

        val replacement = CognitiveConversationContextSnapshot(
            sessionId,
            ((current?.snapshot?.messages ?: emptyList()) + message)
                .takeLast(maxRetainedMessages)
        )
        cache[sessionId] = ConversationV3NativeEntry(
            replacement,
            message.sequence.value,
            chunkRecord.id,
            headOwnership.generation
        )
        return PersistentConversationAppendResult.Appended(replacement)
    }

    @Synchronized
    fun appendPair(
        sessionId: CognitiveConversationSessionId,
        user: CognitiveConversationContextMessage,
        assistant: CognitiveConversationContextMessage,
        persistedAt: Instant
    ): PersistentConversationAppendPairResult {
        if (user.role != CognitiveConversationRole.USER ||
            assistant.role != CognitiveConversationRole.ASSISTANT
        ) {
            return PersistentConversationAppendPairResult.Rejected(
                "conversation pair must be USER then ASSISTANT"
            )
        }
        if (user.content.length > maxMessageChars ||
            assistant.content.length > maxMessageChars
        ) {
            return PersistentConversationAppendPairResult.Rejected(
                "conversation pair message exceeds configured bound"
            )
        }
        if (assistant.sequence.value != user.sequence.value + 1L) {
            return PersistentConversationAppendPairResult.Rejected(
                "conversation pair sequences must be adjacent"
            )
        }

        val current = when (val loaded = loadSession(sessionId)) {
            is ConversationV3SessionLoad.Found -> loaded.entry
            ConversationV3SessionLoad.Absent -> null
            ConversationV3SessionLoad.Corrupt ->
                return PersistentConversationAppendPairResult.Failed(
                    "conversation v3 archive is corrupt"
                )
            is ConversationV3SessionLoad.Incompatible ->
                return PersistentConversationAppendPairResult.Failed(loaded.reason)
            is ConversationV3SessionLoad.EncryptionUnavailable ->
                return PersistentConversationAppendPairResult.EncryptionUnavailable(loaded.category)
        }

        if (current == null && user.sequence.value != 1L) {
            return PersistentConversationAppendPairResult.Rejected(
                "new conversation pair must begin at sequence 1"
            )
        }
        if (current != null) {
            val existingUser = current.snapshot.messages.firstOrNull {
                it.sequence == user.sequence
            }
            val existingAssistant = current.snapshot.messages.firstOrNull {
                it.sequence == assistant.sequence
            }
            if (existingUser != null || existingAssistant != null) {
                return if (existingUser == user && existingAssistant == assistant) {
                    PersistentConversationAppendPairResult.AlreadyPresent(current.snapshot)
                } else {
                    PersistentConversationAppendPairResult.Rejected(
                        "conversation pair sequence conflicts with durable history"
                    )
                }
            }
            if (user.sequence.value != current.lastSequence + 1L) {
                return PersistentConversationAppendPairResult.Rejected(
                    "conversation pair must advance exactly from durable history"
                )
            }
        }

        val linked = ConversationV3LinkedChunk(
            snapshot = CognitiveConversationContextSnapshot(
                sessionId,
                listOf(user, assistant)
            ),
            previousChunkId = current?.latestChunkId
        )
        val chunkRecord = ConversationV3IndexCodec.encodeChunk(linked, persistedAt)
        when (val persisted = persistChunkOrValidateExisting(chunkRecord, linked)) {
            is ConversationV3PersistResult.Persisted -> Unit
            is ConversationV3PersistResult.Rejected -> {
                cache.remove(sessionId)
                return PersistentConversationAppendPairResult.Rejected(persisted.reason)
            }
            is ConversationV3PersistResult.EncryptionUnavailable -> {
                cache.remove(sessionId)
                return PersistentConversationAppendPairResult.EncryptionUnavailable(
                    persisted.category
                )
            }
            is ConversationV3PersistResult.Failed -> {
                cache.remove(sessionId)
                return PersistentConversationAppendPairResult.Failed(persisted.reason)
            }
        }

        val head = ConversationV3SessionHead(
            sessionId = sessionId,
            lastSequence = assistant.sequence.value,
            latestChunkId = chunkRecord.id
        )
        val headOwnership = when (
            val persisted = persistHead(
                head = head,
                previousGeneration = current?.headGeneration,
                persistedAt = persistedAt
            )
        ) {
            is ConversationV3PersistResult.Persisted -> persisted.ownership
            is ConversationV3PersistResult.Rejected -> {
                cache.remove(sessionId)
                return PersistentConversationAppendPairResult.Rejected(persisted.reason)
            }
            is ConversationV3PersistResult.EncryptionUnavailable -> {
                cache.remove(sessionId)
                return PersistentConversationAppendPairResult.EncryptionUnavailable(
                    persisted.category
                )
            }
            is ConversationV3PersistResult.Failed -> {
                cache.remove(sessionId)
                return PersistentConversationAppendPairResult.Failed(persisted.reason)
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
        cache[sessionId] = ConversationV3NativeEntry(
            replacement,
            assistant.sequence.value,
            chunkRecord.id,
            headOwnership.generation
        )
        return PersistentConversationAppendPairResult.Appended(replacement)
    }

    @Synchronized
    fun reopenResult(
        sessionId: CognitiveConversationSessionId
    ): PersistentConversationReopenResult =
        when (val loaded = loadSession(sessionId)) {
            is ConversationV3SessionLoad.Found ->
                PersistentConversationReopenResult.Found(loaded.entry.snapshot)
            ConversationV3SessionLoad.Absent ->
                PersistentConversationReopenResult.Absent
            ConversationV3SessionLoad.Corrupt ->
                PersistentConversationReopenResult.Corrupt
            is ConversationV3SessionLoad.Incompatible ->
                PersistentConversationReopenResult.Incompatible(loaded.reason)
            is ConversationV3SessionLoad.EncryptionUnavailable ->
                PersistentConversationReopenResult.EncryptionUnavailable(loaded.category)
        }

    /**
     * Explicit global count may enumerate/decrypt the store. Ordinary startup and session reopen do
     * not call this method.
     */
    @Synchronized
    fun sessionCountResult(): PersistentConversationSessionCountResult {
        val decrypted = when (val result = encryptedStore.decryptedSnapshotEntries()) {
            is CognitiveEncryptionResult.Success -> result.value
            is CognitiveEncryptionResult.Rejected ->
                return PersistentConversationSessionCountResult.EncryptionUnavailable(
                    result.category
                )
            is CognitiveEncryptionResult.Failed ->
                return PersistentConversationSessionCountResult.EncryptionUnavailable(
                    result.category
                )
        }

        var count = 0
        for (snapshot in decrypted) {
            if (snapshot.record.schemaId != ConversationV3IndexCodec.HEAD_SCHEMA_ID) {
                continue
            }
            if (snapshot.record.schemaVersion != ConversationV3IndexCodec.SCHEMA_VERSION) {
                return PersistentConversationSessionCountResult.Incompatible(
                    "conversation v3 head schema version mismatch"
                )
            }
            when (ConversationV3IndexCodec.decodeHead(snapshot.record)) {
                is ConversationV3DecodeResult.Decoded -> count += 1
                ConversationV3DecodeResult.Corrupt ->
                    return PersistentConversationSessionCountResult.Corrupt
                is ConversationV3DecodeResult.Incompatible ->
                    return PersistentConversationSessionCountResult.Incompatible(
                        "conversation v3 head schema mismatch"
                    )
            }
        }
        return PersistentConversationSessionCountResult.Count(count)
    }

    @Synchronized
    fun history(
        sessionId: CognitiveConversationSessionId,
        beforeSequenceExclusive: Long,
        maxMessages: Int
    ): PersistentConversationHistoryResult {
        require(maxMessages > 0) { "history page size must be positive" }
        val entry = when (val loaded = loadSession(sessionId)) {
            is ConversationV3SessionLoad.Found -> loaded.entry
            ConversationV3SessionLoad.Absent -> return PersistentConversationHistoryResult.Absent
            ConversationV3SessionLoad.Corrupt -> return PersistentConversationHistoryResult.Corrupt
            is ConversationV3SessionLoad.Incompatible ->
                return PersistentConversationHistoryResult.Corrupt
            is ConversationV3SessionLoad.EncryptionUnavailable ->
                return PersistentConversationHistoryResult.EncryptionUnavailable(loaded.category)
        }

        val selected = ArrayList<CognitiveConversationContextMessage>()
        val seen = LinkedHashSet<PersistentEntityId>()
        var next: PersistentEntityId? = entry.latestChunkId
        while (next != null && selected.size < maxMessages) {
            if (!seen.add(next)) return PersistentConversationHistoryResult.Corrupt
            val chunk = when (val read = readChunk(next, sessionId)) {
                is ConversationV3SessionLoadChunk.Found -> read.chunk
                ConversationV3SessionLoadChunk.Missing,
                ConversationV3SessionLoadChunk.Corrupt ->
                    return PersistentConversationHistoryResult.Corrupt
                is ConversationV3SessionLoadChunk.Incompatible ->
                    return PersistentConversationHistoryResult.Corrupt
                is ConversationV3SessionLoadChunk.EncryptionUnavailable ->
                    return PersistentConversationHistoryResult.EncryptionUnavailable(read.category)
            }
            for (message in chunk.snapshot.messages.asReversed()) {
                if (message.sequence.value < beforeSequenceExclusive) {
                    selected += message
                    if (selected.size >= maxMessages) break
                }
            }
            next = chunk.previousChunkId
        }
        return PersistentConversationHistoryResult.Found(
            CognitiveConversationContextSnapshot(
                sessionId,
                selected.sortedBy { it.sequence.value }.takeLast(maxMessages)
            )
        )
    }

    private fun loadSession(
        sessionId: CognitiveConversationSessionId
    ): ConversationV3SessionLoad {
        cache[sessionId]?.let { return ConversationV3SessionLoad.Found(it) }

        val headId = ConversationV3IndexCodec.headId(sessionId)
        var headGeneration: PersistentGeneration? = null
        var headMissing = false
        var head = when (val read = readPlainRecord(headId)) {
            is ConversationV3RecordRead.Found -> {
                headGeneration = read.generation
                when (val decoded = ConversationV3IndexCodec.decodeHead(read.record)) {
                    is ConversationV3DecodeResult.Decoded -> decoded.value
                    ConversationV3DecodeResult.Corrupt ->
                        return ConversationV3SessionLoad.Corrupt
                    is ConversationV3DecodeResult.Incompatible ->
                        return ConversationV3SessionLoad.Incompatible(decoded.reason)
                }
            }
            ConversationV3RecordRead.Missing -> {
                headMissing = true
                ConversationV3SessionHead(sessionId, 0L, null)
            }
            ConversationV3RecordRead.Corrupt ->
                return ConversationV3SessionLoad.Corrupt
            is ConversationV3RecordRead.EncryptionUnavailable ->
                return ConversationV3SessionLoad.EncryptionUnavailable(read.category)
        }

        if (headMissing) {
            when (
                val lockRead = readPlainRecord(
                    ConversationV3MigrationCodec.migrationLockId(sessionId)
                )
            ) {
                is ConversationV3RecordRead.Found ->
                    when (
                        val decoded =
                            ConversationV3MigrationCodec.decodeMigrationLock(
                                lockRead.record
                            )
                    ) {
                        is ConversationV3MigrationDecodeResult.Decoded ->
                            if (decoded.value.sessionId == sessionId) {
                                return ConversationV3SessionLoad.Absent
                            } else {
                                return ConversationV3SessionLoad.Corrupt
                            }
                        ConversationV3MigrationDecodeResult.Corrupt ->
                            return ConversationV3SessionLoad.Corrupt
                        is ConversationV3MigrationDecodeResult.Incompatible ->
                            return ConversationV3SessionLoad.Incompatible(
                                decoded.reason
                            )
                    }
                ConversationV3RecordRead.Missing -> Unit
                ConversationV3RecordRead.Corrupt ->
                    return ConversationV3SessionLoad.Corrupt
                is ConversationV3RecordRead.EncryptionUnavailable ->
                    return ConversationV3SessionLoad.EncryptionUnavailable(
                        lockRead.category
                    )
            }
        }

        var advanced = false
        while (true) {
            val nextFirst = head.lastSequence + 1L
            if (nextFirst <= 0L) return ConversationV3SessionLoad.Corrupt
            val expectedId = ConversationV3IndexCodec.chunkId(sessionId, nextFirst)
            when (val read = readChunk(expectedId, sessionId)) {
                ConversationV3SessionLoadChunk.Missing -> break
                is ConversationV3SessionLoadChunk.Found -> {
                    val chunk = read.chunk
                    if (chunk.previousChunkId != head.latestChunkId ||
                        chunk.snapshot.messages.first().sequence.value != nextFirst
                    ) {
                        return ConversationV3SessionLoad.Corrupt
                    }
                    if (chunk.snapshot.messages.any { it.content.length > maxMessageChars }) {
                        return ConversationV3SessionLoad.Incompatible(
                            "durable conversation exceeds configured reconstruction bounds"
                        )
                    }
                    head = ConversationV3SessionHead(
                        sessionId,
                        chunk.snapshot.messages.last().sequence.value,
                        expectedId
                    )
                    advanced = true
                }
                ConversationV3SessionLoadChunk.Corrupt ->
                    return ConversationV3SessionLoad.Corrupt
                is ConversationV3SessionLoadChunk.Incompatible ->
                    return ConversationV3SessionLoad.Incompatible(read.reason)
                is ConversationV3SessionLoadChunk.EncryptionUnavailable ->
                    return ConversationV3SessionLoad.EncryptionUnavailable(read.category)
            }
        }

        if (head.lastSequence == 0L) {
            return ConversationV3SessionLoad.Absent
        }

        if (advanced) {
            val ownership = when (
                val persisted = persistHead(
                    head,
                    headGeneration,
                    Instant.EPOCH
                )
            ) {
                is ConversationV3PersistResult.Persisted -> persisted.ownership
                is ConversationV3PersistResult.Rejected ->
                    return ConversationV3SessionLoad.Corrupt
                is ConversationV3PersistResult.EncryptionUnavailable ->
                    return ConversationV3SessionLoad.EncryptionUnavailable(persisted.category)
                is ConversationV3PersistResult.Failed ->
                    return ConversationV3SessionLoad.Corrupt
            }
            headGeneration = ownership.generation
        }

        val generation = headGeneration ?: return ConversationV3SessionLoad.Corrupt
        val tail = reconstructTail(sessionId, head)
            ?: return ConversationV3SessionLoad.Corrupt
        val entry = ConversationV3NativeEntry(
            snapshot = tail,
            lastSequence = head.lastSequence,
            latestChunkId = head.latestChunkId ?: return ConversationV3SessionLoad.Corrupt,
            headGeneration = generation
        )
        cache[sessionId] = entry
        return ConversationV3SessionLoad.Found(entry)
    }

    private fun reconstructTail(
        sessionId: CognitiveConversationSessionId,
        head: ConversationV3SessionHead
    ): CognitiveConversationContextSnapshot? {
        val selected = ArrayList<CognitiveConversationContextMessage>()
        val seen = LinkedHashSet<PersistentEntityId>()
        var expectedLast = head.lastSequence
        var next = head.latestChunkId
        while (next != null && selected.size < maxRetainedMessages) {
            if (!seen.add(next)) return null
            val read = readChunk(next, sessionId)
            val chunk = when (read) {
                is ConversationV3SessionLoadChunk.Found -> read.chunk
                else -> return null
            }
            if (chunk.snapshot.messages.last().sequence.value != expectedLast ||
                chunk.snapshot.messages.any { it.content.length > maxMessageChars }
            ) {
                return null
            }
            selected += chunk.snapshot.messages.asReversed()
            expectedLast = chunk.snapshot.messages.first().sequence.value - 1L
            next = chunk.previousChunkId
            if (
                read is ConversationV3SessionLoadChunk.Found &&
                read.truncatedRoot &&
                next == null
            ) {
                expectedLast = 0L
            }
        }
        if (selected.size < maxRetainedMessages && next == null && expectedLast != 0L) {
            return null
        }
        return CognitiveConversationContextSnapshot(
            sessionId,
            selected.sortedBy { it.sequence.value }.takeLast(maxRetainedMessages)
        )
    }

    private sealed interface ConversationV3SessionLoadChunk {
        data class Found(
            val chunk: ConversationV3LinkedChunk,
            val truncatedRoot: Boolean = false
        ) : ConversationV3SessionLoadChunk
        data object Missing : ConversationV3SessionLoadChunk
        data object Corrupt : ConversationV3SessionLoadChunk
        data class Incompatible(val reason: String) : ConversationV3SessionLoadChunk
        data class EncryptionUnavailable(
            val category: CognitiveEncryptionFailureCategory
        ) : ConversationV3SessionLoadChunk
    }

    private fun readMigrationLock(
        sessionId: CognitiveConversationSessionId
    ): ConversationV3LockedMigrationResult =
        when (
            val read = readPlainRecord(
                ConversationV3MigrationCodec.migrationLockId(sessionId)
            )
        ) {
            is ConversationV3RecordRead.Found ->
                when (
                    val decoded =
                        ConversationV3MigrationCodec.decodeMigrationLock(read.record)
                ) {
                    is ConversationV3MigrationDecodeResult.Decoded ->
                        if (decoded.value.sessionId == sessionId) {
                            ConversationV3LockedMigrationResult.Ready
                        } else {
                            ConversationV3LockedMigrationResult.Corrupt
                        }
                    ConversationV3MigrationDecodeResult.Corrupt ->
                        ConversationV3LockedMigrationResult.Corrupt
                    is ConversationV3MigrationDecodeResult.Incompatible ->
                        ConversationV3LockedMigrationResult.Incompatible(
                            decoded.reason
                        )
                }
            ConversationV3RecordRead.Missing ->
                ConversationV3LockedMigrationResult.Rejected(
                    "conversation migration lock is missing"
                )
            ConversationV3RecordRead.Corrupt ->
                ConversationV3LockedMigrationResult.Corrupt
            is ConversationV3RecordRead.EncryptionUnavailable ->
                ConversationV3LockedMigrationResult.EncryptionUnavailable(
                    read.category
                )
        }

    private fun readChunk(
        id: PersistentEntityId,
        sessionId: CognitiveConversationSessionId
    ): ConversationV3SessionLoadChunk =
        when (val read = readPlainRecord(id)) {
            is ConversationV3RecordRead.Found ->
                if (
                    read.record.schemaId ==
                        ConversationV3MigrationCodec.TRUNCATED_ROOT_CHUNK_SCHEMA_ID
                ) {
                    when (
                        val decoded =
                            ConversationV3MigrationCodec.decodeTruncatedRootChunk(
                                read.record
                            )
                    ) {
                        is ConversationV3MigrationDecodeResult.Decoded -> {
                            val snapshot = decoded.value.snapshot
                            if (snapshot.sessionId != sessionId ||
                                snapshot.messages.any {
                                    it.content.length > maxMessageChars
                                }
                            ) {
                                ConversationV3SessionLoadChunk.Corrupt
                            } else {
                                when (
                                    val boundaryRead = readPlainRecord(
                                        ConversationV3MigrationCodec.truncatedRootId(
                                            sessionId
                                        )
                                    )
                                ) {
                                    is ConversationV3RecordRead.Found ->
                                        when (
                                            val boundary =
                                                ConversationV3MigrationCodec.decodeTruncatedRoot(
                                                    boundaryRead.record
                                                )
                                        ) {
                                            is ConversationV3MigrationDecodeResult.Decoded ->
                                                if (
                                                    boundary.value.sessionId ==
                                                        sessionId &&
                                                    boundary.value.firstRetainedSequence ==
                                                        snapshot.messages.first()
                                                            .sequence.value
                                                ) {
                                                    ConversationV3SessionLoadChunk.Found(
                                                        ConversationV3LinkedChunk(
                                                            snapshot = snapshot,
                                                            previousChunkId = null
                                                        ),
                                                        truncatedRoot = true
                                                    )
                                                } else {
                                                    ConversationV3SessionLoadChunk.Corrupt
                                                }
                                            ConversationV3MigrationDecodeResult.Corrupt ->
                                                ConversationV3SessionLoadChunk.Corrupt
                                            is ConversationV3MigrationDecodeResult.Incompatible ->
                                                ConversationV3SessionLoadChunk.Incompatible(
                                                    boundary.reason
                                                )
                                        }
                                    ConversationV3RecordRead.Missing,
                                    ConversationV3RecordRead.Corrupt ->
                                        ConversationV3SessionLoadChunk.Corrupt
                                    is ConversationV3RecordRead.EncryptionUnavailable ->
                                        ConversationV3SessionLoadChunk.EncryptionUnavailable(
                                            boundaryRead.category
                                        )
                                }
                            }
                        }
                        ConversationV3MigrationDecodeResult.Corrupt ->
                            ConversationV3SessionLoadChunk.Corrupt
                        is ConversationV3MigrationDecodeResult.Incompatible ->
                            ConversationV3SessionLoadChunk.Incompatible(decoded.reason)
                    }
                } else {
                    when (
                        val decoded =
                            ConversationV3IndexCodec.decodeChunk(read.record)
                    ) {
                        is ConversationV3DecodeResult.Decoded ->
                            if (decoded.value.snapshot.sessionId != sessionId) {
                                ConversationV3SessionLoadChunk.Corrupt
                            } else if (
                                decoded.value.snapshot.messages.any {
                                    it.content.length > maxMessageChars
                                }
                            ) {
                                ConversationV3SessionLoadChunk.Incompatible(
                                    "durable conversation exceeds configured reconstruction bounds"
                                )
                            } else {
                                ConversationV3SessionLoadChunk.Found(
                                    decoded.value
                                )
                            }
                        ConversationV3DecodeResult.Corrupt ->
                            ConversationV3SessionLoadChunk.Corrupt
                        is ConversationV3DecodeResult.Incompatible ->
                            ConversationV3SessionLoadChunk.Incompatible(
                                decoded.reason
                            )
                    }
                }
            ConversationV3RecordRead.Missing ->
                ConversationV3SessionLoadChunk.Missing
            ConversationV3RecordRead.Corrupt ->
                ConversationV3SessionLoadChunk.Corrupt
            is ConversationV3RecordRead.EncryptionUnavailable ->
                ConversationV3SessionLoadChunk.EncryptionUnavailable(
                    read.category
                )
        }

    private fun persistChunkOrValidateExisting(
        record: PersistentRecord,
        expected: ConversationV3LinkedChunk
    ): ConversationV3PersistResult {
        val draft = draft(record)
        return when (val installed = encryptedStore.install(draft)) {
            is CognitiveEncryptionResult.Success ->
                ConversationV3PersistResult.Persisted(installed.value)
            is CognitiveEncryptionResult.Rejected -> {
                if (installed.category != CognitiveEncryptionFailureCategory.PERSISTENCE_CONFLICT) {
                    ConversationV3PersistResult.EncryptionUnavailable(installed.category)
                } else {
                    when (val read = readPlainRecord(record.id)) {
                        is ConversationV3RecordRead.Found ->
                            when (val decoded = ConversationV3IndexCodec.decodeChunk(read.record)) {
                                is ConversationV3DecodeResult.Decoded ->
                                    if (decoded.value == expected) {
                                        ConversationV3PersistResult.Persisted(
                                            existingOwnership(read.record, read.generation)
                                        )
                                    } else {
                                        ConversationV3PersistResult.Rejected(
                                            "conversation v3 chunk conflicts with durable history"
                                        )
                                    }
                                else -> ConversationV3PersistResult.Rejected(
                                    "conversation v3 chunk conflicts with durable history"
                                )
                            }
                        else -> ConversationV3PersistResult.Rejected(
                            "conversation v3 chunk conflicts with durable history"
                        )
                    }
                }
            }
            is CognitiveEncryptionResult.Failed ->
                ConversationV3PersistResult.Failed(
                    "encrypted conversation v3 chunk persistence failed"
                )
        }
    }

    private fun persistHead(
        head: ConversationV3SessionHead,
        previousGeneration: PersistentGeneration?,
        persistedAt: Instant
    ): ConversationV3PersistResult {
        val record = ConversationV3IndexCodec.encodeHead(head, persistedAt)
        val draft = draft(record)
        val result = if (previousGeneration == null) {
            encryptedStore.install(draft)
        } else {
            encryptedStore.transitionExact(
                sourceId = record.id,
                sourceGeneration = previousGeneration,
                replacement = draft
            )
        }
        return when (result) {
            is CognitiveEncryptionResult.Success ->
                ConversationV3PersistResult.Persisted(result.value)
            is CognitiveEncryptionResult.Rejected ->
                ConversationV3PersistResult.Rejected(
                    "conversation v3 head persistence conflict"
                )
            is CognitiveEncryptionResult.Failed ->
                ConversationV3PersistResult.Failed(
                    "conversation v3 head persistence failed"
                )
        }
    }

    private fun draft(record: PersistentRecord): CognitivePersistentRecordDraft =
        CognitivePersistentRecordDraft(
            id = record.id,
            schemaId = record.schemaId,
            schemaVersion = record.schemaVersion,
            plaintext = CognitivePlaintext(record.payload.copyBytes()),
            createdAt = record.createdAt,
            dek = activeDek
        )

    private fun existingOwnership(
        record: PersistentRecord,
        generation: PersistentGeneration
    ): PersistentRecordOwnership = object : PersistentRecordOwnership {
        override val record: PersistentRecord = record
        override val generation: PersistentGeneration = generation
        override fun remove() = encryptedStore.removeExact(record.id, generation)
    }

    private fun readPlainRecord(id: PersistentEntityId): ConversationV3RecordRead {
        val plaintext = when (val opened = encryptedStore.open(id)) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected ->
                return if (opened.category == CognitiveEncryptionFailureCategory.INVALID_REQUEST) {
                    ConversationV3RecordRead.Missing
                } else {
                    ConversationV3RecordRead.EncryptionUnavailable(opened.category)
                }
            is CognitiveEncryptionResult.Failed ->
                return ConversationV3RecordRead.EncryptionUnavailable(opened.category)
        }
        val raw: PersistentRecordSnapshot =
            encryptedStore.inspect(id) ?: return ConversationV3RecordRead.Corrupt
        return ConversationV3RecordRead.Found(
            raw.record.copy(payload = PersistentPayload(plaintext.copyBytes())),
            raw.generation
        )
    }

    companion object {
        fun detectOrInitialize(
            encryptedStore: EncryptedPersistentRecordStore,
            activeDek: CognitiveDekReference,
            maxRetainedMessages: Int,
            maxMessageChars: Int
        ): ConversationV3NativeDecision {
            if (!encryptedStore.supportsIndexedLazyMode()) {
                return ConversationV3NativeDecision.LegacyFallback
            }

            fun runtime() = ConversationV3NativeRuntime(
                encryptedStore,
                activeDek,
                maxRetainedMessages,
                maxMessageChars
            )

            val probe = runtime()
            val nativeMarker = when (
                val read = probe.readPlainRecord(ConversationV3IndexCodec.MARKER_ID)
            ) {
                is ConversationV3RecordRead.Found ->
                    when (val decoded = ConversationV3IndexCodec.decodeMarker(read.record)) {
                        is ConversationV3DecodeResult.Decoded -> true
                        ConversationV3DecodeResult.Corrupt ->
                            return ConversationV3NativeDecision.Corrupt
                        is ConversationV3DecodeResult.Incompatible ->
                            return ConversationV3NativeDecision.Incompatible(decoded.reason)
                    }
                ConversationV3RecordRead.Missing -> false
                ConversationV3RecordRead.Corrupt ->
                    return ConversationV3NativeDecision.Corrupt
                is ConversationV3RecordRead.EncryptionUnavailable ->
                    return ConversationV3NativeDecision.EncryptionUnavailable(read.category)
            }

            val mixedMarker = when (
                val read = probe.readPlainRecord(
                    ConversationV3MigrationCodec.MIXED_MARKER_ID
                )
            ) {
                is ConversationV3RecordRead.Found ->
                    when (
                        val decoded =
                            ConversationV3MigrationCodec.decodeMixedMarker(read.record)
                    ) {
                        is ConversationV3MigrationDecodeResult.Decoded -> true
                        ConversationV3MigrationDecodeResult.Corrupt ->
                            return ConversationV3NativeDecision.Corrupt
                        is ConversationV3MigrationDecodeResult.Incompatible ->
                            return ConversationV3NativeDecision.Incompatible(decoded.reason)
                    }
                ConversationV3RecordRead.Missing -> false
                ConversationV3RecordRead.Corrupt ->
                    return ConversationV3NativeDecision.Corrupt
                is ConversationV3RecordRead.EncryptionUnavailable ->
                    return ConversationV3NativeDecision.EncryptionUnavailable(read.category)
            }

            if (nativeMarker && mixedMarker) {
                return ConversationV3NativeDecision.Corrupt
            }
            if (nativeMarker) {
                return ConversationV3NativeDecision.Native(probe)
            }
            if (mixedMarker) {
                return ConversationV3NativeDecision.Mixed(probe)
            }

            if (encryptedStore.entryCount() != 0L) {
                return ConversationV3NativeDecision.LegacyFallback
            }

            val markerRecord = ConversationV3IndexCodec.encodeMarker(Instant.EPOCH)
            return when (val installed = encryptedStore.install(probe.draft(markerRecord))) {
                is CognitiveEncryptionResult.Success ->
                    ConversationV3NativeDecision.Native(probe)
                is CognitiveEncryptionResult.Rejected ->
                    if (
                        installed.category !=
                            CognitiveEncryptionFailureCategory.PERSISTENCE_CONFLICT
                    ) {
                        ConversationV3NativeDecision.EncryptionUnavailable(
                            installed.category
                        )
                    } else {
                        when (encryptedStore.refreshIndexedMetadata()) {
                            is EncryptedPersistentMetadataRefreshResult.Refreshed ->
                                detectOrInitialize(
                                    encryptedStore,
                                    activeDek,
                                    maxRetainedMessages,
                                    maxMessageChars
                                )
                            EncryptedPersistentMetadataRefreshResult.Unchanged ->
                                ConversationV3NativeDecision.EncryptionUnavailable(
                                    CognitiveEncryptionFailureCategory.PERSISTENCE_CONFLICT
                                )
                            EncryptedPersistentMetadataRefreshResult.Corrupt ->
                                ConversationV3NativeDecision.Corrupt
                            is EncryptedPersistentMetadataRefreshResult.Incompatible ->
                                ConversationV3NativeDecision.Incompatible(
                                    "conversation marker refresh is incompatible"
                                )
                            is EncryptedPersistentMetadataRefreshResult.Failed ->
                                ConversationV3NativeDecision.EncryptionUnavailable(
                                    CognitiveEncryptionFailureCategory.PERSISTENCE_FAILED
                                )
                        }
                    }
                is CognitiveEncryptionResult.Failed ->
                    ConversationV3NativeDecision.EncryptionUnavailable(
                        installed.category
                    )
            }
        }
    }
}
