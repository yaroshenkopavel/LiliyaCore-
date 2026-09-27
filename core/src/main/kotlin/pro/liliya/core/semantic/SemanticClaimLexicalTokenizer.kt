package pro.liliya.core.semantic

import java.text.Normalizer
import java.util.Locale

data class SemanticLexicalTokenization(
    val tokens: List<String>,
    val truncated: Boolean
)

object SemanticClaimLexicalTokenizer {
    const val VERSION = 1
    const val MAX_TOKEN_CODE_POINTS = 64
    const val MAX_QUERY_TOKENS = 16
    const val MAX_DOCUMENT_TOKENS = 256

    fun tokenizeQuery(text: String): SemanticLexicalTokenization =
        tokenize(text, MAX_QUERY_TOKENS)

    fun tokenizeDocument(text: String): SemanticLexicalTokenization =
        tokenize(text, MAX_DOCUMENT_TOKENS)

    private fun tokenize(
        text: String,
        maxTokens: Int
    ): SemanticLexicalTokenization {
        require(maxTokens > 0)
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        val tokens = ArrayList<String>(minOf(maxTokens, 32))
        val current = StringBuilder()
        var currentCodePoints = 0
        var truncated = false

        fun flush() {
            if (current.isEmpty()) return
            if (tokens.size < maxTokens) {
                tokens += current.toString()
            } else {
                truncated = true
            }
            current.setLength(0)
            currentCodePoints = 0
        }

        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            offset += Character.charCount(codePoint)

            if (Character.isLetterOrDigit(codePoint)) {
                if (currentCodePoints < MAX_TOKEN_CODE_POINTS) {
                    current.appendCodePoint(codePoint)
                    currentCodePoints += 1
                } else {
                    truncated = true
                }
            } else {
                flush()
            }
        }
        flush()

        return SemanticLexicalTokenization(tokens, truncated)
    }
}
