package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.persistence.PersistentBackendMetadata

class SemanticShardStoreContractTest {
    @Test
    fun manifest_and_multiple_shards_rank_to_one_global_top_k() {
        val storage = InMemoryShardStorage()
        val store = SemanticShardStore(
            storage = storage,
            profileGeneration = SemanticModelProfileV01.PROFILE_GENERATION
        )

        val shard0 = SemanticShardCheckpoint(
            version = SemanticShardCheckpoint.CURRENT_VERSION,
            shardId = SemanticShardId(SemanticIndexDomain.MEMORY, 0L),
            seeds = listOf(
                seed("m-1", 1L, normalized(0.95f, 0.05f)),
                seed("m-2", 2L, normalized(0.70f, 0.30f))
            )
        )
        val shard1 = SemanticShardCheckpoint(
            version = SemanticShardCheckpoint.CURRENT_VERSION,
            shardId = SemanticShardId(SemanticIndexDomain.MEMORY, 1L),
            seeds = listOf(
                seed("m-2049", 2_049L, normalized(0.90f, 0.10f)),
                seed("m-2050", 2_050L, normalized(0.60f, 0.40f))
            )
        )

        val descriptors = listOf(
            requireNotNull(store.writeShard(shard0)),
            requireNotNull(store.writeShard(shard1))
        )
        val manifest = SemanticShardManifest(
            version = SemanticShardManifest.CURRENT_VERSION,
            model = SemanticCheckpointModelBinding.production(),
            authoritative = authoritative(),
            entriesPerShard = SemanticShardLayout.ENTRIES_PER_SHARD,
            shards = descriptors
        )
        assertEquals(true, store.writeManifest(manifest))

        val loaded = assertIs<SemanticShardManifestLoadResult.Loaded>(
            store.loadManifest()
        ).manifest
        assertEquals(manifest, loaded)

        val ranked = assertIs<SemanticShardRankResult.Ranked>(
            store.rank(
                manifest = loaded,
                domain = SemanticIndexDomain.MEMORY,
                query = axisVector(0),
                maxCandidates = 3
            )
        ).candidates

        assertEquals(
            listOf("m-1", "m-2049", "m-2"),
            ranked.map { (it.source as SemanticIndexSourceReference.Memory).id.value }
        )
        assertEquals(2, storage.shardReadCount)
    }

    @Test
    fun missing_or_swapped_shard_fails_closed_instead_of_returning_partial_candidates() {
        val storage = InMemoryShardStorage()
        val store = SemanticShardStore(
            storage,
            SemanticModelProfileV01.PROFILE_GENERATION
        )
        val shard = SemanticShardCheckpoint(
            SemanticShardCheckpoint.CURRENT_VERSION,
            SemanticShardId(SemanticIndexDomain.MEMORY, 0L),
            listOf(seed("m-1", 1L, axisVector(0)))
        )
        val descriptor = requireNotNull(store.writeShard(shard))
        val manifest = SemanticShardManifest(
            SemanticShardManifest.CURRENT_VERSION,
            SemanticCheckpointModelBinding.production(),
            authoritative(),
            SemanticShardLayout.ENTRIES_PER_SHARD,
            listOf(descriptor)
        )

        storage.remove(AndroidOfflineSemanticShardStorageKey.forShard(descriptor.shardId))

        assertIs<SemanticShardRankResult.Corrupt>(
            store.rank(
                manifest,
                SemanticIndexDomain.MEMORY,
                axisVector(0),
                1
            )
        )
    }

    private class InMemoryShardStorage : AndroidOfflineSemanticShardStorage {
        private val blobs = LinkedHashMap<String, AndroidOfflineSemanticCheckpointBlob>()
        var shardReadCount: Int = 0
            private set

        override fun read(
            key: AndroidOfflineSemanticShardStorageKey
        ): AndroidOfflineSemanticShardStorageReadResult {
            if (key != AndroidOfflineSemanticShardStorageKey.MANIFEST) {
                shardReadCount += 1
            }
            return blobs[key.value]?.let {
                AndroidOfflineSemanticShardStorageReadResult.Loaded(it)
            } ?: AndroidOfflineSemanticShardStorageReadResult.Missing
        }

        override fun write(
            key: AndroidOfflineSemanticShardStorageKey,
            blob: AndroidOfflineSemanticCheckpointBlob
        ): AndroidOfflineSemanticShardStorageWriteResult {
            blobs[key.value] = AndroidOfflineSemanticCheckpointBlob(blob.copyBytes())
            return AndroidOfflineSemanticShardStorageWriteResult.Written
        }

        fun remove(key: AndroidOfflineSemanticShardStorageKey) {
            blobs.remove(key.value)
        }
    }

    private fun authoritative(): SemanticAuthoritativeMetadataCheckpoint =
        SemanticAuthoritativeMetadataCheckpoint(
            memory = PersistentBackendMetadata(3L, 2_050L, 2_050L),
            knowledge = PersistentBackendMetadata(1L, 0L, 0L)
        )

    private fun seed(
        id: String,
        generation: Long,
        vector: SemanticEmbeddingVector
    ): SemanticIndexSeed =
        SemanticIndexSeed(
            SemanticIndexSourceReference.Memory(
                MemoryRecordId(id),
                MemoryGeneration(generation)
            ),
            vector
        )

    private fun axisVector(axis: Int): SemanticEmbeddingVector {
        val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
        values[axis] = 1f
        return SemanticEmbeddingVector(values)
    }

    private fun normalized(first: Float, second: Float): SemanticEmbeddingVector {
        val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
        val norm = kotlin.math.sqrt((first * first + second * second).toDouble()).toFloat()
        values[0] = first / norm
        values[1] = second / norm
        return SemanticEmbeddingVector(values)
    }
}
