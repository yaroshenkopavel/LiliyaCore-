package pro.liliya.android.semanticprovider

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

internal data class SemanticEmbeddingPolicy(
    val contextTokens: Int = 512,
    val batchTokens: Int = 512,
    val threadCount: Int = 1,
    val maxInputUtf8Bytes: Int = SemanticTextProfile.MAX_PREPARED_UTF8_BYTES,
    val useMmap: Boolean = true
) {
    init {
        require(contextTokens > 0)
        require(batchTokens > 0)
        require(threadCount > 0)
        require(maxInputUtf8Bytes > 0)
        require(batchTokens <= contextTokens)
    }
}

internal sealed interface SemanticEmbeddingSessionLoadResult {
    data class Loaded(val session: SemanticEmbeddingSessionOwnership) : SemanticEmbeddingSessionLoadResult
    data object ResourceRejected : SemanticEmbeddingSessionLoadResult
    data object Unsupported : SemanticEmbeddingSessionLoadResult
    data object Rejected : SemanticEmbeddingSessionLoadResult
    data object ModelLoadRejectedDiagnostic : SemanticEmbeddingSessionLoadResult
    data object ContextInitRejectedDiagnostic : SemanticEmbeddingSessionLoadResult
    data object ProviderFailed : SemanticEmbeddingSessionLoadResult
}

internal sealed interface SemanticEmbeddingResult {
    data class Embedded(val vector: SemanticEmbeddingVector) : SemanticEmbeddingResult {
        override fun toString(): String = "Embedded(vector=<redacted:384>)"
    }

    data object ResourceRejected : SemanticEmbeddingResult
    data object RequestRejected : SemanticEmbeddingResult
    data object StaleSession : SemanticEmbeddingResult
    data object OperationFailed : SemanticEmbeddingResult
    data object ProviderFailed : SemanticEmbeddingResult
}

internal sealed interface SemanticEmbeddingCloseResult {
    data object Closed : SemanticEmbeddingCloseResult
    data object StaleOrAlreadyClosed : SemanticEmbeddingCloseResult
    data object ProviderFailed : SemanticEmbeddingCloseResult
}

internal class SemanticEmbeddingSessionLoader(
    private val policy: SemanticEmbeddingPolicy = SemanticEmbeddingPolicy()
) {
    fun load(artifact: ValidatedSemanticModelArtifact): SemanticEmbeddingSessionLoadResult {
        val path = artifact.file.absolutePath.toByteArray(StandardCharsets.UTF_8)
        return try {
            val nativeId = SemanticNativeBridge.nativeLoad(
                sourcePathUtf8 = path,
                contextTokens = policy.contextTokens,
                batchTokens = policy.batchTokens,
                threadCount = policy.threadCount,
                maxInputUtf8Bytes = policy.maxInputUtf8Bytes,
                useMmap = policy.useMmap
            )
            when (nativeId) {
                SemanticNativeBridge.LOAD_RESOURCE_REJECTED -> SemanticEmbeddingSessionLoadResult.ResourceRejected
                SemanticNativeBridge.LOAD_UNSUPPORTED -> SemanticEmbeddingSessionLoadResult.Unsupported
                SemanticNativeBridge.LOAD_REJECTED ->
                    SemanticEmbeddingSessionLoadResult.Rejected
                SemanticNativeBridge.LOAD_MODEL_REJECTED_DIAGNOSTIC ->
                    SemanticEmbeddingSessionLoadResult.ModelLoadRejectedDiagnostic
                SemanticNativeBridge.LOAD_CONTEXT_REJECTED_DIAGNOSTIC ->
                    SemanticEmbeddingSessionLoadResult.ContextInitRejectedDiagnostic
                SemanticNativeBridge.LOAD_PROVIDER_FAILED -> SemanticEmbeddingSessionLoadResult.ProviderFailed
                else -> if (nativeId > 0L) {
                    SemanticEmbeddingSessionLoadResult.Loaded(
                        SemanticEmbeddingSessionOwnership(nativeId, policy.maxInputUtf8Bytes)
                    )
                } else {
                    SemanticEmbeddingSessionLoadResult.ProviderFailed
                }
            }
        } catch (_: Throwable) {
            SemanticEmbeddingSessionLoadResult.ProviderFailed
        } finally {
            path.fill(0)
        }
    }
}

