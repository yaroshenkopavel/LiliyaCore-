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

/**
 * Platform-neutral deterministic text projection for the model-engine boundary.
 *
 * The compiler preserves the already-authoritative CognitiveContext order and intentionally projects
 * only source kind plus private content. Source IDs/generations remain Cognitive Runtime provenance and
 * are not required by the model prompt. The provider independently rechecks the final prompt bound.
 */
class DeterministicCognitiveModelRequestCompiler : CognitiveModelRequestCompilerPort {
    override fun compile(
        request: CognitiveModelRequestCompilerRequest
    ): CognitiveModelRequestCompilerResult {
        val builder = StringBuilder()

        if (!appendBounded(builder, HEADER, request.maxPromptChars)) {
            return CognitiveModelRequestCompilerResult.Rejected(
                CognitiveModelRequestCompilerFailure.RESOURCE_LIMIT_REJECTED
            )
        }
        if (!appendBounded(builder, "\nINPUT\n", request.maxPromptChars) ||
            !appendBounded(builder, request.inference.input.text, request.maxPromptChars) ||
            !appendBounded(builder, "\nCONTEXT", request.maxPromptChars)
        ) {
            return CognitiveModelRequestCompilerResult.Rejected(
                CognitiveModelRequestCompilerFailure.RESOURCE_LIMIT_REJECTED
            )
        }

        request.inference.context.items.forEachIndexed { index, item ->
            val prefix = "\n${index + 1}:${sourceKind(item.source)}\n"
            if (!appendBounded(builder, prefix, request.maxPromptChars) ||
                !appendBounded(builder, item.content, request.maxPromptChars)
            ) {
                return CognitiveModelRequestCompilerResult.Rejected(
                    CognitiveModelRequestCompilerFailure.RESOURCE_LIMIT_REJECTED
                )
            }
        }

        val prompt = builder.toString()
        if (prompt.isBlank()) {
            return CognitiveModelRequestCompilerResult.Rejected(
                CognitiveModelRequestCompilerFailure.COMPILER_REJECTED
            )
        }
        return CognitiveModelRequestCompilerResult.Compiled(
            CognitiveCompiledModelRequest(prompt)
        )
    }

    private fun appendBounded(
        builder: StringBuilder,
        value: String,
        maximum: Int
    ): Boolean {
        if (value.length > maximum - builder.length) return false
        builder.append(value)
        return true
    }

    private fun sourceKind(source: CognitiveContextSourceReference): String = when (source) {
        is CognitiveContextSourceReference.Memory -> "MEMORY"
        is CognitiveContextSourceReference.Knowledge -> "KNOWLEDGE"
        is CognitiveContextSourceReference.Self -> "SELF"
        is CognitiveContextSourceReference.Personality -> "PERSONALITY"
    }

    private companion object {
        const val HEADER = "LILIYA_COGNITIVE_REQUEST_V1"
    }
}
