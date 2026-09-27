package pro.liliya.android.semanticprovider

import java.time.Instant
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import pro.liliya.core.episodic.EpisodeId
import pro.liliya.core.persistence.PersistentBackendPageCursor
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.semantic.SemanticClaimExtractionProvenance
import pro.liliya.core.semantic.SemanticClaimId
import pro.liliya.core.semantic.SemanticClaimIdentity
import pro.liliya.core.semantic.SemanticClaimObject
import pro.liliya.core.semantic.SemanticClaimPageResult
import pro.liliya.core.semantic.SemanticClaimProvenance
import pro.liliya.core.semantic.SemanticClaimRecord
import pro.liliya.core.semantic.SemanticClaimSourceCheckpoint
import pro.liliya.core.semantic.SemanticClaimTemporalState
import pro.liliya.core.semantic.SemanticClaimVersion
import pro.liliya.core.semantic.SemanticEntityReference

class SemanticClaimVectorProjectionRebuilderContractTest {
    @Test
    fun rebuild_streams_129_claims_into_two_bounded_shards_and_publishes_complete_last() {
        val records = (0 until 129).map { claim(it) }
        val source = PagedSource(records)
        val storage = MemoryStorage()
        var embedCalls = 0
        val rebuilder = SemanticClaimVectorProjectionRebuilder(
            source = source,
            embedding = SemanticClaimVectorPassageEmbeddingPort {
                embedCalls += 1
                OfflineSemanticSharedEmbeddingResult.Embedded(vector(embedCalls % 8))
            },
            projectionStore = SemanticClaimVectorProjectionStore(storage),
            routingStore = SemanticClaimVectorRoutingStore(storage)
        )

        val result = assertIs<SemanticClaimVectorProjectionRebuildResult.Complete>(
            rebuilder.rebuild(101)
        )
        assertEquals(129, embedCalls)
        assertEquals(2L, result.shardCount)
        assertEquals(129L, result.indexedEntryCount)
        assertNotNull(result.routingRootSha256)
        assertEquals(listOf(128, 128), source.requestedLimits)

        val manifest = assertIs<SemanticClaimVectorProjectionManifestLoadResult.Loaded>(
            SemanticClaimVectorProjectionStore(storage).loadManifest()
        ).manifest
        assertEquals(SemanticClaimVectorProjectionState.COMPLETE, manifest.state)
        assertEquals(2L, manifest.shardCount)
        assertEquals(129L, manifest.indexedEntryCount)
        assertEquals(source.initialCheckpoint, manifest.source)
        assertEquals(2, storage.keys.count {
            it.value.startsWith("claim-vector-v1-shard-101-")
        })
        assertTrue(storage.keys.any {
            it.value.startsWith("claim-vector-v1-routing-node-")
        })
    }

    @Test
    fun source_drift_leaves_manifest_incomplete_and_never_publishes_complete_projection() {
        val source = PagedSource(listOf(claim(1))).apply {
            driftOnFinalCheckpoint = true
        }
        val storage = MemoryStorage()
        val rebuilder = SemanticClaimVectorProjectionRebuilder(
            source = source,
            embedding = SemanticClaimVectorPassageEmbeddingPort {
                OfflineSemanticSharedEmbeddingResult.Embedded(vector(0))
            },
            projectionStore = SemanticClaimVectorProjectionStore(storage),
            routingStore = SemanticClaimVectorRoutingStore(storage)
        )

        val result = assertIs<SemanticClaimVectorProjectionRebuildResult.SourceDrift>(
            rebuilder.rebuild(102)
        )
        assertEquals(source.initialCheckpoint, result.started)
        assertTrue(result.ended.revision > result.started.revision)

        val manifest = assertIs<SemanticClaimVectorProjectionManifestLoadResult.Loaded>(
            SemanticClaimVectorProjectionStore(storage).loadManifest()
        ).manifest
        assertEquals(SemanticClaimVectorProjectionState.INCOMPLETE, manifest.state)
        assertEquals(0L, manifest.indexedEntryCount)
        assertEquals(null, manifest.routingRootSha256)
    }

