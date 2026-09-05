package pro.liliya.android.semanticprovider

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.nio.charset.StandardCharsets

/**
 * Structural backend protocol preserved from the original provider boundary.
 *
 * One logical semantic ownership contains two pinned ORT sessions:
 *  1. tokenizer.onnx: UTF-8 text -> exact token IDs, using ORT Extensions custom ops.
 *  2. encoder.onnx: validated token IDs/masks -> 384-d mean-pooled, L2-normalized embedding.
 *
 * The tokenizer is always executed first. Inputs above the exact token ceiling are rejected before
 * encoder inference; the provider never enables silent tokenizer truncation.
 */
internal object SemanticNativeBridge {
    const val LOAD_RESOURCE_REJECTED = -1L
    const val LOAD_UNSUPPORTED = -2L
    const val LOAD_REJECTED = -3L
    const val LOAD_PROVIDER_FAILED = -4L

    const val EMBED_OK: Byte = 0
    const val EMBED_RESOURCE_REJECTED: Byte = 1
    const val EMBED_REQUEST_REJECTED: Byte = 2
    const val EMBED_STALE_SESSION: Byte = 3
    const val EMBED_OPERATION_FAILED: Byte = 4
    const val EMBED_PROVIDER_FAILED: Byte = 5

    const val CLOSE_OK = 0
    const val CLOSE_FAILED = 1
    const val CLOSE_PROVIDER_FAILED = 2

    private const val TOKENIZER_INPUT = "inputs"
    private const val TOKENIZER_TOKENS_OUTPUT = "tokens"

    private const val ENCODER_INPUT_IDS = "input_ids"
    private const val ENCODER_ATTENTION_MASK = "attention_mask"
    private const val ENCODER_TOKEN_TYPE_IDS = "token_type_ids"
    private const val ENCODER_OUTPUT = "embedding"

    private data class OrtSemanticSession(
        val environment: OrtEnvironment,
        val tokenizerOptions: OrtSession.SessionOptions,
        val tokenizerSession: OrtSession,
        val encoderOptions: OrtSession.SessionOptions,
        val encoderSession: OrtSession,
        val maxTokens: Int,
        val maxInputUtf8Bytes: Int
    )

    private var nextSessionId = 1L
    private var loadInProgress = false
    private var activeSessionId = 0L
    private var activeSession: OrtSemanticSession? = null

    @Synchronized
    fun nativeLinkProbe(): Int = try {
        val environment = OrtEnvironment.getEnvironment()
        environment.setTelemetry(false)
        if (OrtxPackage.getLibraryPath().isBlank()) 0 else 1
    } catch (_: Throwable) {
        0
    }

    @Synchronized
    fun nativeLoad(
        sourcePathUtf8: ByteArray,
        tokenizerPathUtf8: ByteArray,
        contextTokens: Int,
        batchTokens: Int,
        threadCount: Int,
        maxInputUtf8Bytes: Int
    ): Long {
        if (loadInProgress || activeSession != null) return LOAD_RESOURCE_REJECTED
        if (
            contextTokens !in 1..SemanticEmbeddingPolicy.MAX_CONTEXT_TOKENS ||
            batchTokens !in 1..SemanticEmbeddingPolicy.MAX_BATCH_TOKENS ||
            batchTokens > contextTokens ||
            threadCount !in 1..SemanticEmbeddingPolicy.MAX_THREAD_COUNT ||
            maxInputUtf8Bytes !in 1..SemanticTextProfile.MAX_PREPARED_UTF8_BYTES
        ) {
            return LOAD_RESOURCE_REJECTED
        }
        if (sourcePathUtf8.isEmpty() || tokenizerPathUtf8.isEmpty()) return LOAD_REJECTED

        val encoderPath = decodePath(sourcePathUtf8) ?: return LOAD_REJECTED
        val tokenizerPath = decodePath(tokenizerPathUtf8) ?: return LOAD_REJECTED
        if (encoderPath.isBlank() || tokenizerPath.isBlank()) return LOAD_REJECTED

        loadInProgress = true
        var tokenizerOptions: OrtSession.SessionOptions? = null
        var tokenizerSession: OrtSession? = null
        var encoderOptions: OrtSession.SessionOptions? = null
        var encoderSession: OrtSession? = null

        return try {
            val environment = OrtEnvironment.getEnvironment()
            environment.setTelemetry(false)

            tokenizerOptions = createSessionOptions(
                threadCount = 1,
                registerExtensions = true
            )
            tokenizerSession = environment.createSession(tokenizerPath, tokenizerOptions)
            if (!tokenizerContractMatches(tokenizerSession)) {
                return LOAD_UNSUPPORTED
            }

            encoderOptions = createSessionOptions(
                threadCount = threadCount,
                registerExtensions = false
            )
            encoderSession = environment.createSession(encoderPath, encoderOptions)
            if (!encoderContractMatches(encoderSession)) {
                return LOAD_UNSUPPORTED
            }

            val id = nextSessionId++
            activeSessionId = id
            activeSession = OrtSemanticSession(
                environment = environment,
                tokenizerOptions = tokenizerOptions,
                tokenizerSession = tokenizerSession,
                encoderOptions = encoderOptions,
                encoderSession = encoderSession,
                maxTokens = contextTokens,
                maxInputUtf8Bytes = maxInputUtf8Bytes
            )
            id
        } catch (_: IllegalArgumentException) {
            LOAD_REJECTED
        } catch (_: Throwable) {
            LOAD_PROVIDER_FAILED
        } finally {
            if (activeSession == null) {
                closeQuietly(encoderSession)
                closeQuietly(encoderOptions)
                closeQuietly(tokenizerSession)
                closeQuietly(tokenizerOptions)
            }
            loadInProgress = false
        }
    }