internal class SemanticEmbeddingSessionOwnership internal constructor(
    private var nativeSessionId: Long,
    private val maxInputUtf8Bytes: Int
) {
    @Synchronized
    fun embed(text: String): SemanticEmbeddingResult {
        val sessionId = nativeSessionId
        if (sessionId <= 0L) return SemanticEmbeddingResult.StaleSession
        if (text.isBlank()) return SemanticEmbeddingResult.RequestRejected

        val utf8 = text.toByteArray(StandardCharsets.UTF_8)
        return try {
            if (utf8.size > maxInputUtf8Bytes) return SemanticEmbeddingResult.ResourceRejected
            val packet = SemanticNativeBridge.nativeEmbed(sessionId, utf8)
            decodePacket(packet)
        } catch (_: Throwable) {
            SemanticEmbeddingResult.ProviderFailed
        } finally {
            utf8.fill(0)
        }
    }

    @Synchronized
    fun close(): SemanticEmbeddingCloseResult {
        val sessionId = nativeSessionId
        if (sessionId <= 0L) return SemanticEmbeddingCloseResult.StaleOrAlreadyClosed
        val result = try {
            SemanticNativeBridge.nativeClose(sessionId)
        } catch (_: Throwable) {
            SemanticNativeBridge.CLOSE_PROVIDER_FAILED
        }
        return when (result) {
            SemanticNativeBridge.CLOSE_OK -> {
                nativeSessionId = 0L
                SemanticEmbeddingCloseResult.Closed
            }
            SemanticNativeBridge.CLOSE_FAILED -> SemanticEmbeddingCloseResult.StaleOrAlreadyClosed
            else -> SemanticEmbeddingCloseResult.ProviderFailed
        }
    }

    override fun toString(): String = "SemanticEmbeddingSessionOwnership(nativeSessionId=<redacted>)"

    private fun decodePacket(packet: ByteArray?): SemanticEmbeddingResult {
        if (packet == null || packet.isEmpty()) return SemanticEmbeddingResult.ProviderFailed
        return when (packet[0]) {
            SemanticNativeBridge.EMBED_OK -> {
                val expectedBytes = 1 + SemanticEmbeddingVector.DIMENSION * Float.SIZE_BYTES
                if (packet.size != expectedBytes) return SemanticEmbeddingResult.ProviderFailed
                val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
                val buffer = ByteBuffer.wrap(packet, 1, packet.size - 1).order(ByteOrder.nativeOrder())
                for (index in values.indices) values[index] = buffer.float
                try {
                    SemanticEmbeddingResult.Embedded(SemanticEmbeddingVector(values))
                } catch (_: IllegalArgumentException) {
                    SemanticEmbeddingResult.OperationFailed
                } finally {
                    values.fill(0f)
                    packet.fill(0)
                }
            }
            SemanticNativeBridge.EMBED_RESOURCE_REJECTED -> SemanticEmbeddingResult.ResourceRejected
            SemanticNativeBridge.EMBED_REQUEST_REJECTED -> SemanticEmbeddingResult.RequestRejected
            SemanticNativeBridge.EMBED_STALE_SESSION -> SemanticEmbeddingResult.StaleSession
            SemanticNativeBridge.EMBED_OPERATION_FAILED -> SemanticEmbeddingResult.OperationFailed
            SemanticNativeBridge.EMBED_PROVIDER_FAILED -> SemanticEmbeddingResult.ProviderFailed
            else -> SemanticEmbeddingResult.ProviderFailed
        }
    }
}
