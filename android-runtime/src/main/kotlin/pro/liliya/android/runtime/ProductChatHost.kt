package pro.liliya.android.runtime

import java.util.UUID
import pro.liliya.core.cognitive.CognitiveInput
import pro.liliya.core.cognitive.CognitiveStreamControl
import pro.liliya.core.cognitive.CognitiveStreamingSink
import pro.liliya.core.cognitive.CognitiveTurnId

enum class ProductChatGenerationMode {
    ONE_SHOT,
    STREAMING
}

class ProductChatRequest(
    val text: String,
    val mode: ProductChatGenerationMode
) {
    override fun toString(): String =
        "ProductChatRequest(text=<redacted:" + text.length + ">,mode=" + mode + ")"
}

class ProductChatChunk(
    val sequence: Long,
    val text: String
) {
    init {
        require(sequence > 0L) { "product chat chunk sequence must be positive" }
        require(text.isNotEmpty()) { "product chat chunk text must not be empty" }
    }

    override fun toString(): String =
        "ProductChatChunk(sequence=" + sequence + ",text=<redacted:" + text.length + ">)"
}

enum class ProductChatStreamControl {
    CONTINUE,
    STOP
}

fun interface ProductChatStreamingSink {
    fun onChunk(chunk: ProductChatChunk): ProductChatStreamControl
}

enum class ProductChatFailure {
    INPUT_REJECTED,
    HEART_NOT_READY,
    BUSY_OR_TURN_REJECTED,
    STREAMING_SINK_REQUIRED,
    CONTEXT_REJECTED,
    GENERATION_REJECTED,
    CANCELLED,
    FINALIZATION_REJECTED,
    STALE,
    INTERNAL_FAILURE
}

sealed interface ProductChatResult {
    class Completed(
        val reply: String,
        val streamedChunkCount: Int,
        val streamedCharacterCount: Int
    ) : ProductChatResult {
        init {
            require(reply.isNotBlank()) { "product chat reply must not be blank" }
            require(streamedChunkCount >= 0)
            require(streamedCharacterCount >= 0)
        }

        override fun toString(): String =
            "Completed(reply=<redacted:" + reply.length + ">," +
                "streamedChunkCount=" + streamedChunkCount + "," +
                "streamedCharacterCount=" + streamedCharacterCount + ")"
    }

    data class Rejected(
        val reason: ProductChatFailure
    ) : ProductChatResult
}

internal fun interface ProductChatTurnIdSource {
    fun next(): String
}

internal fun interface ProductChatTurnRunner {
    fun run(
        request: ProductTurnRequest,
        streamingSink: CognitiveStreamingSink?
    ): ProductTurnResult
}

/**
 * Thin host-facing text facade over ProductTurnOrchestrator.
 *
 * This component adapts public chat types only. Product turn sequencing, Cognitive state,
 * model/session ownership, Learning, License, Authority and Execution remain elsewhere.
 */
class ProductChatHost internal constructor(
    private val maxInputChars: Int,
    private val maxTurnIdChars: Int,
    private val turnIds: ProductChatTurnIdSource,
    private val turns: ProductChatTurnRunner
) {
    init {
        require(maxInputChars > 0) { "product chat input limit must be positive" }
        require(maxTurnIdChars > 0) { "product chat turn-id limit must be positive" }
    }

    fun send(
        request: ProductChatRequest,
        streamingSink: ProductChatStreamingSink? = null
    ): ProductChatResult {
        if (request.text.isBlank() || request.text.length > maxInputChars) {
            return rejected(ProductChatFailure.INPUT_REJECTED)
        }
        if (request.mode == ProductChatGenerationMode.STREAMING && streamingSink == null) {
            return rejected(ProductChatFailure.STREAMING_SINK_REQUIRED)
        }

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
            val downstream = streamingSink ?: return rejected(
                ProductChatFailure.STREAMING_SINK_REQUIRED
            )
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
            turns.run(productRequest, productSink)
        } catch (_: Exception) {
            return rejected(ProductChatFailure.INTERNAL_FAILURE)
        }

        return when (result) {
            is ProductTurnResult.Completed ->
                ProductChatResult.Completed(
                    reply = result.finalization.result.content,
                    streamedChunkCount = result.streamedChunkCount,
                    streamedCharacterCount = result.streamedCharacterCount
                )

            is ProductTurnResult.Rejected -> rejected(mapFailure(result.reason))
        }
    }

    override fun toString(): String =
        "ProductChatHost(maxInputChars=" + maxInputChars + ",maxTurnIdChars=" + maxTurnIdChars + ",turnIds=<redacted>,turns=<redacted>)"

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

    private fun rejected(reason: ProductChatFailure): ProductChatResult.Rejected =
        ProductChatResult.Rejected(reason)

    companion object {
        internal const val MIN_PRODUCTION_TURN_ID_CHARS = 16

        internal fun production(
            maxInputChars: Int,
            maxTurnIdChars: Int,
            turns: ProductTurnOrchestrator
        ): ProductChatHost? {
            if (maxTurnIdChars < MIN_PRODUCTION_TURN_ID_CHARS) return null
            return ProductChatHost(
                maxInputChars = maxInputChars,
                maxTurnIdChars = maxTurnIdChars,
                turnIds = ProductChatTurnIdSource {
                    UUID.randomUUID().toString().replace("-", "").take(maxTurnIdChars)
                },
                turns = ProductChatTurnRunner { request, sink ->
                    turns.run(request, sink)
                }
            )
        }
    }
}
