package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.semantic.SemanticClaimId
import pro.liliya.core.semantic.SemanticClaimSourceCheckpoint
import pro.liliya.core.semantic.SemanticClaimVersion
import pro.liliya.core.semantic.SemanticClaimVersionReference

class SemanticClaimVectorProjectionDiscoveryContractTest {
    @Test
    fun complete_projection_uses_shared_embedding_and_returns_exact_identity() {
        val storage = MemoryStorage()
        val projectionStore = SemanticClaimVectorProjectionStore(storage)
        val routingStore = SemanticClaimVectorRoutingStore(storage)
        val source = SemanticClaimSourceCheckpoint(7, 9, 1)
        val vector = vector(0)
        val query = vector(0)
        var embeddedText: String? = null
        try {
            val shard = SemanticClaimVectorProjectionShard(
                indexGeneration = 31,
                ordinal = 0,
                entries = listOf(
                    SemanticClaimVectorProjectionEntry(reference("one", 4), vector)
                )
            )
            val digest = projectionStore.writeShard(shard) ?: error("shard write failed")
            val builder = SemanticClaimVectorRoutingBuilder(routingStore)
            assertTrue(
                builder.append(
                    SemanticClaimVectorShardDescriptor(31, 0, 1, digest),
                    envelope(vector)
                )
            )
            val root = builder.finish() ?: error("routing build failed")
            assertTrue(
                projectionStore.writeManifest(
                    SemanticClaimVectorProjectionManifest(
                        profileId = SemanticModelProfileV01.PROFILE_ID,
                        profileGeneration = SemanticModelProfileV01.PROFILE_GENERATION.value,
                        indexGeneration = 31,
                        source = source,
                        state = SemanticClaimVectorProjectionState.COMPLETE,
                        shardEntryLimit = 128,
                        shardCount = 1,
                        indexedEntryCount = 1,
                        routingRootSha256 = root
                    )
                )
            )

            val discovery = OfflineSemanticClaimVectorProjectionDiscovery(
                embedding = SemanticClaimVectorQueryEmbeddingPort { text ->
                    embeddedText = text
                    OfflineSemanticSharedEmbeddingResult.Embedded(
                        SemanticEmbeddingVector(query.copyValues())
                    )
                },
                projectionStore = projectionStore,
                routingStore = routingStore
            )
            val result = assertIs<OfflineSemanticClaimVectorDiscoveryResult.Ranked>(
                discovery.discover("remembered preference", 8)
            )
            assertEquals("remembered preference", embeddedText)
            assertEquals(source, result.identity.source)
            assertEquals(31L, result.identity.indexGeneration)
            assertEquals(reference("one", 4), result.candidates.single().reference)
            assertEquals(1.0, result.candidates.single().similarity, 1e-9)
        } finally {
            vector.clear()
            query.clear()
        }
    }

    @Test
    fun incomplete_or_profile_incompatible_projection_is_unavailable_before_embedding() {
        val storage = MemoryStorage()
        val store = SemanticClaimVectorProjectionStore(storage)
        var embeddingCalls = 0
        val discovery = OfflineSemanticClaimVectorProjectionDiscovery(
            embedding = SemanticClaimVectorQueryEmbeddingPort {
                embeddingCalls += 1
                OfflineSemanticSharedEmbeddingResult.RequestRejected
            },
            projectionStore = store,
            routingStore = SemanticClaimVectorRoutingStore(storage)
        )

        assertTrue(
            store.writeManifest(
                SemanticClaimVectorProjectionManifest(
                    profileId = SemanticModelProfileV01.PROFILE_ID,
                    profileGeneration = 1,
                    indexGeneration = 1,
                    source = SemanticClaimSourceCheckpoint(1, 0, 0),
                    state = SemanticClaimVectorProjectionState.INCOMPLETE,
                    shardEntryLimit = 128,
                    shardCount = 0,
                    indexedEntryCount = 0
                )
            )
        )
        val incomplete = assertIs<OfflineSemanticClaimVectorDiscoveryResult.Unavailable>(
            discovery.discover("query", 8)
        )
        assertTrue(incomplete.reason.contains("incomplete"))
        assertEquals(0, embeddingCalls)

        assertTrue(
            store.writeManifest(
                SemanticClaimVectorProjectionManifest(
                    profileId = "wrong-profile",
                    profileGeneration = 1,
                    indexGeneration = 2,
                    source = SemanticClaimSourceCheckpoint(2, 0, 0),
                    state = SemanticClaimVectorProjectionState.COMPLETE,
                    shardEntryLimit = 128,
                    shardCount = 0,
                    indexedEntryCount = 0
                )
            )
        )
        val incompatible = assertIs<OfflineSemanticClaimVectorDiscoveryResult.Unavailable>(
            discovery.discover("query", 8)
        )
        assertTrue(incompatible.reason.contains("profile"))
        assertEquals(0, embeddingCalls)
    }

    @Test
    fun embedding_failure_is_explicit_and_never_queries_projection() {
        val storage = MemoryStorage()
        val store = SemanticClaimVectorProjectionStore(storage)
        assertTrue(
            store.writeManifest(
                SemanticClaimVectorProjectionManifest(
                    profileId = SemanticModelProfileV01.PROFILE_ID,
                    profileGeneration = 1,
                    indexGeneration = 3,
                    source = SemanticClaimSourceCheckpoint(3, 0, 0),
                    state = SemanticClaimVectorProjectionState.COMPLETE,
                    shardEntryLimit = 128,
                    shardCount = 0,
                    indexedEntryCount = 0
                )
            )
        )
        val discovery = OfflineSemanticClaimVectorProjectionDiscovery(
            embedding = SemanticClaimVectorQueryEmbeddingPort {
                OfflineSemanticSharedEmbeddingResult.OperationFailed
            },
            projectionStore = store,
            routingStore = SemanticClaimVectorRoutingStore(storage)
        )

        val result = assertIs<OfflineSemanticClaimVectorDiscoveryResult.Failed>(
            discovery.discover("query", 8)
        )
        assertTrue(result.reason.contains("embedding"))
    }

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

    private fun envelope(vector: SemanticEmbeddingVector): SemanticShardRoutingEnvelope {
        val values = vector.copyValues()
        return try {
            SemanticShardRoutingEnvelope.fromBounds(values, values)
        } finally {
            values.fill(0f)
        }
    }

    private class MemoryStorage : AndroidOfflineSemanticShardStorage {
        private val entries = LinkedHashMap<
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