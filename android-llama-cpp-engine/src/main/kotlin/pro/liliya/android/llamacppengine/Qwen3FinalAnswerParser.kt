package pro.liliya.android.llamacppengine

/**
 * Conservative, opt-in Qwen3 final-answer extraction.
 * Never treats an unterminated thinking block as a user-visible answer.
 * Does not remove tags embedded inside ordinary final-answer text.
 */
internal object Qwen3FinalAnswerParser {
    sealed interface Result {
        data class Final(val text: String) : Result
        data object IncompleteThinking : Result
        data object EmptyFinal : Result
        data class Malformed(val original: String) : Result
    }

    fun parse(output: String): Result {
        val leading = output.trimStart()
        if (!leading.startsWith("<think>")) {
            return if (output.isBlank()) Result.EmptyFinal else Result.Final(output)
        }
        val end = leading.indexOf("</think>", startIndex = "<think>".length)
        if (end < 0) return Result.IncompleteThinking
        val final = leading.substring(end + "</think>".length).trimStart()
        if (final.isBlank()) return Result.EmptyFinal
        // A second opening tag after the first completed block is ambiguous.
        if (final.startsWith("<think>")) return Result.Malformed(output)
        return Result.Final(final)
    }
}
