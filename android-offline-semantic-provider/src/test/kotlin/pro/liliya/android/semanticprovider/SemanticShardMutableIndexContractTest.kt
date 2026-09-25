package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.persistence.PersistentBackendMetadata

class SemanticShardMutableIndexContractTest {
    @Test
    fun add_replace_across_shard_boundary_remove_and_manifest_publish_are_deterministic() {
        val storage = InMemoryStorage()
        val store = SemanticShardStore(
            storage,
            SemanticModelProfileV01.PROFILE_GENERATION
        )
        val initial = SemanticShardManifest(
            version = SemanticShardManifest.CURRENT_VERSION,
            model = SemanticCheckpointModelBinding.production(),
            authoritative = authoritative(0L),
            entriesPerShard = SemanticShardLayout.ENTRIES_PER_SHARD,
            shards = emptyList()
        )
        val mutable = SemanticShardMutableIndex(store, initial)

        val source2048 = memorySource("shared", 2_048L)
        assertIs<SemanticShardMutationResult.Applied>(
            mutable.add(source2048, axisVector(0))
        )
        assertEquals(
            listOf(SemanticShardId(SemanticIndexDomain.MEMORY, 0L)),
            mutable.currentManifest().shards.map { it.shardId }
        )

        val rankedBeforePersist = assertIs<SemanticShardRankResult.Ranked>(
            mutable.rank(
                SemanticIndexDomain.MEMORY,
                axisVector(0),
                1
            )
        ).candidates
        assertEquals(listOf(source2048), rankedBeforePersist.map { it.source })
        assertEquals(null, storage.persistedManifest())

        val authoritativeAfterAdd = authoritative(2_048L)
        assertEquals(true, mutable.persistManifest(authoritativeAfterAdd))
        assertEquals(
            authoritativeAfterAdd,
            requireNotNull(storage.persistedManifest()).authoritative
        )

        val source2049 = memorySource("shared", 2_049L)
        assertIs<SemanticShardMutationResult.Applied>(
            mutable.replace(
                expected = source2048,
                replacement = source2049,
                replacementVector = axisVector(1)
            )
        )
        assertEquals(
            listOf(SemanticShardId(SemanticIndexDomain.MEMORY, 1L)),
            mutable.currentManifest().shards.map { it.shardId }
        )
        assertEquals(
            listOf(source2049),
            assertIs<SemanticShardRankResult.Ranked>(
                mutable.rank(
                    SemanticIndexDomain.MEMORY,
                    axisVector(1),
                    1
                )
            ).candidates.map { it.source }
        )

        assertIs<SemanticShardMutationResult.Applied>(
            mutable.remove(source2049)
        )
        assertEquals(emptyList(), mutable.currentManifest().shards)
        assertEquals(
            emptyList(),
            assertIs<SemanticShardRankResult.Ranked>(
                mutable.rank(
                    SemanticIndexDomain.MEMORY,
                    axisVector(1),
                    4
                )
            ).candidates
        )
    }

    @Test
    fun rebuild_batch_groups_unordered_generations_into_bounded_sorted_shards() {
        val storage = InMemoryStorage()
        val store = SemanticShardStore(
            storage,
            SemanticModelProfileV01.PROFILE_GENERATION
        )
        val mutable = SemanticShardMutableIndex(
            store,
            SemanticShardManifest(
                SemanticShardManifest.CURRENT_VERSION,
                SemanticCheckpointModelBinding.production(),
                authoritative(0L),
                SemanticShardLayout.ENTRIES_PER_SHARD,
                emptyList()
            )
        )
        val vector = axisVector(0)
        val seeds = listOf(
            SemanticIndexSeed(memorySource("g4097", 4_097L), vector),
            SemanticIndexSeed(memorySource("g1", 1L), vector),
            SemanticIndexSeed(memorySource("g2049", 2_049L), vector),
            SemanticIndexSeed(memorySource("g2", 2L), vector)
        )

        assertEquals(true, mutable.appendRebuildBatch(seeds))
        assertEquals(
            listOf(
                SemanticShardId(SemanticIndexDomain.MEMORY, 0L),
                SemanticShardId(SemanticIndexDomain.MEMORY, 1L),
                SemanticShardId(SemanticIndexDomain.MEMORY, 2L)
            ),
            mutable.currentManifest().shards.map { it.shardId }
        )

        assertEquals(
            listOf("g1", "g2", "g2049", "g4097"),
            assertIs<SemanticShardRankResult.Ranked>(
                mutable.rank(
                    SemanticIndexDomain.MEMORY,
                    axisVector(0),
                    8
                )
            ).candidates.map {
                (it.source as SemanticIndexSourceReference.Memory).id.value
            }
        )
    }

    @Test
    fun stale_replace_and_duplicate_add_do_not_publish_new_descriptor_state() {
        val storage = InMemoryStorage()
        val store = SemanticShardStore(
            storage,
            SemanticModelProfileV01.PROFILE_GENERATION
        )
        val initial = SemanticShardManifest(
            SemanticShardManifest.CURRENT_VERSION,
            SemanticCheckpointModelBinding.production(),
            authoritative(0L),
            SemanticShardLayout.ENTRIES_PER_SHARD,
            emptyList()
        )
        val mutable = SemanticShardMutableIndex(store, initial)
        val first = memorySource("entity", 1L)

        assertIs<SemanticShardMutationResult.Applied>(
            mutable.add(first, axisVector(0))
        )
        val descriptor = mutable.currentManifest().shards.single()

        assertIs<SemanticShardMutationResult.AlreadyApplied>(
            mutable.add(first, axisVector(0))
        )
        assertEquals(descriptor, mutable.currentManifest().shards.single())

        assertIs<SemanticShardMutationResult.StaleOrConflicting>(
            mutable.replace(
                expected = memorySource("entity", 2L),
                replacement = memorySource("entity", 3L),
                replacementVector = axisVector(1)
            )
        )
        assertEquals(descriptor, mutable.currentManifest().shards.single())
    }

    private class InMemoryStorage : AndroidOfflineSemanticShardStorage {
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

        fun persistedManifest(): SemanticShardManifest? {
            val blob = blobs[AndroidOfflineSemanticShardStorageKey.MANIFEST.value]
                ?: return null
            return assertIs<SemanticShardManifestDecodeResult.Decoded>(
                SemanticShardCheckpointCodec.decodeManifest(blob)
            ).manifest
        }
    }

    private fun authoritative(highWatermark: Long): SemanticAuthoritativeMetadataCheckpoint =
        SemanticAuthoritativeMetadataCheckpoint(
            memory = PersistentBackendMetadata(
                revision = maxOf(1L, highWatermark),
                highWatermark = highWatermark,
                entryCount = if (highWatermark == 0L) 0L else 1L
            ),
            knowledge = PersistentBackendMetadata(1L, 0L, 0L)
        )

    private fun memorySource(
        id: String,
        generation: Long
    ): SemanticIndexSourceReference.Memory =
        SemanticIndexSourceReference.Memory(
            MemoryRecordId(id),
            MemoryGeneration(generation)
        )

    private fun axisVector(axis: Int): SemanticEmbeddingVector {
        val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
        values[axis] = 1f
        return SemanticEmbeddingVector(values)
    }
}
