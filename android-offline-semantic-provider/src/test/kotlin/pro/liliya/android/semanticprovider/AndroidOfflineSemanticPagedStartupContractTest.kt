package pro.liliya.android.semanticprovider

import java.io.File
import java.time.Instant
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemoryRecordSnapshot
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.persistence.PersistentBackendMetadata

class AndroidOfflineSemanticPagedStartupContractTest {
    @Test
    fun twenty_thousand_plus_authoritative_entries_start_ready_without_legacy_snapshot() {
        val totalEntries = 20_001L
        val metadata = SemanticAuthoritativeMetadataCheckpoint(
            memory = PersistentBackendMetadata(
                revision = totalEntries + 1L,
                highWatermark = totalEntries,
                entryCount = totalEntries
            ),
            knowledge = PersistentBackendMetadata(
                revision = 1L,
                highWatermark = 0L,
                entryCount = 0L
            )
        )
        val storage = InMemoryShardStorage()
        val runtime = PagedRuntime()
        var legacySnapshotCalls = 0

        val coordinator = AndroidOfflineSemanticStartupCoordinator(
            runtime = runtime,
            authoritativeSnapshots = AndroidOfflineSemanticAuthoritativeSnapshotSource {
                legacySnapshotCalls += 1
                error("legacy authoritative snapshot must not run for shard startup")
            },
            authoritativeMetadata = SemanticAuthoritativeMetadataSource { metadata },
            authoritativePages = AndroidOfflineSemanticAuthoritativePageSource {
                MemoryOnlyPageReader(totalEntries)
            },
            shardStorage = storage
        )

        assertEquals(
            AndroidOfflineSemanticStartupResult.Ready(totalEntries.toInt()),
            coordinator.start(File("/private"), File("/private/model.onnx"))
        )
        assertEquals(0, legacySnapshotCalls)
        assertEquals(0, runtime.legacyRebuildCalls)
        assertEquals(AndroidOfflineSemanticStartupState.READY, coordinator.state())

        val manifestBlob = assertIs<AndroidOfflineSemanticShardStorageReadResult.Loaded>(
            storage.read(AndroidOfflineSemanticShardStorageKey.MANIFEST)
        ).blob
        val manifest = assertIs<SemanticShardManifestDecodeResult.Decoded>(
            SemanticShardCheckpointCodec.decodeManifest(manifestBlob)
        ).manifest
        assertEquals(10, manifest.shards.size)
        assertEquals(totalEntries, manifest.shards.sumOf { it.entryCount.toLong() })
        assertEquals(metadata, manifest.authoritative)
    }

    private class PagedRuntime : SemanticProductionRuntime {
        var legacyRebuildCalls: Int = 0
            private set

        override fun load(
            appPrivateRoot: File,
            encoderFile: File
        ): AndroidOfflineSemanticProviderLoadResult =
            AndroidOfflineSemanticProviderLoadResult.Loaded

        override fun restoreCheckpoint(
            checkpoint: SemanticIndexCheckpoint
        ): AndroidOfflineSemanticProviderRebuildResult =
            AndroidOfflineSemanticProviderRebuildResult.Failed

        override fun activateShardManifest(
            store: SemanticShardStore,
            manifest: SemanticShardManifest
        ): AndroidOfflineSemanticProviderRebuildResult =
            AndroidOfflineSemanticProviderRebuildResult.Ready(
                manifest.shards.sumOf { it.entryCount }
            )

        override fun embedShardPage(
            observations: List<SemanticSourceObservation>
        ): OfflineSemanticShardEmbedResult =
            OfflineSemanticShardEmbedResult.Embedded(
                observations.map { observation ->
                    val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
                    values[0] = 1f
                    SemanticIndexSeed(
                        source = observation.source,
                        vector = SemanticEmbeddingVector(values)
                    )
                }
            )

        override fun persistShardManifest(
            authoritative: SemanticAuthoritativeMetadataCheckpoint
        ): Boolean? = true

        override fun rebuild(
            memory: List<MemoryRecordSnapshot>,
            knowledge: List<pro.liliya.core.knowledge.KnowledgeItemSnapshot>
        ): AndroidOfflineSemanticProviderRebuildResult {
            legacyRebuildCalls += 1
            return AndroidOfflineSemanticProviderRebuildResult.Failed
        }

        override fun checkpointSeeds(): List<SemanticIndexSeed>? = null

        override fun close(): AndroidOfflineSemanticProviderCloseResult =
            AndroidOfflineSemanticProviderCloseResult.Closed
    }

    private class MemoryOnlyPageReader(
        private val totalEntries: Long
    ) : AndroidOfflineSemanticAuthoritativePageReader {
        private var nextGeneration: Long = 1L

        override fun nextMemoryPage(): AndroidOfflineSemanticMemoryPageResult {
            if (nextGeneration > totalEntries) {
                return AndroidOfflineSemanticMemoryPageResult.End
            }
            val end = minOf(
                totalEntries,
                nextGeneration + MAX_PAGE_ENTRIES.toLong() - 1L
            )
            val page = ArrayList<MemoryRecordSnapshot>((end - nextGeneration + 1L).toInt())
            var generation = nextGeneration
            while (generation <= end) {
                page += MemoryRecordSnapshot(
                    record = MemoryRecord(
                        id = MemoryRecordId("memory-" + generation),
                        provenance = MemoryProvenance(MemorySourceId("paged-startup")),
                        content = "memory " + generation,
                        createdAt = BASE
                    ),
                    generation = MemoryGeneration(generation)
                )
                generation += 1L
            }
            nextGeneration = end + 1L
            return AndroidOfflineSemanticMemoryPageResult.Loaded(page)
        }

        override fun nextKnowledgePage(): AndroidOfflineSemanticKnowledgePageResult =
            AndroidOfflineSemanticKnowledgePageResult.End
    }

    private class InMemoryShardStorage : AndroidOfflineSemanticShardStorage {
        private val blobs = LinkedHashMap<String, AndroidOfflineSemanticCheckpointBlob>()

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
            return AndroidOfflineSemanticShardStorageWriteResult.Written
        }
    }

    private companion object {
        val BASE: Instant = Instant.parse("2026-09-25T00:00:00Z")
    }
}
