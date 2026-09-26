package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.persistence.PersistentBackendMetadata

class SemanticIndexCheckpointCodecContractTest {
    @Test
    fun round_trip_preserves_exact_binding_metadata_sources_and_vectors() {
        val original = checkpoint()
        val encoded = SemanticCheckpointCodec.encode(original)
        val decoded = assertIs<SemanticCheckpointReadResult.Loaded>(
            SemanticCheckpointCodec.decode(encoded)
        ).checkpoint

        assertEquals(original.version, decoded.version)
        assertEquals(original.model, decoded.model)
        assertEquals(original.authoritative, decoded.authoritative)
        assertEquals(original.seeds.size, decoded.seeds.size)
        original.seeds.zip(decoded.seeds).forEach { (expected, actual) ->
            assertEquals(expected.source, actual.source)
            assertContentEquals(
                expected.vector.copyValues(),
                actual.vector.copyValues()
            )
        }
    }

    @Test
    fun single_byte_tamper_is_corrupt() {
        val encoded = SemanticCheckpointCodec.encode(checkpoint()).copyBytes()
        try {
            encoded[encoded.size / 2] = (encoded[encoded.size / 2].toInt() xor 0x01).toByte()
            val tampered = AndroidOfflineSemanticCheckpointBlob(encoded)
            assertIs<SemanticCheckpointReadResult.Corrupt>(
                SemanticCheckpointCodec.decode(tampered)
            )
        } finally {
            encoded.fill(0)
        }
    }

    @Test
    fun unsupported_version_with_valid_digest_is_incompatible() {
        val encoded = SemanticCheckpointCodec.encode(checkpoint()).copyBytes()
        try {
            // version is the second big-endian Int after the 4-byte magic.
            encoded[7] = 2
            val bodySize = encoded.size - 32
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(encoded.copyOfRange(0, bodySize))
            try {
                digest.copyInto(encoded, bodySize)
            } finally {
                digest.fill(0)
            }

            assertIs<SemanticCheckpointReadResult.Incompatible>(
                SemanticCheckpointCodec.decode(
                    AndroidOfflineSemanticCheckpointBlob(encoded)
                )
            )
        } finally {
            encoded.fill(0)
        }
    }

    @Test
    fun public_blob_string_never_exposes_checkpoint_bytes() {
        val marker = "private-semantic-checkpoint"
        val blob = AndroidOfflineSemanticCheckpointBlob(marker.encodeToByteArray())
        val rendered = blob.toString()

        assertTrue(rendered.contains("redacted"))
        assertTrue(rendered.contains(marker.length.toString()))
        assertFalse(rendered.contains(marker))
    }

    private fun checkpoint(): SemanticIndexCheckpoint =
        SemanticIndexCheckpoint(
            version = SemanticIndexCheckpoint.CURRENT_VERSION,
            model = SemanticCheckpointModelBinding.production(),
            authoritative = SemanticAuthoritativeMetadataCheckpoint(
                memory = PersistentBackendMetadata(
                    revision = 7L,
                    highWatermark = 11L,
                    entryCount = 2L
                ),
                knowledge = PersistentBackendMetadata(
                    revision = 5L,
                    highWatermark = 9L,
                    entryCount = 1L
                )
            ),
            seeds = listOf(
                SemanticIndexSeed(
                    source = SemanticIndexSourceReference.Memory(
                        MemoryRecordId("checkpoint-memory"),
                        MemoryGeneration(11L)
                    ),
                    vector = vector(0)
                ),
                SemanticIndexSeed(
                    source = SemanticIndexSourceReference.Knowledge(
                        KnowledgeItemId("checkpoint-knowledge"),
                        KnowledgeGeneration(9L)
                    ),
                    vector = vector(1)
                )
            )
        )

    private fun vector(index: Int): SemanticEmbeddingVector =
        SemanticEmbeddingVector(
            FloatArray(SemanticEmbeddingVector.DIMENSION).also { values ->
                values[index] = 1.0f
            }
        )
}
