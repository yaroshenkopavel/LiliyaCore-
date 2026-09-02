package pro.liliya.android.llamacppengine

/**
 * Caller-supplied resource policy for one llama.cpp engine session.
 *
 * No device-dependent defaults are guessed by this adapter. A higher layer must select and
 * authorize concrete values before load. This keeps resource admission separate from execution.
 */
data class LlamaCppEnginePolicy(
    val contextTokens: Int,
    val batchTokens: Int,
    val threadCount: Int
) {
    init {
        require(contextTokens > 0) { "context token budget must be positive" }
        require(batchTokens > 0) { "batch token budget must be positive" }
        require(batchTokens <= contextTokens) {
            "batch token budget must not exceed context token budget"
        }
        require(threadCount > 0) { "thread count must be positive" }
    }
}
