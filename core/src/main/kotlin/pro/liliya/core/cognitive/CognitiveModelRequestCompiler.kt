package pro.liliya.core.cognitive

class CognitiveModelRequestCompilerRequest(
    val inference: CognitiveInferenceRequest,
    val maxPromptChars: Int
) {
    init {
        require(maxPromptChars > 0) { "cognitive model prompt budget must be positive" }
    }

    override fun toString(): String =
        "CognitiveModelRequestCompilerRequest(turn=${inference.turn}, input=<redacted>, context=<redacted:${inference.context.items.size}>, maxPromptChars=$maxPromptChars, maxOutputChars=${inference.maxOutputChars})"
}

class CognitiveCompiledModelRequest(
    val prompt: String
) {
    override fun toString(): String =
        "CognitiveCompiledModelRequest(prompt=<redacted:${prompt.length}>)"
}

enum class CognitiveModelRequestCompilerFailure {
    COMPILER_REJECTED,
    RESOURCE_LIMIT_REJECTED,
    PROVIDER_FAILED
}

sealed interface CognitiveModelRequestCompilerResult {
    data class Compiled(
        val request: CognitiveCompiledModelRequest
    ) : CognitiveModelRequestCompilerResult

    data class Rejected(
        val reason: CognitiveModelRequestCompilerFailure
    ) : CognitiveModelRequestCompilerResult
}

fun interface CognitiveModelRequestCompilerPort {
    fun compile(request: CognitiveModelRequestCompilerRequest): CognitiveModelRequestCompilerResult
}
