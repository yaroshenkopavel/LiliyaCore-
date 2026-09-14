package pro.liliya.android.llamacppengine

import java.io.File
import pro.liliya.core.modelengine.ModelEngineInferenceRequest
import pro.liliya.core.modelengine.ModelEngineInferenceResult
import pro.liliya.core.modelengine.ModelEngineLoadResult
import pro.liliya.core.modelengine.ModelEngineSessionOwnership

/**
 * Debug-only bridge for a user-authorized physical ARM64 GGUF load/inference acceptance test.
 *
 * Prompt framing intentionally exercises the P2.3 engine-local GGUF-native chat-template boundary.
 * The selected model must provide a supported default chat template; no model-name or filename
 * heuristics and no model-specific prompt literals are used here.
 */
class ManualPhysicalLlamaSession private constructor(
    private val ownership: ModelEngineSessionOwnership
) : AutoCloseable {
    fun infer(prompt: String): Result<String> = runCatching {
        when (
            val result = ownership.infer(
                ModelEngineInferenceRequest(
                    prompt = prompt,
                    maxOutputChars = MAX_OUTPUT_CHARS
                )
            )
        ) {
            is ModelEngineInferenceResult.Succeeded -> result.output
                .trim()
                .ifEmpty { "Модель завершила ответ без текста." }
            is ModelEngineInferenceResult.Rejected ->
                error("Inference rejected: ${result.reason}")
        }
    }

    override fun close() {
        ownership.close()
    }

    companion object {
        fun load(model: File): Result<ManualPhysicalLlamaSession> = runCatching {
            require(model.isFile && model.length() > 0L) { "Selected GGUF is missing or empty" }
            val loader = AndroidLlamaCppPhysicalEngineLoader(
                LlamaCppEnginePolicy(
                    contextTokens = 2_048,
                    maxPromptTokens = 1_024,
                    maxGeneratedTokens = 192,
                    batchTokens = 256,
                    microBatchTokens = 64,
                    threadCount = 2,
                    maxPromptChars = 8_192,
                    maxPromptUtf8Bytes = 32_768,
                    maxOutputChars = MAX_OUTPUT_CHARS,
                    maxOutputUtf8Bytes = MAX_OUTPUT_CHARS * 4,
                    useMmap = true,
                    promptFormatPolicy = LlamaCppPromptFormatPolicy.MODEL_DEFAULT_CHAT_TEMPLATE
                )
            )
            when (val loaded = loader.loadValidatedPhysicalSource(model)) {
                is ModelEngineLoadResult.Loaded -> ManualPhysicalLlamaSession(loaded.ownership)
                is ModelEngineLoadResult.Rejected -> error("Model load rejected: ${loaded.reason}")
            }
        }

        private const val MAX_OUTPUT_CHARS = 768
    }
}
