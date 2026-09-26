package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.persistence.PersistentBackendMetadata

class SemanticShardRoutingQueryContractTest {
    @Test
    fun routed_top_k_matches_exhaustive_ranking() {
        val fixture = Fixture()
        val descriptors = fixture.publishShards(
            count = 64,
            targetOrdinal = 63
        )
        val manifest = fixture.publishManifest(descriptors)
        val routing = requireNotNull(fixture.routingBuilder.rebuild(manifest))
        assertTrue(routing.matches(manifest))

        val query = axisVector(0)
        try {
            val exhaustive = assertIs<SemanticShardRankResult.Ranked>(
                fixture.shardStore.rankDescriptors(
                    descriptors,
                    SemanticIndexDomain.MEMORY,
                    query,
                    5
                )
            ).candidates

            val routed = assertIs<SemanticShardRoutingQueryResult.Routed>(
                fixture.routingQuery.rank(
                    manifest,
                    SemanticIndexDomain.MEMORY,
                    query,
                    5
                )
            )

            assertEquals(
                exhaustive.map { it.source },
                routed.candidates.map { it.source }
            )
            assertEquals(
                exhaustive.map { it.similarity },
                routed.candidates.map { it.similarity }
            )
            // Equal-score candidates must remain eligible for generation/UTF-8 tie-breaks.
            // Sublinear work is asserted separately only for a mathematically selective query.
        } finally {
            query.clear()
        }
    }

    @Test
    fun selective_query_does_not_scan_lifetime_shard_set() {
        val fixture = Fixture()
        val shardCount = 512
        val targetOrdinal = shardCount - 1
        val descriptors = fixture.publishShards(
            count = shardCount,
            targetOrdinal = targetOrdinal
        )
        val manifest = fixture.publishManifest(descriptors)
        val routing = requireNotNull(fixture.routingBuilder.rebuild(manifest))

        assertTrue(routing.matches(manifest))
        assertTrue(routing.memory.depth >= 3)

        val query = axisVector(0)
        try {
            val routed = assertIs<SemanticShardRoutingQueryResult.Routed>(
                fixture.routingQuery.rank(
                    manifest,
                    SemanticIndexDomain.MEMORY,
                    query,
                    1
                )
            )
            assertEquals(1, routed.candidates.size)
            val top = routed.candidates.single().source as SemanticIndexSourceReference.Memory
            assertEquals(
                targetOrdinal.toLong() * SemanticShardLayout.ENTRIES_PER_SHARD + 1L,
                top.generationValue
            )

            // This is the #448 scale contract. A linear scan would read all 512 shard blobs.
            // The selective branch-and-bound query must stay far below the lifetime shard count.
            assertTrue(
                routed.shardReads <= 4,
                "expected <=4 shard reads, got " + routed.shardReads + " of " + shardCount
            )
            assertTrue(
                routed.routingNodeReads < shardCount / 4,
                "routing node reads unexpectedly scale with all shards: " +
                    routed.routingNodeReads
            )
        } finally {
            query.clear()
        }
    }

    @Test
    fun production_segmented_index_uses_routing_and_pending_mutation_forces_exact_fallback() {
        val fixture = Fixture()
        val shardCount = 128
        val descriptors = fixture.publishShards(
            count = shardCount,
            targetOrdinal = shardCount - 1
        )
        val manifest = fixture.publishManifest(descriptors)
        val index = SemanticShardSegmentedIndexV3(
            shardStore = fixture.shardStore,
            manifestStore = fixture.manifestStore,
            initialRoot = manifest
        )
        assertTrue(index.ensureRouting())

        val query = axisVector(0)
        try {
            fixture.storage.resetShardReads()
            val clean = assertIs<SemanticShardRankResult.Ranked>(
                index.rank(SemanticIndexDomain.MEMORY, query, 1)
            )
            assertEquals(1, clean.candidates.size)
            assertTrue(
                fixture.storage.shardReadCount <= 4,
                "production routed path read " + fixture.storage.shardReadCount +
                    " shard blobs of " + shardCount
            )

            val newGeneration =
                shardCount.toLong() * SemanticShardLayout.ENTRIES_PER_SHARD + 1L
            val newSource = SemanticIndexSourceReference.Memory(
                MemoryRecordId("routing-pending"),
                MemoryGeneration(newGeneration)
            )
            val pendingVector = axisVector(1)
            try {
                assertEquals(
                    SemanticShardMutationResult.Applied,
                    index.add(newSource, pendingVector)
                )
            } finally {
                pendingVector.clear()
            }

            fixture.storage.resetShardReads()
            val withPending = assertIs<SemanticShardRankResult.Ranked>(
                index.rank(SemanticIndexDomain.MEMORY, query, 1)
            )
            assertEquals(1, withPending.candidates.size)
            assertTrue(
                fixture.storage.shardReadCount >= shardCount,
                "pending mutation must force correctness-preserving exhaustive ranking"
            )

            assertTrue(
                index.persistManifest(
                    SemanticAuthoritativeMetadataCheckpoint(
                        memory = PersistentBackendMetadata(
                            revision = shardCount.toLong() + 2L,
                            highWatermark = newGeneration,
                            entryCount = shardCount.toLong() + 1L
                        ),
                        knowledge = PersistentBackendMetadata(1L, 0L, 0L)
                    )
                )
            )

            fixture.storage.resetShardReads()
            val afterCommit = assertIs<SemanticShardRankResult.Ranked>(
                index.rank(SemanticIndexDomain.MEMORY, query, 1)
            )
            assertEquals(1, afterCommit.candidates.size)
            assertTrue(
                fixture.storage.shardReadCount <= 4,
                "routing should be rebuilt after manifest commit"
            )
        } finally {
            query.clear()
        }
    }

