package pro.liliya.android.llamacppengine

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.android.protectedmodel.staging.AndroidProtectedModelPhysicalEngineLoaderPort
import pro.liliya.core.modelengine.ModelEngineCloseFailure
import pro.liliya.core.modelengine.ModelEngineHandleId
import pro.liliya.core.modelengine.ModelEngineInferenceFailure
import pro.liliya.core.modelengine.ModelEngineLoadFailure
import pro.liliya.core.modelengine.ModelEngineLoadResult
import pro.liliya.core.protectedmodel.LargeProtectedModelEngineSourceCapability

internal sealed interface LlamaCppNativeLoadResult {
    data class Loaded(val nativeSessionId: Long) : LlamaCppNativeLoadResult
    data class Rejected(val reason: ModelEngineLoadFailure) : LlamaCppNativeLoadResult
}

internal sealed interface LlamaCppNativeInferenceResult {
    data class Succeeded(val output: String) : LlamaCppNativeInferenceResult
    data class Rejected(val reason: ModelEngineInferenceFailure) : LlamaCppNativeInferenceResult
}

internal sealed interface LlamaCppNativeCloseResult {
    data object Closed : LlamaCppNativeCloseResult
    data class Failed(val reason: ModelEngineCloseFailure) : LlamaCppNativeCloseResult
}

internal fun interface LlamaCppNativeStreamingSink {
    /** Returns false to request cooperative cancellation. */
    fun onChunk(chunkUtf8: ByteArray): Boolean
}

internal interface LlamaCppNativeSessionPort {
    fun load(sourcePath: String, policy: LlamaCppEnginePolicy): LlamaCppNativeLoadResult

    fun infer(
        nativeSessionId: Long,
        promptUtf8: ByteArray,
        maxOutputChars: Int
    ): LlamaCppNativeInferenceResult

    fun stream(
        nativeSessionId: Long,
        promptUtf8: ByteArray,
        maxOutputChars: Int,
        sink: LlamaCppNativeStreamingSink
    ): LlamaCppNativeInferenceResult =
        LlamaCppNativeInferenceResult.Rejected(ModelEngineInferenceFailure.REQUEST_REJECTED)

    fun close(nativeSessionId: Long): LlamaCppNativeCloseResult
}

/** Concrete llama.cpp insertion point behind the frozen Slice 6 physical-source handoff. */
class AndroidLlamaCppPhysicalEngineLoader internal constructor(
    private val policy: LlamaCppEnginePolicy,
    private val nativePort: LlamaCppNativeSessionPort
) : AndroidProtectedModelPhysicalEngineLoaderPort {

    constructor(policy: LlamaCppEnginePolicy) : this(
        policy = policy,
        nativePort = JniLlamaCppNativeSessionPort
    )

    override fun load(
        source: File,
        capability: LargeProtectedModelEngineSourceCapability
    ): ModelEngineLoadResult {
        capability.model
        return loadValidatedPhysicalSource(source)
    }

    internal fun loadValidatedPhysicalSource(source: File): ModelEngineLoadResult {
        val publicHandleId = PublicHandleIds.allocate()
            ?: return ModelEngineLoadResult.Rejected(ModelEngineLoadFailure.PROVIDER_FAILED)

        val nativeResult = try {
            nativePort.load(source.absolutePath, policy)
        } catch (_: Throwable) {
            return ModelEngineLoadResult.Rejected(ModelEngineLoadFailure.PROVIDER_FAILED)
        }

        return when (nativeResult) {
            is LlamaCppNativeLoadResult.Rejected -> ModelEngineLoadResult.Rejected(nativeResult.reason)
            is LlamaCppNativeLoadResult.Loaded -> {
                if (nativeResult.nativeSessionId <= 0L) {
                    ModelEngineLoadResult.Rejected(ModelEngineLoadFailure.PROVIDER_FAILED)
                } else {
                    ModelEngineLoadResult.Loaded(
                        LlamaCppSessionOwnership(
                            handleId = publicHandleId,
                            nativeSessionId = nativeResult.nativeSessionId,
                            nativePort = nativePort,
                            policy = policy
                        )
                    )
                }
            }
        }
    }
}

private object PublicHandleIds {
    private val next = AtomicLong(1L)

    fun allocate(): ModelEngineHandleId? {
        while (true) {
            val current = next.get()
            if (current <= 0L || current == Long.MAX_VALUE) {
                return null
            }
            if (next.compareAndSet(current, current + 1L)) {
                return ModelEngineHandleId("llama-session-$current")
            }
        }
    }
}

