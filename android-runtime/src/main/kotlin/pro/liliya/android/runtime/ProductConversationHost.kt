package pro.liliya.android.runtime

import java.util.UUID
import pro.liliya.core.cognitive.CognitiveConversationContextMessage
import pro.liliya.core.cognitive.CognitiveTimestampSource
import pro.liliya.core.cognitive.EncryptedPersistentConversationStore
import pro.liliya.core.cognitive.PersistentConversationAppendPairResult
import pro.liliya.core.cognitive.CognitiveConversationContextSnapshot
import pro.liliya.core.cognitive.CognitiveConversationRole
import pro.liliya.core.cognitive.CognitiveConversationSequence
import pro.liliya.core.cognitive.CognitiveConversationSessionId
import pro.liliya.core.cognitive.CognitiveInput
import pro.liliya.core.cognitive.CognitiveStreamControl
import pro.liliya.core.cognitive.CognitiveStreamingSink
import pro.liliya.core.cognitive.CognitiveTurnId

enum class ProductConversationCommitStatus {
    COMMITTED,
    NOT_RETAINED_RESOURCE_LIMIT,
    NOT_RETAINED_PERSISTENCE_FAILURE
}

sealed interface ProductConversationResult {
    class Completed internal constructor(
        val reply: String,
        val streamedChunkCount: Int,
        val streamedCharacterCount: Int,
        val conversationCommit: ProductConversationCommitStatus,
        internal val learningFollowUpEvidence: ProductLearningFollowUpReference?
    ) : ProductConversationResult {
        constructor(
            reply: String,
            streamedChunkCount: Int,
            streamedCharacterCount: Int,
            conversationCommit: ProductConversationCommitStatus
        ) : this(
            reply = reply,
            streamedChunkCount = streamedChunkCount,
            streamedCharacterCount = streamedCharacterCount,
            conversationCommit = conversationCommit,
            learningFollowUpEvidence = null
        )

        init {
            require(reply.isNotBlank()) { "product conversation reply must not be blank" }
            require(streamedChunkCount >= 0)
            require(streamedCharacterCount >= 0)
        }

        override fun toString(): String =
            "Completed(reply=<redacted:" + reply.length + ">," +
                "streamedChunkCount=" + streamedChunkCount + "," +
                "streamedCharacterCount=" + streamedCharacterCount + "," +
                "conversationCommit=" + conversationCommit + "," +
                "learningFollowUpEvidence=" +
                if (learningFollowUpEvidence == null) "<absent>)" else "<redacted>)"
    }

    data class Rejected(
        val reason: ProductChatFailure
    ) : ProductConversationResult
}

fun ProductConversationResult.Completed.learningFollowUpReference():
    ProductLearningFollowUpReference? = learningFollowUpEvidence

sealed interface ProductConversationClearResult {
    data object Cleared : ProductConversationClearResult
    data object Busy : ProductConversationClearResult
    data object NewSessionRequired : ProductConversationClearResult
}

internal fun interface ProductConversationTurnIdSource {
    fun next(): String
}

internal fun interface ProductConversationTurnRunner {
    fun run(
        request: ProductTurnRequest,
        conversationContext: CognitiveConversationContextSnapshot,
        streamingSink: CognitiveStreamingSink?
    ): ProductTurnResult
}

internal sealed interface ProductConversationPersistenceAppendResult {
    data object Committed : ProductConversationPersistenceAppendResult
    data object Rejected : ProductConversationPersistenceAppendResult
}

internal interface ProductConversationPersistencePort {
    fun reopen(sessionId: CognitiveConversationSessionId): CognitiveConversationContextSnapshot?
    fun appendPair(
        sessionId: CognitiveConversationSessionId,
        user: CognitiveConversationContextMessage,
        assistant: CognitiveConversationContextMessage,
        persistedAt: java.time.Instant
    ): ProductConversationPersistenceAppendResult
}

