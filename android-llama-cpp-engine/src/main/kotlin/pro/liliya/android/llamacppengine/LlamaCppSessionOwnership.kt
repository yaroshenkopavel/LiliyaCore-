package pro.liliya.android.llamacppengine

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import pro.liliya.core.modelengine.ModelEngineBackendId
import pro.liliya.core.modelengine.ModelEngineCloseFailure
import pro.liliya.core.modelengine.ModelEngineCloseResult
import pro.liliya.core.modelengine.ModelEngineHandleId
import pro.liliya.core.modelengine.ModelEngineInferenceFailure
import pro.liliya.core.modelengine.ModelEngineInferenceRequest
import pro.liliya.core.modelengine.ModelEngineInferenceResult
import pro.liliya.core.modelengine.ModelEngineStreamChunk
import pro.liliya.core.modelengine.ModelEngineStreamControl
import pro.liliya.core.modelengine.ModelEngineStreamingSessionOwnership
import pro.liliya.core.modelengine.ModelEngineStreamingSink

private val LLAMA_CPP_BACKEND_ID = ModelEngineBackendId("llama.cpp-v0.1")

/** Internal per-native-allocation ownership. No native pointer is exposed through this object. */
internal class LlamaCppSessionOwnership(
    override val handleId: ModelEngineHandleId,
    private val nativeSessionId: Long,
    private val nativePort: LlamaCppNativeSessionPort,
    private val policy: LlamaCppEnginePolicy
) : ModelEngineStreamingSessionOwnership {

    init {
        require(nativeSessionId > 0L) { "native session id must be positive" }
    }

    override val backendId: ModelEngineBackendId = LLAMA_CPP_BACKEND_ID

    // Fair serialization prevents a newly arriving infer from barging ahead of a queued close.
    // This is engine-local; no Core/staging/backend ownership lock is held during native work.
    private val executionLock = ReentrantLock(true)
    private var state = State.LIVE
    private var operationInProgress = false

    override fun infer(request: ModelEngineInferenceRequest): ModelEngineInferenceResult =
        executionLock.withLock {
            if (state != State.LIVE) {
                return@withLock ModelEngineInferenceResult.Rejected(
                    ModelEngineInferenceFailure.SESSION_FAILED
                )
            }
            if (operationInProgress) {
                return@withLock ModelEngineInferenceResult.Rejected(
                    ModelEngineInferenceFailure.RESOURCE_LIMIT_REJECTED
                )
            }
            if (
                request.prompt.length > policy.maxPromptChars ||
                request.maxOutputChars > policy.maxOutputChars
            ) {
                return@withLock ModelEngineInferenceResult.Rejected(
                    ModelEngineInferenceFailure.RESOURCE_LIMIT_REJECTED
                )
            }

            val promptUtf8 = try {
                request.prompt.toByteArray(Charsets.UTF_8)
            } catch (_: Throwable) {
                return@withLock ModelEngineInferenceResult.Rejected(
                    ModelEngineInferenceFailure.PROVIDER_FAILED
                )
            }
            if (promptUtf8.size > policy.maxPromptUtf8Bytes) {
                promptUtf8.fill(0)
                return@withLock ModelEngineInferenceResult.Rejected(
                    ModelEngineInferenceFailure.RESOURCE_LIMIT_REJECTED
                )
            }

            operationInProgress = true
            val result = try {
                nativePort.infer(
                    nativeSessionId = nativeSessionId,
                    promptUtf8 = promptUtf8,
                    maxOutputChars = request.maxOutputChars
                )
            } catch (_: Throwable) {
                LlamaCppNativeInferenceResult.Rejected(ModelEngineInferenceFailure.PROVIDER_FAILED)
            } finally {
                operationInProgress = false
                promptUtf8.fill(0)
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

    override fun stream(
        request: ModelEngineInferenceRequest,
        sink: ModelEngineStreamingSink
    ): ModelEngineInferenceResult = executionLock.withLock {
        if (state != State.LIVE) {
            return@withLock ModelEngineInferenceResult.Rejected(
                ModelEngineInferenceFailure.SESSION_FAILED
            )
        }
        if (operationInProgress) {
            return@withLock ModelEngineInferenceResult.Rejected(
                ModelEngineInferenceFailure.RESOURCE_LIMIT_REJECTED
            )
        }
        if (
            request.prompt.length > policy.maxPromptChars ||
            request.maxOutputChars > policy.maxOutputChars
        ) {
            return@withLock ModelEngineInferenceResult.Rejected(
                ModelEngineInferenceFailure.RESOURCE_LIMIT_REJECTED
            )
        }

        val promptUtf8 = try {
            request.prompt.toByteArray(Charsets.UTF_8)
        } catch (_: Throwable) {
            return@withLock ModelEngineInferenceResult.Rejected(
                ModelEngineInferenceFailure.PROVIDER_FAILED
            )
        }
        if (promptUtf8.size > policy.maxPromptUtf8Bytes) {
            promptUtf8.fill(0)
            return@withLock ModelEngineInferenceResult.Rejected(
                ModelEngineInferenceFailure.RESOURCE_LIMIT_REJECTED
            )
        }

        var sequence = 0L
        var stopped = false
        var contractViolation = false
        val collected = StringBuilder()
        operationInProgress = true
        val result = try {
            nativePort.stream(
                nativeSessionId = nativeSessionId,
                promptUtf8 = promptUtf8,
                maxOutputChars = request.maxOutputChars,
                sink = LlamaCppNativeStreamingSink { chunkUtf8 ->
                    if (chunkUtf8.isEmpty()) {
                        contractViolation = true
                        return@LlamaCppNativeStreamingSink false
                    }
                    val text = try {
                        chunkUtf8.toString(Charsets.UTF_8)
                    } catch (_: Throwable) {
                        contractViolation = true
                        return@LlamaCppNativeStreamingSink false
                    }
                    if (text.isEmpty()) {
                        contractViolation = true
                        return@LlamaCppNativeStreamingSink false
                    }
                    val nextChars = collected.length + text.length
                    if (
                        nextChars > request.maxOutputChars ||
                        nextChars > policy.maxOutputChars
                    ) {
                        contractViolation = true
                        return@LlamaCppNativeStreamingSink false
                    }

                    val nextSequence = sequence + 1L
                    val control = try {
                        sink.onChunk(ModelEngineStreamChunk(nextSequence, text))
                    } catch (_: Throwable) {
                        ModelEngineStreamControl.STOP
                    }
                    if (control == ModelEngineStreamControl.STOP) {
                        stopped = true
                        false
                    } else {
                        sequence = nextSequence
                        collected.append(text)
                        true
                    }
                }
            )
        } catch (_: Throwable) {
            LlamaCppNativeInferenceResult.Rejected(ModelEngineInferenceFailure.PROVIDER_FAILED)
        } finally {
            operationInProgress = false
            promptUtf8.fill(0)
        }

        if (contractViolation) {
            return@withLock ModelEngineInferenceResult.Rejected(
                ModelEngineInferenceFailure.PROVIDER_FAILED
            )
        }

        if (stopped) {
            return@withLock when (result) {
                is LlamaCppNativeInferenceResult.Rejected ->
                    if (result.reason == ModelEngineInferenceFailure.CANCELLED) {
                        ModelEngineInferenceResult.Rejected(ModelEngineInferenceFailure.CANCELLED)
                    } else {
                        ModelEngineInferenceResult.Rejected(ModelEngineInferenceFailure.PROVIDER_FAILED)
                    }
                is LlamaCppNativeInferenceResult.Succeeded ->
                    ModelEngineInferenceResult.Rejected(ModelEngineInferenceFailure.PROVIDER_FAILED)
            }
        }

        when (result) {
            is LlamaCppNativeInferenceResult.Rejected ->
                ModelEngineInferenceResult.Rejected(result.reason)

            is LlamaCppNativeInferenceResult.Succeeded -> {
                if (
                    result.output.length > request.maxOutputChars ||
                    result.output.length > policy.maxOutputChars ||
                    result.output != collected.toString()
                ) {
                    ModelEngineInferenceResult.Rejected(
                        ModelEngineInferenceFailure.PROVIDER_FAILED
                    )
                } else {
                    ModelEngineInferenceResult.Succeeded(result.output)
                }
            }
        }
    }

    override fun close(): ModelEngineCloseResult = executionLock.withLock {
        if (operationInProgress) {
            return@withLock ModelEngineCloseResult.Failed(
                ModelEngineCloseFailure.CLOSE_FAILED
            )
        }
        if (state == State.CLOSED) {
            return@withLock ModelEngineCloseResult.Closed
        }

        // A failed close keeps the native/Core lease relationship fail-closed. Inference never
        // becomes live again, but close may be retried if the provider can finish cleanup later.
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
