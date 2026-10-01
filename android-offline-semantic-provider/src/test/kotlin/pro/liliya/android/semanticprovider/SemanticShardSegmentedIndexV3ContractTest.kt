package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.persistence.PersistentBackendMetadata

class SemanticShardSegmentedIndexV3ContractTest {
    @Test
    fun deterministic_global_top_k_crosses_manifest_segment_boundary() {
        val storage = InMemoryDeletableStorage()
        val shardStore = SemanticShardStore(
            storage,
            SemanticModelProfileV01.PROFILE_GENERATION
        )
        val manifestStore = SemanticShardManifestV3Store(storage)
        val writer = SemanticShardManifestV3PublicationWriter(
            store = manifestStore,
            publicationId = "d".repeat(32)
        )

        var highWatermark = 0L
        repeat(SemanticShardManifestRootV3.DESCRIPTORS_PER_SEGMENT + 1) { ordinal ->
            val generation =
                ordinal.toLong() * SemanticShardLayout.ENTRIES_PER_SHARD + 1L
            highWatermark = generation
            val source = SemanticIndexSourceReference.Memory(
                MemoryRecordId("memory-segmented-" + ordinal),
                MemoryGeneration(generation)
            )
            val vector = vectorForOrdinal(ordinal)
            val descriptor = try {
                requireNotNull(
                    shardStore.writeShard(
                        SemanticShardCheckpoint(
                            version = SemanticShardCheckpoint.CURRENT_VERSION,
                            shardId = SemanticShardLayout.shardFor(source),
                            seeds = listOf(SemanticIndexSeed(source, vector))
                        )
                    )
                )
            } finally {
                vector.clear()
            }
            assertTrue(writer.append(descriptor))
        }

        val count =
            (SemanticShardManifestRootV3.DESCRIPTORS_PER_SEGMENT + 1).toLong()
        val root = requireNotNull(
            writer.finish(
                SemanticAuthoritativeMetadataCheckpoint(
                    memory = PersistentBackendMetadata(
                        revision = count + 1L,
                        highWatermark = highWatermark,
                        entryCount = count
                    ),
                    knowledge = PersistentBackendMetadata(1L, 0L, 0L)
                )
            )
        )
        assertEquals(2L, root.segmentCount)
        assertEquals(count, root.shardDescriptorCount)

        val index = SemanticShardSegmentedIndexV3(
            shardStore = shardStore,
            manifestStore = manifestStore,
            initialRoot = root
        )
        assertTrue(index.validate())

        val query = unitVector(0)
        try {
            val first = assertIs<SemanticShardRankResult.Ranked>(
                index.rank(SemanticIndexDomain.MEMORY, query, 2)
            ).candidates
            val second = assertIs<SemanticShardRankResult.Ranked>(
                index.rank(SemanticIndexDomain.MEMORY, query, 2)
            ).candidates

            assertEquals(2, first.size)
            assertEquals(first.map { it.source }, second.map { it.source })

            val top = first.first().source as SemanticIndexSourceReference.Memory
            assertEquals(highWatermark, top.generationValue)

            val runnerUp = first[1].source as SemanticIndexSourceReference.Memory
            assertEquals(1L, runnerUp.generationValue)
        } finally {
            query.clear()
        }
    }

    @Test
    fun missing_manifest_segment_fails_closed_validation() {
        val storage = InMemoryDeletableStorage()
        val manifestStore = SemanticShardManifestV3Store(storage)
        val writer = SemanticShardManifestV3PublicationWriter(
            store = manifestStore,
            publicationId = "e".repeat(32)
        )

        val descriptorCount =
            SemanticShardManifestRootV3.DESCRIPTORS_PER_SEGMENT + 1
        repeat(descriptorCount) { ordinal ->
            assertTrue(
                writer.append(
                    SemanticShardDescriptor(
                        shardId = SemanticShardId(
                            SemanticIndexDomain.MEMORY,
                            ordinal.toLong()
                        ),
                        entryCount = 1,
                        blobSha256 = "a".repeat(64)
                    )
                )
            )
        }

        val count = descriptorCount.toLong()
        val root = requireNotNull(
            writer.finish(
                SemanticAuthoritativeMetadataCheckpoint(
                    memory = PersistentBackendMetadata(
                        revision = count + 1L,
                        highWatermark = count,
                        entryCount = count
                    ),
                    knowledge = PersistentBackendMetadata(1L, 0L, 0L)
                )
            )
        )
        assertEquals(2L, root.segmentCount)

        assertIs<AndroidOfflineSemanticShardStorageDeleteResult.Deleted>(
            storage.delete(
                AndroidOfflineSemanticShardStorageKey.forManifestSegment(
                    root.publicationId,
                    1L
                )
            )
        )

        val index = SemanticShardSegmentedIndexV3(
            shardStore = SemanticShardStore(
                storage,
                SemanticModelProfileV01.PROFILE_GENERATION
            ),
            manifestStore = manifestStore,
            initialRoot = root
        )
        assertEquals(false, index.validate())
        assertIs<SemanticShardManifestSegmentLoadResult.Missing>(
            manifestStore.readSegment(root, 1L)
        )
    }

    private fun vectorForOrdinal(ordinal: Int): SemanticEmbeddingVector {
        val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
        when (ordinal) {
            0 -> {
                values[0] = 0.8f
                values[1] = 0.6f
            }
            SemanticShardManifestRootV3.DESCRIPTORS_PER_SEGMENT -> {
                values[0] = 1f
            }
            else -> {
                values[1] = 1f
            }
        }
        return try {
            SemanticEmbeddingVector(values)
        } finally {
            values.fill(0f)
        }
    }

    private fun unitVector(index: Int): SemanticEmbeddingVector {
        val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
        values[index] = 1f
        return try {
            SemanticEmbeddingVector(values)
        } finally {
            values.fill(0f)
        }
    }

    private class InMemoryDeletableStorage : AndroidOfflineSemanticShardStorage {
        private val blobs = LinkedHashMap<String, AndroidOfflineSemanticCheckpointBlob>()

        override fun read(
            key: AndroidOfflineSemanticShardStorageKey
        ): AndroidOfflineSemanticShardStorageReadResult =
            blobs[key.value]?.let {
                AndroidOfflineSemanticShardStorageReadResult.Loaded(
                    AndroidOfflineSemanticCheckpointBlob(it.copyBytes())
                )
            } ?: AndroidOfflineSemanticShardStorageReadResult.Missing

        override fun write(
            key: AndroidOfflineSemanticShardStorageKey,
            blob: AndroidOfflineSemanticCheckpointBlob
        ): AndroidOfflineSemanticShardStorageWriteResult {
            blobs[key.value] = AndroidOfflineSemanticCheckpointBlob(blob.copyBytes())
            return AndroidOfflineSemanticShardStorageWriteResult.Written
        }

        override fun delete(
            key: AndroidOfflineSemanticShardStorageKey
        ): AndroidOfflineSemanticShardStorageDeleteResult =
            if (blobs.remove(key.value) != null) {
                AndroidOfflineSemanticShardStorageDeleteResult.Deleted
            } else {
                AndroidOfflineSemanticShardStorageDeleteResult.Missing
            }
    }
}
