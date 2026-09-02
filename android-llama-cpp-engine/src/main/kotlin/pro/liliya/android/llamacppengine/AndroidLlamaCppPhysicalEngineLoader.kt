package pro.liliya.android.llamacppengine

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import pro.liliya.android.protectedmodel.staging.AndroidProtectedModelPhysicalEngineLoaderPort
import pro.liliya.core.modelengine.ModelEngineBackendId
import pro.liliya.core.modelengine.ModelEngineCloseFailure
import pro.liliya.core.modelengine.ModelEngineCloseResult
import pro.liliya.core.modelengine.ModelEngineHandleId
import pro.liliya.core.modelengine.ModelEngineInferenceFailure
import pro.liliya.core.modelengine.ModelEngineInferenceRequest
import pro.liliya.core.modelengine.ModelEngineInferenceResult
import pro.liliya.core.modelengine.ModelEngineLoadFailure
import pro.liliya.core.modelengine.ModelEngineLoadResult
import pro.liliya.core.modelengine.ModelEngineSessionOwnership
import pro.liliya.core.protectedmodel.LargeProtectedModelEngineSourceCapability

private val LLAMA_CPP_BACKEND_ID = ModelEngineBackendId("llama.cpp-v0.1")

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

internal interface LlamaCppNativeSessionPort {
    fun load(sourcePath: String, policy: LlamaCppEnginePolicy): LlamaCppNativeLoadResult

    fun infer(
        nativeSessionId: Long,
        prompt: String,
        maxOutputChars: Int
    ): LlamaCppNativeInferenceResult

    fun close(nativeSessionId: Long): LlamaCppNativeCloseResult
}

/**
 * Concrete llama.cpp insertion point behind the frozen Slice 6 physical-source handoff.
 *
 * This class deliberately exposes no alternate model path/file resolver. Production protected
 * staged-model loading reaches this method only after the Core engine-use capability and the
 * Android staging backend have already revalidated the exact SEALED source.
 */
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
        // Keep the exact capability in this purpose-specific boundary; no path-only production API.
        capability.model

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
                            nativePort = nativePort
                        )
                    )
                }
            }
        }
    }
}

private class LlamaCppSessionOwnership(
    override val handleId: ModelEngineHandleId,
    private val nativeSessionId: Long,
    private val nativePort: LlamaCppNativeSessionPort
) : ModelEngineSessionOwnership {

    override val backendId: ModelEngineBackendId = LLAMA_CPP_BACKEND_ID

    // Fair serialization prevents a newly arriving infer from barging ahead of a queued close.
    // This is an engine-local lock; no Core/staging ownership lock is held during native work.
    private val executionLock = ReentrantLock(true)
    private var state = State.LIVE

    override fun infer(request: ModelEngineInferenceRequest): ModelEngineInferenceResult =
        executionLock.withLock {
            if (state != State.LIVE) {
                return@withLock ModelEngineInferenceResult.Rejected(
                    ModelEngineInferenceFailure.SESSION_FAILED
                )
            }

            val result = try {
                nativePort.infer(
                    nativeSessionId = nativeSessionId,
                    prompt = request.prompt,
                    maxOutputChars = request.maxOutputChars
                )
            } catch (_: Throwable) {
                LlamaCppNativeInferenceResult.Rejected(ModelEngineInferenceFailure.PROVIDER_FAILED)
            }

            when (result) {
                is LlamaCppNativeInferenceResult.Rejected ->
                    ModelEngineInferenceResult.Rejected(result.reason)

                is LlamaCppNativeInferenceResult.Succeeded ->
                    ModelEngineInferenceResult.Succeeded(
                        result.output.take(request.maxOutputChars)
                    )
            }
        }

    override fun close(): ModelEngineCloseResult = executionLock.withLock {
        if (state == State.CLOSED) {
            return@withLock ModelEngineCloseResult.Closed
        }

        // Once close has begun, this session never becomes inferable again, even if cleanup fails.
        state = State.CLOSING_OR_FAILED

        val result = try {
            nativePort.close(nativeSessionId)
        } catch (_: Throwable) {
            LlamaCppNativeCloseResult.Failed(ModelEngineCloseFailure.PROVIDER_FAILED)
        }

        when (result) {
            LlamaCppNativeCloseResult.Closed -> {
                state = State.CLOSED
                ModelEngineCloseResult.Closed
            }

            is LlamaCppNativeCloseResult.Failed -> ModelEngineCloseResult.Failed(result.reason)
        }
    }

    private enum class State {
        LIVE,
        CLOSING_OR_FAILED,
        CLOSED
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
        val code = LlamaCppNativeBridge.nativeLoad(
            sourcePathUtf8 = sourcePath.toByteArray(Charsets.UTF_8),
            contextTokens = policy.contextTokens,
            maxPromptTokens = policy.maxPromptTokens,
            maxGeneratedTokens = policy.maxGeneratedTokens,
            batchTokens = policy.batchTokens,
            microBatchTokens = policy.microBatchTokens,
            threadCount = policy.threadCount,
            useMmap = policy.useMmap
        )
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
        prompt: String,
        maxOutputChars: Int
    ): LlamaCppNativeInferenceResult {
        val packet = LlamaCppNativeBridge.nativeInfer(
            nativeSessionId = nativeSessionId,
            promptUtf8 = prompt.toByteArray(Charsets.UTF_8),
            maxOutputChars = maxOutputChars
        )
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
