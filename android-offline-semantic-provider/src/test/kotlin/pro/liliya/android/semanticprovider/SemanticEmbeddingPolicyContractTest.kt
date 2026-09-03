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
}
