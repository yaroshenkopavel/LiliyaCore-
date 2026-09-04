package pro.liliya.android.semanticprovider

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Structural backend protocol preserved from the original provider boundary.
 *
 * The implementation is now pure Kotlin/Java over the pinned ONNX Runtime Android API.
 * The ONNX artifact contract is end-to-end: UTF-8 string input named "text" and one
 * 384-dimensional normalized float output named "embedding". Tokenization, attention-mask
 * handling, mean pooling and L2 normalization belong to the reviewed ONNX graph.
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

    private const val INPUT_NAME = "text"
    private const val OUTPUT_NAME = "embedding"

    private data class OrtSemanticSession(
        val environment: OrtEnvironment,
        val options: OrtSession.SessionOptions,
        val session: OrtSession,
        val maxInputUtf8Bytes: Int
    )

    private var nextSessionId = 1L
    private var loadInProgress = false
    private var activeSessionId = 0L
    private var activeSession: OrtSemanticSession? = null

    @Synchronized
    fun nativeLinkProbe(): Int = try {
        OrtEnvironment.getEnvironment()
        if (OrtxPackage.getLibraryPath().isBlank()) 0 else 1
    } catch (_: Throwable) {
        0
    }

    @Synchronized
    fun nativeLoad(
        sourcePathUtf8: ByteArray,
        contextTokens: Int,
        batchTokens: Int,
        threadCount: Int,
        maxInputUtf8Bytes: Int,
        useMmap: Boolean
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
        if (sourcePathUtf8.isEmpty()) return LOAD_REJECTED

        val modelPath = try {
            String(sourcePathUtf8, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            return LOAD_REJECTED
        }
        if (modelPath.isBlank()) return LOAD_REJECTED

        loadInProgress = true
        var options: OrtSession.SessionOptions? = null
        var session: OrtSession? = null
        return try {
            val environment = OrtEnvironment.getEnvironment()
            options = OrtSession.SessionOptions().apply {
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setInterOpNumThreads(1)
                setIntraOpNumThreads(threadCount)
                setDeterministicCompute(true)
                registerCustomOpLibrary(OrtxPackage.getLibraryPath())
            }

            session = environment.createSession(modelPath, options)
            if (
                !session.inputNames.contains(INPUT_NAME) ||
                !session.outputNames.contains(OUTPUT_NAME)
            ) {
                session.close()
                options.close()
                session = null
                options = null
                return LOAD_UNSUPPORTED
            }

            val id = nextSessionId++
            activeSessionId = id
            activeSession = OrtSemanticSession(
                environment = environment,
                options = options,
                session = session,
                maxInputUtf8Bytes = maxInputUtf8Bytes
            )
            id
        } catch (_: IllegalArgumentException) {
            LOAD_REJECTED
        } catch (_: Throwable) {
            LOAD_PROVIDER_FAILED
        } finally {
            if (activeSession == null) {
                runCatching { session?.close() }
                runCatching { options?.close() }
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

        return try {
            OnnxTensor.createTensor(holder.environment, arrayOf(text)).use { tensor ->
                holder.session.run(
                    mapOf(INPUT_NAME to tensor),
                    setOf(OUTPUT_NAME)
                ).use { result ->
                    val values = extractEmbedding(result[0].value)
                        ?: return statusPacket(EMBED_OPERATION_FAILED)
                    try {
                        if (values.size != SemanticEmbeddingVector.DIMENSION) {
                            return statusPacket(EMBED_OPERATION_FAILED)
                        }
                        successPacket(values)
                    } finally {
                        values.fill(0f)
                    }
                }
            }
        } catch (_: IllegalArgumentException) {
            statusPacket(EMBED_OPERATION_FAILED)
        } catch (_: Throwable) {
            statusPacket(EMBED_PROVIDER_FAILED)
        }
    }

    @Synchronized
    fun nativeClose(nativeSessionId: Long): Int {
        val holder = activeSession
        if (holder == null || nativeSessionId <= 0L || nativeSessionId != activeSessionId) {
            return CLOSE_FAILED
        }

        // Clear ownership before cleanup so no new operation can observe a closing session.
        activeSession = null
        activeSessionId = 0L
        return try {
            holder.session.close()
            holder.options.close()
            CLOSE_OK
        } catch (_: Throwable) {
            CLOSE_PROVIDER_FAILED
        }
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

    private fun successPacket(values: FloatArray): ByteArray {
        val packet = ByteArray(1 + values.size * Float.SIZE_BYTES)
        packet[0] = EMBED_OK
        val buffer = ByteBuffer.wrap(packet, 1, packet.size - 1).order(ByteOrder.nativeOrder())
        values.forEach(buffer::putFloat)
        return packet
    }

    private fun statusPacket(status: Byte): ByteArray = byteArrayOf(status)
}
