package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.semantic.SemanticClaimId
import pro.liliya.core.semantic.SemanticClaimSourceCheckpoint
import pro.liliya.core.semantic.SemanticClaimVersion
import pro.liliya.core.semantic.SemanticClaimVersionReference

class SemanticClaimVectorRoutingContractTest {
    @Test
    fun routed_query_returns_deterministic_top_k_from_content_addressed_shards() {
        val storage = MemoryStorage()
        val projectionStore = SemanticClaimVectorProjectionStore(storage)
        val routingStore = SemanticClaimVectorRoutingStore(storage)
        val builder = SemanticClaimVectorRoutingBuilder(routingStore)

        val first = vector(0)
        val second = vector(1)
        val third = vector(2)
        val fourth = vector(3)
        val query = vector(0)
        try {
            val d0 = writeShard(
                projectionStore,
                generation = 11,
                ordinal = 0,
                listOf(entry("best", 1, first), entry("b", 1, second))
            )
            val d1 = writeShard(
                projectionStore,
                generation = 11,
                ordinal = 1,
                listOf(entry("a", 1, third), entry("c", 1, fourth))
            )
            assertTrue(builder.append(d0, envelope(first, second)))
            assertTrue(builder.append(d1, envelope(third, fourth)))
            val root = builder.finish() ?: error("routing build failed")

            val manifest = completeManifest(
                generation = 11,
                shardCount = 2,
                entries = 4,
                root = root
            )
            val result = assertIs<SemanticClaimVectorRoutingQueryResult.Routed>(
                SemanticClaimVectorRoutingQuery(projectionStore, routingStore).rank(
                    manifest,
                    query,
                    SemanticClaimVectorRoutingPolicy(
                        maxCandidates = 2,
                        maxRoutingNodeReads = 16,
                        maxShardReads = 16
                    )
                )
            )

            assertEquals(2, result.candidates.size)
            assertEquals(reference("best", 1), result.candidates.first().reference)
            assertEquals(1.0, result.candidates.first().similarity, 1e-9)
            assertEquals(reference("a", 1), result.candidates[1].reference)
            assertFalse(result.truncated)
            assertTrue(result.routingNodeReads > 0)
            assertTrue(result.shardReads > 0)
        } finally {
            listOf(first, second, third, fourth, query).forEach { it.clear() }
        }
    }

    @Test
    fun shard_read_budget_is_explicit_and_never_scans_lifetime_projection() {
        val storage = MemoryStorage()
        val projectionStore = SemanticClaimVectorProjectionStore(storage)
        val routingStore = SemanticClaimVectorRoutingStore(storage)
        val builder = SemanticClaimVectorRoutingBuilder(routingStore)
        val vectors = (0..2).map(::vector)
        val query = vector(0)
        try {
            vectors.forEachIndexed { ordinal, vector ->
                val descriptor = writeShard(
                    projectionStore,
                    generation = 17,
                    ordinal = ordinal.toLong(),
                    listOf(entry("claim-" + ordinal, 1, vector))
                )
                assertTrue(builder.append(descriptor, envelope(vector)))
            }
            val root = builder.finish() ?: error("routing build failed")
            val result = assertIs<SemanticClaimVectorRoutingQueryResult.Routed>(
                SemanticClaimVectorRoutingQuery(projectionStore, routingStore).rank(
                    completeManifest(17, 3, 3, root),
                    query,
                    SemanticClaimVectorRoutingPolicy(
                        maxCandidates = 8,
                        maxRoutingNodeReads = 8,
                        maxShardReads = 1
                    )
                )
            )
            assertEquals(1, result.shardReads)
            assertTrue(result.truncated)
            assertEquals(1, result.candidates.size)
        } finally {
            vectors.forEach { it.clear() }
            query.clear()
        }
    }

    @Test
    fun routing_node_read_budget_is_explicit_and_returns_truncated_advisory_result() {
        val storage = MemoryStorage()
        val projectionStore = SemanticClaimVectorProjectionStore(storage)
        val routingStore = SemanticClaimVectorRoutingStore(storage)
        val builder = SemanticClaimVectorRoutingBuilder(routingStore)
        val vectors = (0 until 17).map { vector(it % 8) }
        val query = vector(0)
        try {
            vectors.forEachIndexed { ordinal, vector ->
                val descriptor = writeShard(
                    projectionStore,
                    generation = 23,
                    ordinal = ordinal.toLong(),
                    listOf(entry("n-" + ordinal, 1, vector))
                )
                assertTrue(builder.append(descriptor, envelope(vector)))
            }
            val root = builder.finish() ?: error("routing build failed")
            val result = assertIs<SemanticClaimVectorRoutingQueryResult.Routed>(
                SemanticClaimVectorRoutingQuery(projectionStore, routingStore).rank(
                    completeManifest(23, 17, 17, root),
                    query,
                    SemanticClaimVectorRoutingPolicy(
                        maxCandidates = 8,
                        maxRoutingNodeReads = 1,
                        maxShardReads = 8
                    )
                )
            )
            assertEquals(1, result.routingNodeReads)
            assertEquals(0, result.shardReads)
            assertTrue(result.truncated)
        } finally {
            vectors.forEach { it.clear() }
            query.clear()
        }
    }

