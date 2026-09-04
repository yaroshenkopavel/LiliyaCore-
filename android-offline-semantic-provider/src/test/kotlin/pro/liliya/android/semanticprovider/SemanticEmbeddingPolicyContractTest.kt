package pro.liliya.android.semanticprovider

import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class SemanticEmbeddingPolicyContractTest {
    @Test
    fun default_native_transport_accepts_maximum_raw_passage_plus_required_prefix() {
        val raw = "a".repeat(SemanticTextProfile.MAX_RAW_UTF8_BYTES)
        val prepared = assertIs<SemanticPreparedTextResult.Prepared>(
            SemanticTextProfile.preparePassage(raw)
        )

        assertEquals(
            SemanticTextProfile.MAX_PREPARED_UTF8_BYTES,
            prepared.text.toByteArray(StandardCharsets.UTF_8).size
        )
        assertEquals(
            SemanticTextProfile.MAX_PREPARED_UTF8_BYTES,
            SemanticEmbeddingPolicy().maxInputUtf8Bytes
        )
    }

    @Test
    fun definitely_over_bound_utf16_length_is_rejected_before_utf8_encoding_path() {
        val overBound = "a".repeat(SemanticTextProfile.MAX_RAW_UTF8_BYTES + 1)

        assertEquals(
            SemanticPreparedTextResult.ResourceRejected,
            SemanticTextProfile.prepareQuery(overBound)
        )
        assertEquals(
            SemanticPreparedTextResult.ResourceRejected,
            SemanticTextProfile.preparePassage(overBound)
        )
    }

    @Test
    fun raw_utf8_over_profile_bound_is_rejected_without_truncation() {
        val overBound = "я".repeat(SemanticTextProfile.MAX_RAW_UTF8_BYTES / 2 + 1)

        assertEquals(
            SemanticPreparedTextResult.ResourceRejected,
            SemanticTextProfile.prepareQuery(overBound)
        )
        assertEquals(
            SemanticPreparedTextResult.ResourceRejected,
            SemanticTextProfile.preparePassage(overBound)
        )
    }
}