private object JniLlamaCppNativeSessionPort : LlamaCppNativeSessionPort {
    override fun load(
        sourcePath: String,
        policy: LlamaCppEnginePolicy
    ): LlamaCppNativeLoadResult {
        val grammarUtf8 = policy.structuredGrammar?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
        val code = try {
            LlamaCppNativeBridge.nativeLoad(
                sourcePathUtf8 = sourcePath.toByteArray(Charsets.UTF_8),
                contextTokens = policy.contextTokens,
                maxPromptTokens = policy.maxPromptTokens,
                maxGeneratedTokens = policy.maxGeneratedTokens,
                batchTokens = policy.batchTokens,
                microBatchTokens = policy.microBatchTokens,
                threadCount = policy.threadCount,
                maxPromptUtf8Bytes = policy.maxPromptUtf8Bytes,
                maxOutputUtf8Bytes = policy.maxOutputUtf8Bytes,
                structuredGrammarUtf8 = grammarUtf8,
                maxStructuredGrammarUtf8Bytes = policy.maxStructuredGrammarUtf8Bytes,
                useMmap = policy.useMmap
            )
        } finally {
            grammarUtf8.fill(0)
        }
        return when {
            code > 0L -> LlamaCppNativeLoadResult.Loaded(code)
            code == LlamaCppNativeBridge.LOAD_RESOURCE_REJECTED ->
                LlamaCppNativeLoadResult.Rejected(ModelEngineLoadFailure.RESOURCE_LIMIT_REJECTED)
            code == LlamaCppNativeBridge.LOAD_UNSUPPORTED ->
                LlamaCppNativeLoadResult.Rejected(ModelEngineLoadFailure.UNSUPPORTED_MODEL)
            code == LlamaCppNativeBridge.LOAD_REJECTED ->
                LlamaCppNativeLoadResult.Rejected(ModelEngineLoadFailure.LOAD_REJECTED)
            else -> LlamaCppNativeLoadResult.Rejected(ModelEngineLoadFailure.PROVIDER_FAILED)
        }
    }

    override fun infer(
        nativeSessionId: Long,
        promptUtf8: ByteArray,
        maxOutputChars: Int
    ): LlamaCppNativeInferenceResult =
        decodeInferencePacket(
            LlamaCppNativeBridge.nativeInfer(
                nativeSessionId = nativeSessionId,
                promptUtf8 = promptUtf8,
                maxOutputChars = maxOutputChars
            )
        )

    override fun stream(
        nativeSessionId: Long,
        promptUtf8: ByteArray,
        maxOutputChars: Int,
        sink: LlamaCppNativeStreamingSink
    ): LlamaCppNativeInferenceResult =
        decodeInferencePacket(
            LlamaCppNativeBridge.nativeInferStreaming(
                nativeSessionId = nativeSessionId,
                promptUtf8 = promptUtf8,
                maxOutputChars = maxOutputChars,
                sink = sink
            )
        )

    private fun decodeInferencePacket(packet: ByteArray): LlamaCppNativeInferenceResult {
        if (packet.isEmpty()) {
            return LlamaCppNativeInferenceResult.Rejected(ModelEngineInferenceFailure.PROVIDER_FAILED)
        }
        return when (packet[0]) {
            LlamaCppNativeBridge.INFER_OK -> LlamaCppNativeInferenceResult.Succeeded(
                packet.copyOfRange(1, packet.size).toString(Charsets.UTF_8)
            )
            LlamaCppNativeBridge.INFER_RESOURCE_REJECTED ->
                LlamaCppNativeInferenceResult.Rejected(
                    ModelEngineInferenceFailure.RESOURCE_LIMIT_REJECTED
                )
            LlamaCppNativeBridge.INFER_REQUEST_REJECTED ->
                LlamaCppNativeInferenceResult.Rejected(ModelEngineInferenceFailure.REQUEST_REJECTED)
            LlamaCppNativeBridge.INFER_STALE_SESSION ->
                LlamaCppNativeInferenceResult.Rejected(ModelEngineInferenceFailure.SESSION_FAILED)
            LlamaCppNativeBridge.INFER_OPERATION_FAILED ->
                LlamaCppNativeInferenceResult.Rejected(ModelEngineInferenceFailure.OPERATION_FAILED)
            LlamaCppNativeBridge.INFER_CANCELLED ->
                LlamaCppNativeInferenceResult.Rejected(ModelEngineInferenceFailure.CANCELLED)
            else -> LlamaCppNativeInferenceResult.Rejected(ModelEngineInferenceFailure.PROVIDER_FAILED)
        }
    }

    override fun close(nativeSessionId: Long): LlamaCppNativeCloseResult =
        when (LlamaCppNativeBridge.nativeClose(nativeSessionId)) {
            LlamaCppNativeBridge.CLOSE_OK -> LlamaCppNativeCloseResult.Closed
            LlamaCppNativeBridge.CLOSE_FAILED ->
                LlamaCppNativeCloseResult.Failed(ModelEngineCloseFailure.CLOSE_FAILED)
            else -> LlamaCppNativeCloseResult.Failed(ModelEngineCloseFailure.PROVIDER_FAILED)
        }
}
