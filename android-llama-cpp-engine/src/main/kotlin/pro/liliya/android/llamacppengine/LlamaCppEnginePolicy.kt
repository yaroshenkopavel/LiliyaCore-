package pro.liliya.android.llamacppengine

/**
 * Caller-supplied resource policy for one llama.cpp engine session.
 *
 * No device-dependent defaults are guessed by this adapter. A higher layer must select and
 * authorize concrete values before load. Token budgets bound native compute while character
 * and UTF-8 byte budgets bound JVM/native representation growth independently.
 */
data class LlamaCppEnginePolicy(
    val contextTokens: Int,
    val maxPromptTokens: Int,
    val maxGeneratedTokens: Int,
    val batchTokens: Int,
    val microBatchTokens: Int,
    val threadCount: Int,
    val maxPromptChars: Int,
    val maxPromptUtf8Bytes: Int,
    val maxOutputChars: Int,
    val maxOutputUtf8Bytes: Int,
    val useMmap: Boolean
) {
    init {
        require(contextTokens > 0) { "context token budget must be positive" }
        require(maxPromptTokens > 0) { "prompt token budget must be positive" }
        require(maxGeneratedTokens > 0) { "generated token budget must be positive" }
        require(batchTokens > 0) { "batch token budget must be positive" }
        require(microBatchTokens > 0) { "micro-batch token budget must be positive" }
        require(threadCount > 0) { "thread count must be positive" }
        require(maxPromptChars > 0) { "prompt character budget must be positive" }
        require(maxPromptUtf8Bytes > 0) { "prompt UTF-8 byte budget must be positive" }
        require(maxOutputChars > 0) { "output character budget must be positive" }
        require(maxOutputUtf8Bytes > 0) { "output UTF-8 byte budget must be positive" }
        require(maxPromptTokens <= contextTokens) {
            "prompt token budget must not exceed context token budget"
        }
        require(maxGeneratedTokens <= contextTokens) {
            "generated token budget must not exceed context token budget"
        }
        require(maxPromptTokens.toLong() + maxGeneratedTokens.toLong() <= contextTokens.toLong()) {
            "combined prompt and generated token budgets must fit context"
        }
        require(batchTokens <= contextTokens) {
            "batch token budget must not exceed context token budget"
        }
        require(microBatchTokens <= batchTokens) {
            "micro-batch token budget must not exceed batch token budget"
        }
        require(maxPromptUtf8Bytes.toLong() <= maxPromptChars.toLong() * 4L) {
            "prompt UTF-8 byte budget must fit prompt character ceiling"
        }
        require(maxOutputUtf8Bytes.toLong() <= maxOutputChars.toLong() * 4L) {
            "output UTF-8 byte budget must fit output character ceiling"
        }
    }
}