    @Test
    fun corrupt_content_addressed_shard_fails_closed_without_partial_ranked_success() {
        val storage = MemoryStorage()
        val projectionStore = SemanticClaimVectorProjectionStore(storage)
        val routingStore = SemanticClaimVectorRoutingStore(storage)
        val builder = SemanticClaimVectorRoutingBuilder(routingStore)
        val vector = vector(0)
        val query = vector(0)
        try {
            val descriptor = writeShard(
                projectionStore,
                generation = 29,
                ordinal = 0,
                listOf(entry("corrupt", 1, vector))
            )
            assertTrue(builder.append(descriptor, envelope(vector)))
            val root = builder.finish() ?: error("routing build failed")

            val shardKey = storage.entries.keys.single {
                it.value.startsWith("claim-vector-v1-shard-29-0-")
            }
            val bytes = storage.entries.getValue(shardKey).copyBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            storage.entries[shardKey] = AndroidOfflineSemanticCheckpointBlob(bytes)
            bytes.fill(0)

            assertIs<SemanticClaimVectorRoutingQueryResult.Corrupt>(
                SemanticClaimVectorRoutingQuery(projectionStore, routingStore).rank(
                    completeManifest(29, 1, 1, root),
                    query
                )
            )
        } finally {
            vector.clear()
            query.clear()
        }
    }

    private fun completeManifest(
        generation: Long,
        shardCount: Long,
        entries: Long,
        root: String
    ) = SemanticClaimVectorProjectionManifest(
        profileId = SemanticModelProfileV01.PROFILE_ID,
        profileGeneration = SemanticModelProfileV01.PROFILE_GENERATION.value,
        indexGeneration = generation,
        source = SemanticClaimSourceCheckpoint(5, entries, entries),
        state = SemanticClaimVectorProjectionState.COMPLETE,
        shardEntryLimit = SemanticClaimVectorProjectionManifest.MAX_SHARD_ENTRIES,
        shardCount = shardCount,
        indexedEntryCount = entries,
        routingRootSha256 = root
    )

    private fun writeShard(
        store: SemanticClaimVectorProjectionStore,
        generation: Long,
        ordinal: Long,
        entries: List<SemanticClaimVectorProjectionEntry>
    ): SemanticClaimVectorShardDescriptor {
        val shard = SemanticClaimVectorProjectionShard(
            indexGeneration = generation,
            ordinal = ordinal,
            entries = entries
        )
        val digest = store.writeShard(shard) ?: error("shard write failed")
        return SemanticClaimVectorShardDescriptor(
            indexGeneration = generation,
            ordinal = ordinal,
            entryCount = entries.size,
            blobSha256 = digest
        )
    }

    private fun entry(
        id: String,
        version: Long,
        vector: SemanticEmbeddingVector
    ) = SemanticClaimVectorProjectionEntry(reference(id, version), vector)

    private fun reference(id: String, version: Long) =
        SemanticClaimVersionReference(
            SemanticClaimId("claim-" + id),
            SemanticClaimVersion(version)
        )

    private fun vector(index: Int): SemanticEmbeddingVector {
        val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
        values[index] = 1f
        return SemanticEmbeddingVector(values)
    }

    private fun envelope(
        vararg vectors: SemanticEmbeddingVector
    ): SemanticShardRoutingEnvelope {
        val min = FloatArray(SemanticEmbeddingVector.DIMENSION) { Float.POSITIVE_INFINITY }
        val max = FloatArray(SemanticEmbeddingVector.DIMENSION) { Float.NEGATIVE_INFINITY }
        vectors.forEach { vector ->
            val values = vector.copyValues()
            try {
                values.indices.forEach { index ->
                    if (values[index] < min[index]) min[index] = values[index]
                    if (values[index] > max[index]) max[index] = values[index]
                }
            } finally {
                values.fill(0f)
            }
        }
        return SemanticShardRoutingEnvelope.fromBounds(min, max)
    }

    private class MemoryStorage : AndroidOfflineSemanticShardStorage {
        val entries = LinkedHashMap<
            AndroidOfflineSemanticShardStorageKey,
            AndroidOfflineSemanticCheckpointBlob
        >()

        override fun read(
            key: AndroidOfflineSemanticShardStorageKey
        ): AndroidOfflineSemanticShardStorageReadResult {
            val blob = entries[key] ?: return AndroidOfflineSemanticShardStorageReadResult.Missing
            return AndroidOfflineSemanticShardStorageReadResult.Loaded(
                AndroidOfflineSemanticCheckpointBlob(blob.copyBytes())
            )
        }

        override fun write(
            key: AndroidOfflineSemanticShardStorageKey,
            blob: AndroidOfflineSemanticCheckpointBlob
        ): AndroidOfflineSemanticShardStorageWriteResult {
            entries[key] = AndroidOfflineSemanticCheckpointBlob(blob.copyBytes())
            return AndroidOfflineSemanticShardStorageWriteResult.Written
        }
    }
}