    @Test
    fun embedding_failure_leaves_incomplete_manifest_and_is_explicit() {
        val source = PagedSource(listOf(claim(2)))
        val storage = MemoryStorage()
        val rebuilder = SemanticClaimVectorProjectionRebuilder(
            source = source,
            embedding = SemanticClaimVectorPassageEmbeddingPort {
                OfflineSemanticSharedEmbeddingResult.OperationFailed
            },
            projectionStore = SemanticClaimVectorProjectionStore(storage),
            routingStore = SemanticClaimVectorRoutingStore(storage)
        )

        val result = assertIs<SemanticClaimVectorProjectionRebuildResult.Failed>(
            rebuilder.rebuild(103)
        )
        assertTrue(result.reason.contains("embedding"))
        val manifest = assertIs<SemanticClaimVectorProjectionManifestLoadResult.Loaded>(
            SemanticClaimVectorProjectionStore(storage).loadManifest()
        ).manifest
        assertEquals(SemanticClaimVectorProjectionState.INCOMPLETE, manifest.state)
        assertEquals(0L, manifest.indexedEntryCount)
    }

    private fun claim(index: Int): SemanticClaimRecord {
        val observed = Instant.parse("2026-09-27T00:00:00Z")
        return SemanticClaimRecord(
            id = SemanticClaimId("claim-rebuild-" + index),
            version = SemanticClaimVersion(1),
            identity = SemanticClaimIdentity(
                SemanticEntityReference("user", "self"),
                "vector-rebuild-" + index
            ),
            objectValue = SemanticClaimObject.Text("claim text " + index),
            temporal = SemanticClaimTemporalState(observedAt = observed),
            provenance = SemanticClaimProvenance(
                episodes = listOf(EpisodeId("episode-rebuild-" + index)),
                extraction = SemanticClaimExtractionProvenance(
                    extractorId = "test",
                    extractorVersion = "1",
                    extractedAt = observed
                )
            )
        )
    }

    private fun vector(index: Int): SemanticEmbeddingVector {
        val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
        values[index] = 1f
        return SemanticEmbeddingVector(values)
    }

    private class PagedSource(
        private val records: List<SemanticClaimRecord>
    ) : SemanticClaimVectorRebuildSource {
        val initialCheckpoint = SemanticClaimSourceCheckpoint(
            revision = 10,
            highWatermark = records.size.toLong(),
            entryCount = records.size.toLong()
        )
        var driftOnFinalCheckpoint: Boolean = false
        var checkpointCalls: Int = 0
        val requestedLimits = mutableListOf<Int>()

        override fun sourceCheckpoint(): SemanticClaimSourceCheckpoint {
            checkpointCalls += 1
            return if (driftOnFinalCheckpoint && checkpointCalls > 1) {
                initialCheckpoint.copy(revision = initialCheckpoint.revision + 1)
            } else {
                initialCheckpoint
            }
        }

        override fun claimPage(
            limit: Int,
            cursorExclusive: PersistentBackendPageCursor?
        ): SemanticClaimPageResult {
            requestedLimits += limit
            val start = if (cursorExclusive == null) 0 else limit
            if (start >= records.size) return SemanticClaimPageResult.Empty
            val end = minOf(start + limit, records.size)
            val next = if (end < records.size) {
                PersistentBackendPageCursor(
                    createdAt = Instant.parse("2026-09-27T00:00:00Z"),
                    entityId = PersistentEntityId("cursor-" + end)
                )
            } else {
                null
            }
            return SemanticClaimPageResult.Loaded(
                records = records.subList(start, end),
                nextCursor = next
            )
        }
    }

    private class MemoryStorage : AndroidOfflineSemanticShardStorage {
        private val entries = LinkedHashMap<
            AndroidOfflineSemanticShardStorageKey,
            AndroidOfflineSemanticCheckpointBlob
        >()
        val keys: Set<AndroidOfflineSemanticShardStorageKey> get() = entries.keys

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