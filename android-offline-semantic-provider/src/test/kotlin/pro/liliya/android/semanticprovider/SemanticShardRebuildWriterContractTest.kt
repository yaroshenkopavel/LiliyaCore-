package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.persistence.PersistentBackendMetadata

class SemanticShardRebuildWriterContractTest {
    @Test
    fun ordered_pages_write_each_generation_shard_once_and_manifest_last() {
        val storage = CountingStorage()
        val store = SemanticShardStore(
            storage,
            SemanticModelProfileV01.PROFILE_GENERATION
        )
        val writer = SemanticShardRebuildWriter(store)

        var generation = 1L
        while (generation <= 4_097L) {
            val end = minOf(generation + 511L, 4_097L)
            val seeds = (generation..end).map { current ->
                seed(current)
            }
            assertEquals(true, writer.append(seeds))
            seeds.forEach { it.vector.clear() }
            generation = end + 1L
        }

        val manifest = assertNotNull(
            writer.finish(
                SemanticAuthoritativeMetadataCheckpoint(
                    memory = PersistentBackendMetadata(
                        revision = 4_098L,
                        highWatermark = 4_097L,
                        entryCount = 4_097L
                    ),
                    knowledge = PersistentBackendMetadata(1L, 0L, 0L)
                )
            )
        )

        assertEquals(3, manifest.shards.size)
        assertEquals(3, storage.shardWrites)
        assertEquals(1, storage.manifestWrites)
        assertEquals("manifest-v2", storage.writeOrder.last())
        writer.clear()
    }

    @Test
    fun non_monotonic_generation_input_is_rejected_fail_closed() {
        val writer = SemanticShardRebuildWriter(
            SemanticShardStore(
                CountingStorage(),
                SemanticModelProfileV01.PROFILE_GENERATION
            )
        )
        val seeds = listOf(seed(2L), seed(1L))
        assertFalse(writer.append(seeds))
        seeds.forEach { it.vector.clear() }
        writer.clear()
    }

    private class CountingStorage : AndroidOfflineSemanticShardStorage {
        private val blobs = LinkedHashMap<String, AndroidOfflineSemanticCheckpointBlob>()
        var shardWrites: Int = 0
            private set
        var manifestWrites: Int = 0
            private set
        val writeOrder = ArrayList<String>()

        override fun read(
            key: AndroidOfflineSemanticShardStorageKey
        ): AndroidOfflineSemanticShardStorageReadResult =
            blobs[key.value]?.let {
                AndroidOfflineSemanticShardStorageReadResult.Loaded(it)
            } ?: AndroidOfflineSemanticShardStorageReadResult.Missing

        override fun write(
            key: AndroidOfflineSemanticShardStorageKey,
            blob: AndroidOfflineSemanticCheckpointBlob
        ): AndroidOfflineSemanticShardStorageWriteResult {
            blobs[key.value] = AndroidOfflineSemanticCheckpointBlob(blob.copyBytes())
            writeOrder += key.value
            if (key == AndroidOfflineSemanticShardStorageKey.MANIFEST) {
                manifestWrites += 1
            } else {
                shardWrites += 1
            }
            return AndroidOfflineSemanticShardStorageWriteResult.Written
        }
    }

    private fun seed(generation: Long): SemanticIndexSeed {
        val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
        values[0] = 1f
        return SemanticIndexSeed(
            source = SemanticIndexSourceReference.Memory(
                MemoryRecordId("memory-" + generation),
                MemoryGeneration(generation)
            ),
            vector = SemanticEmbeddingVector(values)
        )
    }
}
