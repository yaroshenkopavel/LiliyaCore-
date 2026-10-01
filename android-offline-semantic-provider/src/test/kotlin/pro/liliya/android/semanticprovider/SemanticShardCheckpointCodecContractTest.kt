package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.persistence.PersistentBackendMetadata

class SemanticShardCheckpointCodecContractTest {
    @Test
    fun shard_round_trip_preserves_exact_shard_identity_sources_and_vectors() {
        val original = SemanticShardCheckpoint(
            version = SemanticShardCheckpoint.CURRENT_VERSION,
            shardId = SemanticShardId(SemanticIndexDomain.MEMORY, 0L),
            seeds = listOf(
                seed(memorySource("memory-1", 1L), axisVector(0)),
                seed(memorySource("memory-2048", 2_048L), axisVector(1))
            )
        )

        val encoded = SemanticShardCheckpointCodec.encodeShard(original)
        val decoded = assertIs<SemanticShardDecodeResult.Decoded>(
            SemanticShardCheckpointCodec.decodeShard(encoded)
        ).checkpoint

        assertEquals(original.shardId, decoded.shardId)
        assertEquals(original.seeds.map { it.source }, decoded.seeds.map { it.source })
        original.seeds.zip(decoded.seeds).forEach { (expected, actual) ->
            assertContentEquals(expected.vector.copyValues(), actual.vector.copyValues())
        }
    }

    @Test
    fun manifest_round_trip_binds_model_authoritative_metadata_and_sorted_shard_digests() {
        val memoryBlob = SemanticShardCheckpointCodec.encodeShard(
            SemanticShardCheckpoint(
                SemanticShardCheckpoint.CURRENT_VERSION,
                SemanticShardId(SemanticIndexDomain.MEMORY, 0L),
                listOf(seed(memorySource("memory", 1L), axisVector(0)))
            )
        )
        val knowledgeBlob = SemanticShardCheckpointCodec.encodeShard(
            SemanticShardCheckpoint(
                SemanticShardCheckpoint.CURRENT_VERSION,
                SemanticShardId(SemanticIndexDomain.KNOWLEDGE, 3L),
                listOf(seed(knowledgeSource("knowledge", 6_145L), axisVector(1)))
            )
        )
        val manifest = SemanticShardManifest(
            version = SemanticShardManifest.CURRENT_VERSION,
            model = SemanticCheckpointModelBinding.production(),
            authoritative = authoritative(),
            entriesPerShard = SemanticShardLayout.ENTRIES_PER_SHARD,
            shards = listOf(
                SemanticShardDescriptor(
                    SemanticShardId(SemanticIndexDomain.MEMORY, 0L),
                    1,
                    SemanticShardCheckpointCodec.shardDigest(memoryBlob)
                ),
                SemanticShardDescriptor(
                    SemanticShardId(SemanticIndexDomain.KNOWLEDGE, 3L),
                    1,
                    SemanticShardCheckpointCodec.shardDigest(knowledgeBlob)
                )
            )
        )

        val decoded = assertIs<SemanticShardManifestDecodeResult.Decoded>(
            SemanticShardCheckpointCodec.decodeManifest(
                SemanticShardCheckpointCodec.encodeManifest(manifest)
            )
        ).manifest

        assertEquals(manifest, decoded)
        assertEquals(true, decoded.matches(SemanticCheckpointModelBinding.production(), authoritative()))
    }

    @Test
    fun shard_and_manifest_tamper_are_rejected_as_corrupt() {
        val shardBlob = SemanticShardCheckpointCodec.encodeShard(
            SemanticShardCheckpoint(
                SemanticShardCheckpoint.CURRENT_VERSION,
                SemanticShardId(SemanticIndexDomain.MEMORY, 0L),
                listOf(seed(memorySource("memory", 1L), axisVector(0)))
            )
        )
        assertIs<SemanticShardDecodeResult.Corrupt>(
            SemanticShardCheckpointCodec.decodeShard(tamper(shardBlob))
        )

        val manifest = SemanticShardManifest(
            version = SemanticShardManifest.CURRENT_VERSION,
            model = SemanticCheckpointModelBinding.production(),
            authoritative = authoritative(),
            entriesPerShard = SemanticShardLayout.ENTRIES_PER_SHARD,
            shards = listOf(
                SemanticShardDescriptor(
                    SemanticShardId(SemanticIndexDomain.MEMORY, 0L),
                    1,
                    SemanticShardCheckpointCodec.shardDigest(shardBlob)
                )
            )
        )
        assertIs<SemanticShardManifestDecodeResult.Corrupt>(
            SemanticShardCheckpointCodec.decodeManifest(
                tamper(SemanticShardCheckpointCodec.encodeManifest(manifest))
            )
        )
    }

    private fun authoritative(): SemanticAuthoritativeMetadataCheckpoint =
        SemanticAuthoritativeMetadataCheckpoint(
            memory = PersistentBackendMetadata(7L, 9_000L, 8_500L),
            knowledge = PersistentBackendMetadata(4L, 7_000L, 6_800L)
        )

    private fun memorySource(id: String, generation: Long) =
        SemanticIndexSourceReference.Memory(
            MemoryRecordId(id),
            MemoryGeneration(generation)
        )

    private fun knowledgeSource(id: String, generation: Long) =
        SemanticIndexSourceReference.Knowledge(
            KnowledgeItemId(id),
            KnowledgeGeneration(generation)
        )

    private fun seed(
        source: SemanticIndexSourceReference,
        vector: SemanticEmbeddingVector
    ) = SemanticIndexSeed(source, vector)

    private fun axisVector(axis: Int): SemanticEmbeddingVector {
        val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
        values[axis] = 1f
        return SemanticEmbeddingVector(values)
    }

    private fun tamper(
        blob: AndroidOfflineSemanticCheckpointBlob
    ): AndroidOfflineSemanticCheckpointBlob {
        val bytes = blob.copyBytes()
        bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 0x01).toByte()
        return AndroidOfflineSemanticCheckpointBlob(bytes)
    }
}