    @Test
    fun missing_routing_on_active_index_falls_back_to_exact_v3_ranking() {
        val fixture = Fixture()
        val descriptors = fixture.publishShards(count = 32, targetOrdinal = 31)
        val manifest = fixture.publishManifest(descriptors)
        val index = SemanticShardSegmentedIndexV3(
            shardStore = fixture.shardStore,
            manifestStore = fixture.manifestStore,
            initialRoot = manifest
        )

        val query = axisVector(0)
        try {
            fixture.storage.resetShardReads()
            val ranked = assertIs<SemanticShardRankResult.Ranked>(
                index.rank(SemanticIndexDomain.MEMORY, query, 1)
            )
            val top = ranked.candidates.single().source as SemanticIndexSourceReference.Memory
            assertEquals(
                31L * SemanticShardLayout.ENTRIES_PER_SHARD + 1L,
                top.generationValue
            )
            assertEquals(32, fixture.storage.shardReadCount)
        } finally {
            query.clear()
        }
    }

    @Test
    fun stale_routing_root_is_not_trusted() {
        val fixture = Fixture()
        val descriptors = fixture.publishShards(count = 2, targetOrdinal = 1)
        val manifest = fixture.publishManifest(descriptors)
        requireNotNull(fixture.routingBuilder.rebuild(manifest))

        val differentManifest = manifest.copy(
            publicationId = "f".repeat(32),
            manifestBindingSha256 = "e".repeat(64)
        )
        val query = axisVector(0)
        try {
            assertIs<SemanticShardRoutingQueryResult.Stale>(
                fixture.routingQuery.rank(
                    differentManifest,
                    SemanticIndexDomain.MEMORY,
                    query,
                    1
                )
            )
        } finally {
            query.clear()
        }
    }

    @Test
    fun missing_routing_root_is_explicit_not_partial_success() {
        val fixture = Fixture()
        val descriptors = fixture.publishShards(count = 1, targetOrdinal = 0)
        val manifest = fixture.publishManifest(descriptors)
        val query = axisVector(0)
        try {
            assertIs<SemanticShardRoutingQueryResult.Missing>(
                fixture.routingQuery.rank(
                    manifest,
                    SemanticIndexDomain.MEMORY,
                    query,
                    1
                )
            )
        } finally {
            query.clear()
        }
    }

    private class Fixture {
        val storage = InMemoryStorage()
        val shardStore = SemanticShardStore(
            storage,
            SemanticModelProfileV01.PROFILE_GENERATION
        )
        val manifestStore = SemanticShardManifestV3Store(storage)
        val routingStore = SemanticShardRoutingStore(storage)
        val routingBuilder = SemanticShardRoutingBuilder(
            shardStore,
            manifestStore,
            routingStore
        )
        val routingQuery = SemanticShardRoutingQuery(shardStore, routingStore)

        fun publishShards(
            count: Int,
            targetOrdinal: Int
        ): List<SemanticShardDescriptor> {
            require(count > 0)
            require(targetOrdinal in 0 until count)
            return (0 until count).map { ordinal ->
                val generation =
                    ordinal.toLong() * SemanticShardLayout.ENTRIES_PER_SHARD + 1L
                val source = SemanticIndexSourceReference.Memory(
                    MemoryRecordId("routing-memory-" + ordinal),
                    MemoryGeneration(generation)
                )
                val vector = if (ordinal == targetOrdinal) {
                    axisVector(0)
                } else {
                    axisVector(1)
                }
                try {
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
            }
        }

        fun publishManifest(
            descriptors: List<SemanticShardDescriptor>
        ): SemanticShardManifestRootV3 {
            val writer = SemanticShardManifestV3PublicationWriter(
                store = manifestStore,
                publicationId = "d".repeat(32)
            )
            descriptors.forEach { check(writer.append(it)) }
            val highWatermark =
                (descriptors.size - 1L) * SemanticShardLayout.ENTRIES_PER_SHARD + 1L
            return requireNotNull(
                writer.finish(
                    SemanticAuthoritativeMetadataCheckpoint(
                        memory = PersistentBackendMetadata(
                            revision = descriptors.size.toLong() + 1L,
                            highWatermark = highWatermark,
                            entryCount = descriptors.size.toLong()
                        ),
                        knowledge = PersistentBackendMetadata(1L, 0L, 0L)
                    )
                )
            )
        }
    }

    private class InMemoryStorage : AndroidOfflineSemanticShardStorage {
        private val blobs = LinkedHashMap<String, AndroidOfflineSemanticCheckpointBlob>()
        var shardReadCount: Int = 0
            private set

        fun resetShardReads() {
            shardReadCount = 0
        }

        override fun read(
            key: AndroidOfflineSemanticShardStorageKey
        ): AndroidOfflineSemanticShardStorageReadResult {
            if (key.value.startsWith("memory-") || key.value.startsWith("knowledge-")) {
                shardReadCount += 1
            }
            return blobs[key.value]?.let {
                AndroidOfflineSemanticShardStorageReadResult.Loaded(
                    AndroidOfflineSemanticCheckpointBlob(it.copyBytes())
                )
            } ?: AndroidOfflineSemanticShardStorageReadResult.Missing
        }

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

    private companion object {
        fun axisVector(axis: Int): SemanticEmbeddingVector {
            val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
            values[axis] = 1f
            return try {
                SemanticEmbeddingVector(values)
            } finally {
                values.fill(0f)
            }
        }
    }
}
