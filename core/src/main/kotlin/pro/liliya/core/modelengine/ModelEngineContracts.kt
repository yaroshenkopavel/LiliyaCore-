package pro.liliya.core.modelengine

import pro.liliya.core.protectedmodel.ProtectedModelReference

private const val MAX_MODEL_ENGINE_BACKEND_ID_CHARS = 128
private const val MAX_MODEL_ENGINE_HANDLE_ID_CHARS = 512

@JvmInline
value class ModelEngineBackendId(val value: String) {
    init {
        require(value.isNotBlank()) { "model engine backend id must not be blank" }
        require(value.length <= MAX_MODEL_ENGINE_BACKEND_ID_CHARS) {
            "model engine backend id exceeds maximum length"
        }
    }

    override fun toString(): String = value
}

@JvmInline
value class ModelEngineHandleId(val value: String) {
    init {
        require(value.isNotBlank()) { "model engine handle id must not be blank" }
        require(value.length <= MAX_MODEL_ENGINE_HANDLE_ID_CHARS) {
            "model engine handle id exceeds maximum length"
        }
    }

    override fun toString(): String = "ModelEngineHandleId([redacted])"
}

enum class ModelEngineLoadFailure {
    UNSUPPORTED_MODEL,
    RESOURCE_LIMIT_REJECTED,
    LOAD_REJECTED,
    PROVIDER_FAILED
}

sealed interface ModelEngineLoadResult {
    data class Loaded(
        val ownership: ModelEngineSessionOwnership
    ) : ModelEngineLoadResult

    data class Rejected(
        val reason: ModelEngineLoadFailure
    ) : ModelEngineLoadResult
}

class ModelEngineInferenceRequest(
    val prompt: String,
    val maxOutputChars: Int
) {
    init {
        require(maxOutputChars > 0) { "model engine output budget must be positive" }
    }

    override fun toString(): String =
        "ModelEngineInferenceRequest(prompt=<redacted:${prompt.length}>, maxOutputChars=$maxOutputChars)"
}

enum class ModelEngineInferenceFailure {
    REQUEST_REJECTED,
    RESOURCE_LIMIT_REJECTED,
    OPERATION_FAILED,
    CANCELLED,
    TIMED_OUT,
    SESSION_FAILED,
    PROVIDER_FAILED
}

sealed interface ModelEngineInferenceResult {
    class Succeeded(
        val output: String
    ) : ModelEngineInferenceResult {
        override fun toString(): String =
            "Succeeded(output=<redacted:${output.length}>)"
    }

    data class Rejected(
        val reason: ModelEngineInferenceFailure
    ) : ModelEngineInferenceResult
}

enum class ModelEngineCloseFailure {
    CLOSE_FAILED,
    PROVIDER_FAILED
}

sealed interface ModelEngineCloseResult {
    data object Closed : ModelEngineCloseResult

    data class Failed(
        val reason: ModelEngineCloseFailure
    ) : ModelEngineCloseResult
}

interface ModelEngineSessionOwnership {
    val backendId: ModelEngineBackendId
    val handleId: ModelEngineHandleId

    fun infer(request: ModelEngineInferenceRequest): ModelEngineInferenceResult

    fun close(): ModelEngineCloseResult
}

fun interface ModelEngineLoaderPort {
    fun load(
        model: ProtectedModelReference,
        plaintext: ByteArray
    ): ModelEngineLoadResult
}