    @Synchronized
    fun nativeEmbed(
        nativeSessionId: Long,
        inputUtf8: ByteArray
    ): ByteArray {
        val holder = activeSession
        if (holder == null || nativeSessionId <= 0L || nativeSessionId != activeSessionId) {
            return statusPacket(EMBED_STALE_SESSION)
        }
        if (inputUtf8.isEmpty()) return statusPacket(EMBED_REQUEST_REJECTED)
        if (inputUtf8.size > holder.maxInputUtf8Bytes) {
            return statusPacket(EMBED_RESOURCE_REJECTED)
        }

        val text = try {
            String(inputUtf8, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            return statusPacket(EMBED_REQUEST_REJECTED)
        }
        if (text.isBlank()) return statusPacket(EMBED_REQUEST_REJECTED)

        var tokenIds: LongArray? = null
        var attentionMask: LongArray? = null
        var tokenTypeIds: LongArray? = null
        return try {
            tokenIds = tokenize(holder, text)
                ?: return statusPacket(EMBED_OPERATION_FAILED)

            if (tokenIds.isEmpty()) return statusPacket(EMBED_REQUEST_REJECTED)
            if (tokenIds.size > holder.maxTokens) {
                return statusPacket(EMBED_RESOURCE_REJECTED)
            }

            attentionMask = LongArray(tokenIds.size) { 1L }
            tokenTypeIds = LongArray(tokenIds.size)

            val values = encode(
                holder = holder,
                tokenIds = tokenIds,
                attentionMask = attentionMask,
                tokenTypeIds = tokenTypeIds
            ) ?: return statusPacket(EMBED_OPERATION_FAILED)

            try {
                if (values.size != SemanticEmbeddingVector.DIMENSION) {
                    return statusPacket(EMBED_OPERATION_FAILED)
                }
                if (values.any { !it.isFinite() }) {
                    return statusPacket(EMBED_OPERATION_FAILED)
                }
                successPacket(values)
            } finally {
                values.fill(0f)
            }
        } catch (_: IllegalArgumentException) {
            statusPacket(EMBED_OPERATION_FAILED)
        } catch (_: Throwable) {
            statusPacket(EMBED_PROVIDER_FAILED)
        } finally {
            tokenIds?.fill(0L)
            attentionMask?.fill(0L)
            tokenTypeIds?.fill(0L)
        }
    }

    @Synchronized
    fun nativeClose(nativeSessionId: Long): Int {
        val holder = activeSession
        if (holder == null || nativeSessionId <= 0L || nativeSessionId != activeSessionId) {
            return CLOSE_FAILED
        }

        // Remove discoverability before physical cleanup. No new operation can observe a closing pair.
        activeSession = null
        activeSessionId = 0L

        var failed = false
        failed = closeTracked(holder.encoderSession) || failed
        failed = closeTracked(holder.encoderOptions) || failed
        failed = closeTracked(holder.tokenizerSession) || failed
        failed = closeTracked(holder.tokenizerOptions) || failed
        return if (failed) CLOSE_PROVIDER_FAILED else CLOSE_OK
    }

    private fun tokenize(
        holder: OrtSemanticSession,
        text: String
    ): LongArray? {
        OnnxTensor.createTensor(
            holder.environment,
            arrayOf(text)
        ).use { input ->
            holder.tokenizerSession.run(
                mapOf(TOKENIZER_INPUT to input),
                setOf(TOKENIZER_TOKENS_OUTPUT)
            ).use { result ->
                val output = result.get(TOKENIZER_TOKENS_OUTPUT).orElse(null)
                return extractLongVector(output?.value)
            }
        }
    }

    private fun encode(
        holder: OrtSemanticSession,
        tokenIds: LongArray,
        attentionMask: LongArray,
        tokenTypeIds: LongArray
    ): FloatArray? {
        val shape = longArrayOf(1L, tokenIds.size.toLong())
        val tokenBuffer = directLongBuffer(tokenIds)
        val maskBuffer = directLongBuffer(attentionMask)
        val typeBuffer = directLongBuffer(tokenTypeIds)

        try {
            OnnxTensor.createTensor(holder.environment, tokenBuffer, shape).use { inputIdsTensor ->
                OnnxTensor.createTensor(holder.environment, maskBuffer, shape).use { attentionTensor ->
                    OnnxTensor.createTensor(holder.environment, typeBuffer, shape).use { typeTensor ->
                        holder.encoderSession.run(
                            mapOf(
                                ENCODER_INPUT_IDS to inputIdsTensor,
                                ENCODER_ATTENTION_MASK to attentionTensor,
                                ENCODER_TOKEN_TYPE_IDS to typeTensor
                            ),
                            setOf(ENCODER_OUTPUT)
                        ).use { result ->
                            val output = result.get(ENCODER_OUTPUT).orElse(null)
                            return extractEmbedding(output?.value)
                        }
                    }
                }
            }
        } finally {
            zeroLongBuffer(tokenBuffer)
            zeroLongBuffer(maskBuffer)
            zeroLongBuffer(typeBuffer)
        }
    }

    private fun tokenizerContractMatches(session: OrtSession): Boolean =
        session.inputNames == setOf(TOKENIZER_INPUT) &&
            session.outputNames.contains(TOKENIZER_TOKENS_OUTPUT)

    private fun encoderContractMatches(session: OrtSession): Boolean =
        session.inputNames.containsAll(
            setOf(
                ENCODER_INPUT_IDS,
                ENCODER_ATTENTION_MASK,
                ENCODER_TOKEN_TYPE_IDS
            )
        ) &&
            session.outputNames == setOf(ENCODER_OUTPUT)

    private fun createSessionOptions(
        threadCount: Int,
        registerExtensions: Boolean
    ): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setInterOpNumThreads(1)
            setIntraOpNumThreads(threadCount)
            setDeterministicCompute(true)
            setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
            setSessionLogVerbosityLevel(0)
            if (registerExtensions) {
                registerCustomOpLibrary(OrtxPackage.getLibraryPath())
            }
        }

