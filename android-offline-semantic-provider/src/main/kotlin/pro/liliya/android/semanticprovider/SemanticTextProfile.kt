package pro.liliya.android.semanticprovider

import java.nio.charset.StandardCharsets

internal sealed interface SemanticPreparedTextResult {
    data class Prepared(val text: String) : SemanticPreparedTextResult {
        override fun toString(): String = "Prepared(text=<redacted>)"
    }

    data object RequestRejected : SemanticPreparedTextResult
    data object ResourceRejected : SemanticPreparedTextResult
}

/**
 * Exact Offline Semantic Provider v0.1 E5 text profile.
 *
 * The canonical 4096-byte limit applies to caller plaintext BEFORE the mandatory E5 prefix.
 * The native/session transport is separately bounded to raw bytes plus the longest permitted
 * prefix; this prevents a valid 4096-byte source from being rejected merely because `passage: `
 * is required by the embedding profile.
 */
internal object SemanticTextProfile {
    const val MAX_RAW_UTF8_BYTES = 4096
    const val QUERY_PREFIX = "query: "
    const val PASSAGE_PREFIX = "passage: "
    const val MAX_PREPARED_UTF8_BYTES = MAX_RAW_UTF8_BYTES + 9 // "passage: ".

    fun prepareQuery(raw: String): SemanticPreparedTextResult = prepare(raw, QUERY_PREFIX)

    fun preparePassage(raw: String): SemanticPreparedTextResult = prepare(raw, PASSAGE_PREFIX)

    private fun prepare(raw: String, prefix: String): SemanticPreparedTextResult {
        if (raw.isBlank()) return SemanticPreparedTextResult.RequestRejected
        // UTF-8 uses at least one byte for every UTF-16 code unit represented by valid text.
        // Reject definitely-over-bound input before allocating a full encoded copy.
        if (raw.length > MAX_RAW_UTF8_BYTES) {
            return SemanticPreparedTextResult.ResourceRejected
        }
        val rawBytes = raw.toByteArray(StandardCharsets.UTF_8)
        return try {
            if (rawBytes.size > MAX_RAW_UTF8_BYTES) {
                SemanticPreparedTextResult.ResourceRejected
            } else {
                val prepared = prefix + raw
                val preparedSize = prepared.toByteArray(StandardCharsets.UTF_8).size
                if (preparedSize > MAX_PREPARED_UTF8_BYTES) {
                    SemanticPreparedTextResult.ResourceRejected
                } else {
                    SemanticPreparedTextResult.Prepared(prepared)
                }
            }
        } finally {
            rawBytes.fill(0)
        }
    }
}
