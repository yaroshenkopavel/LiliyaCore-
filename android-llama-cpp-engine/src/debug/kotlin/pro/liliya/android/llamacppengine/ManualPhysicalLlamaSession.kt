package pro.liliya.android.llamacppengine

import java.io.File
import pro.liliya.core.modelengine.ModelEngineInferenceRequest
import pro.liliya.core.modelengine.ModelEngineInferenceResult
import pro.liliya.core.modelengine.ModelEngineLoadResult
import pro.liliya.core.modelengine.ModelEngineSessionOwnership

/** Debug-only bridge for a user-authorized manual ARM64 GGUF load/inference acceptance test. */
class ManualPhysicalLlamaSession private constructor(
    private val ownership: ModelEngineSessionOwnership
) : AutoCloseable {
    fun infer(prompt: String): Result<String> = runCatching {
        val chatPrompt = buildQwenChatPrompt(prompt)
        when (
            val result = ownership.infer(
                ModelEngineInferenceRequest(
                    prompt = chatPrompt,
                    maxOutputChars = MAX_OUTPUT_CHARS
                )
            )
        ) {
            is ModelEngineInferenceResult.Succeeded -> cleanQwenOutput(result.output)
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
                    useMmap = true
                )
            )
            when (val loaded = loader.loadValidatedPhysicalSource(model)) {
                is ModelEngineLoadResult.Loaded -> ManualPhysicalLlamaSession(loaded.ownership)
                is ModelEngineLoadResult.Rejected -> error("Model load rejected: ${loaded.reason}")
            }
        }

        private fun buildQwenChatPrompt(userMessage: String): String {
            val safeMessage = userMessage
                .replace("<|im_start|>", "")
                .replace("<|im_end|>", "")
            return buildString {
                append("<|im_start|>system\n")
                append("Ты Liliya. Отвечай по-русски, кратко и прямо. Не повторяй ответ.")
                append("<|im_end|>\n<|im_start|>user\n")
                append(safeMessage)
                append("<|im_end|>\n<|im_start|>assistant\n")
            }
        }

        private fun cleanQwenOutput(output: String): String = output
            .substringBefore("<|im_end|>")
            .substringBefore("<|im_start|>")
            .trim()
            .ifEmpty { "Модель завершила ответ без текста." }

        private const val MAX_OUTPUT_CHARS = 768
    }
}