internal fun reconstructDurableProductConversation(
    sessionId: CognitiveConversationSessionId,
    snapshot: CognitiveConversationContextSnapshot,
    maxRetainedMessages: Int,
    maxRetainedCharacters: Int,
    maxMessageCharacters: Int
): CognitiveConversationContextSnapshot? {
    if (snapshot.sessionId != sessionId) return null
    if (maxRetainedMessages < 2 || maxRetainedCharacters <= 0 || maxMessageCharacters <= 0) {
        return null
    }
    if (snapshot.messages.size % 2 != 0) return null
    if (
        snapshot.messages.chunked(2).any { pair ->
            pair.size != 2 ||
                pair[0].role != CognitiveConversationRole.USER ||
                pair[1].role != CognitiveConversationRole.ASSISTANT ||
                pair[1].sequence.value != pair[0].sequence.value + 1L
        }
    ) {
        return null
    }

    val pairRetainedLimit = maxRetainedMessages - (maxRetainedMessages % 2)
    val bounded = snapshot.messages.takeLast(pairRetainedLimit).toMutableList()
    var chars = bounded.sumOf { it.content.length }
    while (bounded.size >= 2 && chars > maxRetainedCharacters) {
        val first = bounded.removeAt(0)
        val second = bounded.removeAt(0)
        chars -= first.content.length + second.content.length
    }
    if (
        chars > maxRetainedCharacters ||
        bounded.any { it.content.length > maxMessageCharacters }
    ) {
        return null
    }
    return CognitiveConversationContextSnapshot(sessionId, bounded)
}

/**
 * Bounded conversation owner over Product Turn.
 *
 * The host may be process-local or explicitly durable for one caller-owned session id. Restored
 * transcript remains inference context only: it is not Memory, Knowledge, Learning, semantic
 * authority, License, Capability, Authority or Execution permission.
 */