    private fun decodePath(bytes: ByteArray): String? =
        try {
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            null
        }

    private fun extractLongVector(value: Any?): LongArray? = when (value) {
        is LongArray -> value.copyOf()
        is IntArray -> LongArray(value.size) { index -> value[index].toLong() }
        is Array<*> -> {
            if (value.size != 1) return null
            when (val first = value[0]) {
                is LongArray -> first.copyOf()
                is IntArray -> LongArray(first.size) { index -> first[index].toLong() }
                else -> null
            }
        }
        else -> null
    }

    private fun extractEmbedding(value: Any?): FloatArray? = when (value) {
        is FloatArray -> value.copyOf()
        is Array<*> -> {
            if (value.size != 1) return null
            val first = value[0]
            if (first is FloatArray) first.copyOf() else null
        }
        else -> null
    }

    private fun directLongBuffer(values: LongArray): LongBuffer {
        val buffer = ByteBuffer.allocateDirect(values.size * Long.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asLongBuffer()
        buffer.put(values)
        buffer.flip()
        return buffer
    }

    private fun zeroLongBuffer(buffer: LongBuffer) {
        buffer.clear()
        while (buffer.hasRemaining()) buffer.put(0L)
        buffer.clear()
    }

    private fun closeTracked(closeable: AutoCloseable?): Boolean =
        try {
            closeable?.close()
            false
        } catch (_: Throwable) {
            true
        }

    private fun closeQuietly(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (_: Throwable) {
        }
    }

    private fun successPacket(values: FloatArray): ByteArray {
        val packet = ByteArray(1 + values.size * Float.SIZE_BYTES)
        packet[0] = EMBED_OK
        val buffer = ByteBuffer.wrap(packet, 1, packet.size - 1).order(ByteOrder.nativeOrder())
        values.forEach(buffer::putFloat)
        return packet
    }

    private fun statusPacket(status: Byte): ByteArray = byteArrayOf(status)
}