class ProductConversationHost internal constructor(
    private val sessionId: CognitiveConversationSessionId,
    private val maxInputChars: Int,
    private val maxTurnIdChars: Int,
    private val maxRetainedMessages: Int,
    private val maxRetainedCharacters: Int,
    private val maxMessageCharacters: Int,
    private val turnIds: ProductConversationTurnIdSource,
    private val turns: ProductConversationTurnRunner,
    initialSnapshot: CognitiveConversationContextSnapshot? = null,
    private val persistence: ProductConversationPersistencePort? = null,
    private val timestamps: CognitiveTimestampSource? = null
) {
    init {
        require(maxInputChars > 0) { "product conversation input limit must be positive" }
        require(maxTurnIdChars > 0) { "product conversation turn-id limit must be positive" }
        require(maxRetainedMessages > 0) { "product conversation retained-message limit must be positive" }
        require(maxRetainedCharacters > 0) { "product conversation retained-character limit must be positive" }
        require(maxMessageCharacters > 0) { "product conversation message limit must be positive" }
    }

    private val lock = Any()
    private val committed = mutableListOf<CognitiveConversationContextMessage>().apply {
        if (initialSnapshot != null) {
            require(initialSnapshot.sessionId == sessionId) {
                "initial conversation snapshot session does not match host session"
            }
            require(initialSnapshot.messages.size <= maxRetainedMessages) {
                "initial conversation snapshot exceeds retained-message bound"
            }
            require(initialSnapshot.messages.sumOf { it.content.length } <= maxRetainedCharacters) {
                "initial conversation snapshot exceeds retained-character bound"
            }
            require(initialSnapshot.messages.all { it.content.length <= maxMessageCharacters }) {
                "initial conversation snapshot exceeds per-message bound"
            }
            addAll(initialSnapshot.messages)
        }
    }
    private var nextSequence = (committed.lastOrNull()?.sequence?.value ?: 0L) + 1L
    private var inFlight = false

    fun send(
        request: ProductChatRequest,
        streamingSink: ProductChatStreamingSink? = null
    ): ProductConversationResult {
        if (request.text.isBlank() || request.text.length > maxInputChars) {
            return rejected(ProductChatFailure.INPUT_REJECTED)
        }
        if (request.mode == ProductChatGenerationMode.STREAMING && streamingSink == null) {
            return rejected(ProductChatFailure.STREAMING_SINK_REQUIRED)
        }

        val snapshot = synchronized(lock) {
            if (inFlight) return rejected(ProductChatFailure.BUSY_OR_TURN_REJECTED)
            inFlight = true
            CognitiveConversationContextSnapshot(
                sessionId = sessionId,
                messages = committed
            )
        }

        try {
            val turnId = try {
                val raw = turnIds.next()
                if (raw.isBlank() || raw.length > maxTurnIdChars) {
                    return rejected(ProductChatFailure.INTERNAL_FAILURE)
                }
                CognitiveTurnId(raw)
            } catch (_: Exception) {
                return rejected(ProductChatFailure.INTERNAL_FAILURE)
            }

            val productRequest = ProductTurnRequest(
                turnId = turnId,
                input = CognitiveInput(request.text),
                mode = when (request.mode) {
                    ProductChatGenerationMode.ONE_SHOT -> ProductTurnGenerationMode.ONE_SHOT
                    ProductChatGenerationMode.STREAMING -> ProductTurnGenerationMode.STREAMING
                }
            )

            val productSink = if (request.mode == ProductChatGenerationMode.STREAMING) {
                val downstream = streamingSink
                    ?: return rejected(ProductChatFailure.STREAMING_SINK_REQUIRED)
                CognitiveStreamingSink { chunk ->
                    when (
                        downstream.onChunk(
                            ProductChatChunk(
                                sequence = chunk.sequence,
                                text = chunk.text
                            )
                        )
                    ) {
                        ProductChatStreamControl.CONTINUE -> CognitiveStreamControl.CONTINUE
                        ProductChatStreamControl.STOP -> CognitiveStreamControl.STOP
                    }
                }
            } else {
                null
            }

            val result = try {
                turns.run(
                    request = productRequest,
                    conversationContext = snapshot,
                    streamingSink = productSink
                )
            } catch (_: Exception) {
                return rejected(ProductChatFailure.INTERNAL_FAILURE)
            }

            return when (result) {
                is ProductTurnResult.Completed -> {
                    val reply = result.finalization.result.content
                    val commit = commitCompletedPair(
                        user = request.text,
                        assistant = reply
                    )
                    ProductConversationResult.Completed(
                        reply = reply,
                        streamedChunkCount = result.streamedChunkCount,
                        streamedCharacterCount = result.streamedCharacterCount,
                        conversationCommit = commit,
                        learningFollowUpEvidence = result.learningFollowUpReference()
                    )
                }

                is ProductTurnResult.Rejected ->
                    rejected(mapFailure(result.reason))
            }
        } finally {
            synchronized(lock) {
                inFlight = false
            }
        }
    }

    fun clear(): ProductConversationClearResult = synchronized(lock) {
        if (inFlight) {
            ProductConversationClearResult.Busy
        } else if (persistence != null) {
            ProductConversationClearResult.NewSessionRequired
        } else {
            committed.clear()
            ProductConversationClearResult.Cleared
        }
    }

    override fun toString(): String {
        val state = synchronized(lock) {
            committed.size to committed.sumOf { it.content.length }
        }
        return "ProductConversationHost(sessionId=<redacted>," +
            "retainedMessageCount=" + state.first + "," +
            "retainedCharacterCount=" + state.second + "," +
            "maxRetainedMessages=" + maxRetainedMessages + "," +
            "maxRetainedCharacters=" + maxRetainedCharacters + "," +
            "maxMessageCharacters=" + maxMessageCharacters + "," +
            "turnIds=<redacted>,turns=<redacted>)"
    }

    private fun commitCompletedPair(
        user: String,
        assistant: String
    ): ProductConversationCommitStatus = synchronized(lock) {
        if (
            user.length > maxMessageCharacters ||
            assistant.length > maxMessageCharacters ||
            maxRetainedMessages < 2 ||
            user.length + assistant.length > maxRetainedCharacters
        ) {
            return@synchronized ProductConversationCommitStatus.NOT_RETAINED_RESOURCE_LIMIT
        }

        val userMessage = CognitiveConversationContextMessage(
            sequence = CognitiveConversationSequence(nextSequence),
            role = CognitiveConversationRole.USER,
            content = user
        )
        val assistantMessage = CognitiveConversationContextMessage(
            sequence = CognitiveConversationSequence(nextSequence + 1L),
            role = CognitiveConversationRole.ASSISTANT,
            content = assistant
        )

        val candidate = committed.toMutableList()
        candidate += userMessage
        candidate += assistantMessage
        var characters = candidate.sumOf { it.content.length }

        while (
            candidate.size > maxRetainedMessages ||
            characters > maxRetainedCharacters
        ) {
            if (candidate.size < 2) {
                return@synchronized ProductConversationCommitStatus.NOT_RETAINED_RESOURCE_LIMIT
            }
            val first = candidate.removeAt(0)
            val second = candidate.removeAt(0)
            characters -= first.content.length
            characters -= second.content.length
        }

        val durable = persistence
        if (durable != null) {
            val timestampSource = timestamps
                ?: return@synchronized ProductConversationCommitStatus.NOT_RETAINED_PERSISTENCE_FAILURE
            if (
                durable.appendPair(
                    sessionId = sessionId,
                    user = userMessage,
                    assistant = assistantMessage,
                    persistedAt = timestampSource.now()
                ) != ProductConversationPersistenceAppendResult.Committed
            ) {
                return@synchronized ProductConversationCommitStatus.NOT_RETAINED_PERSISTENCE_FAILURE
            }
        }

        committed.clear()
        committed += candidate
        nextSequence += 2L
        ProductConversationCommitStatus.COMMITTED
    }

    private fun mapFailure(reason: ProductTurnFailure): ProductChatFailure =
        when (reason) {
            ProductTurnFailure.HEART_NOT_READY -> ProductChatFailure.HEART_NOT_READY
            ProductTurnFailure.MISSING_STREAMING_SINK -> ProductChatFailure.STREAMING_SINK_REQUIRED
            ProductTurnFailure.TURN_REGISTRATION_REJECTED -> ProductChatFailure.BUSY_OR_TURN_REJECTED
            ProductTurnFailure.CONTEXT_REJECTED -> ProductChatFailure.CONTEXT_REJECTED
            ProductTurnFailure.GENERATION_REJECTED -> ProductChatFailure.GENERATION_REJECTED
            ProductTurnFailure.CANCELLED -> ProductChatFailure.CANCELLED
            ProductTurnFailure.FINALIZATION_REJECTED -> ProductChatFailure.FINALIZATION_REJECTED
            ProductTurnFailure.STALE -> ProductChatFailure.STALE
            ProductTurnFailure.INTERNAL_FAILURE -> ProductChatFailure.INTERNAL_FAILURE
        }

    private fun rejected(reason: ProductChatFailure): ProductConversationResult.Rejected =
        ProductConversationResult.Rejected(reason)

    companion object {
        internal fun productionDurable(
            sessionId: CognitiveConversationSessionId,
            maxInputChars: Int,
            maxTurnIdChars: Int,
            maxContextItems: Int,
            maxContextItemChars: Int,
            maxRetainedMessages: Int,
            maxRetainedCharacters: Int,
            maxMessageCharacters: Int,
            turns: ProductTurnOrchestrator,
            persistentStore: EncryptedPersistentConversationStore,
            timestamps: CognitiveTimestampSource
        ): ProductConversationHost? =
            productionDurable(
                sessionId = sessionId,
                maxInputChars = maxInputChars,
                maxTurnIdChars = maxTurnIdChars,
                maxContextItems = maxContextItems,
                maxContextItemChars = maxContextItemChars,
                maxRetainedMessages = maxRetainedMessages,
                maxRetainedCharacters = maxRetainedCharacters,
                maxMessageCharacters = maxMessageCharacters,
                turns = turns,
                persistence = object : ProductConversationPersistencePort {
                    override fun reopen(
                        sessionId: CognitiveConversationSessionId
                    ): CognitiveConversationContextSnapshot? =
                        persistentStore.reopen(sessionId)

                    override fun appendPair(
                        sessionId: CognitiveConversationSessionId,
                        user: CognitiveConversationContextMessage,
                        assistant: CognitiveConversationContextMessage,
                        persistedAt: java.time.Instant
                    ): ProductConversationPersistenceAppendResult =
                        when (
                            persistentStore.appendPair(
                                sessionId = sessionId,
                                user = user,
                                assistant = assistant,
                                persistedAt = persistedAt
                            )
                        ) {
                            is PersistentConversationAppendPairResult.Appended,
                            is PersistentConversationAppendPairResult.AlreadyPresent ->
                                ProductConversationPersistenceAppendResult.Committed
                            is PersistentConversationAppendPairResult.Rejected,
                            is PersistentConversationAppendPairResult.EncryptionUnavailable,
                            is PersistentConversationAppendPairResult.Failed ->
                                ProductConversationPersistenceAppendResult.Rejected
                        }
                },
                timestamps = timestamps
            )

        internal fun productionDurable(
            sessionId: CognitiveConversationSessionId,
            maxInputChars: Int,
            maxTurnIdChars: Int,
            maxContextItems: Int,
            maxContextItemChars: Int,
            maxRetainedMessages: Int,
            maxRetainedCharacters: Int,
            maxMessageCharacters: Int,
            turns: ProductTurnOrchestrator,
            persistence: ProductConversationPersistencePort,
            timestamps: CognitiveTimestampSource
        ): ProductConversationHost? {
            if (maxTurnIdChars < ProductChatHost.MIN_PRODUCTION_TURN_ID_CHARS) return null
            if (maxRetainedMessages < 2 || maxRetainedMessages > maxContextItems) return null
            if (maxRetainedCharacters <= 0) return null
            if (maxMessageCharacters <= 0 || maxMessageCharacters > maxContextItemChars) return null

            val durableSnapshot = persistence.reopen(sessionId)
            val initial = durableSnapshot?.let { snapshot ->
                reconstructDurableProductConversation(
                    sessionId = sessionId,
                    snapshot = snapshot,
                    maxRetainedMessages = maxRetainedMessages,
                    maxRetainedCharacters = maxRetainedCharacters,
                    maxMessageCharacters = maxMessageCharacters
                ) ?: return null
            }

            return ProductConversationHost(
                sessionId = sessionId,
                maxInputChars = maxInputChars,
                maxTurnIdChars = maxTurnIdChars,
                maxRetainedMessages = maxRetainedMessages,
                maxRetainedCharacters = maxRetainedCharacters,
                maxMessageCharacters = maxMessageCharacters,
                turnIds = ProductConversationTurnIdSource {
                    UUID.randomUUID().toString().replace("-", "").take(maxTurnIdChars)
                },
                turns = ProductConversationTurnRunner { request, conversation, sink ->
                    turns.runWithConversationContext(
                        request = request,
                        conversationContext = conversation,
                        streamingSink = sink
                    )
                },
                initialSnapshot = initial,
                persistence = persistence,
                timestamps = timestamps
            )
        }

        internal fun production(
            maxInputChars: Int,
            maxTurnIdChars: Int,
            maxContextItems: Int,
            maxContextItemChars: Int,
            maxRetainedMessages: Int,
            maxRetainedCharacters: Int,
            maxMessageCharacters: Int,
            turns: ProductTurnOrchestrator
        ): ProductConversationHost? {
            if (maxTurnIdChars < ProductChatHost.MIN_PRODUCTION_TURN_ID_CHARS) return null
            if (maxRetainedMessages <= 0 || maxRetainedMessages > maxContextItems) return null
            if (maxRetainedCharacters <= 0) return null
            if (maxMessageCharacters <= 0 || maxMessageCharacters > maxContextItemChars) return null

            return ProductConversationHost(
                sessionId = CognitiveConversationSessionId(
                    UUID.randomUUID().toString().replace("-", "")
                ),
                maxInputChars = maxInputChars,
                maxTurnIdChars = maxTurnIdChars,
                maxRetainedMessages = maxRetainedMessages,
                maxRetainedCharacters = maxRetainedCharacters,
                maxMessageCharacters = maxMessageCharacters,
                turnIds = ProductConversationTurnIdSource {
                    UUID.randomUUID().toString().replace("-", "").take(maxTurnIdChars)
                },
                turns = ProductConversationTurnRunner { request, conversation, sink ->
                    turns.runWithConversationContext(
                        request = request,
                        conversationContext = conversation,
                        streamingSink = sink
                    )
                }
            )
        }
    }
}
