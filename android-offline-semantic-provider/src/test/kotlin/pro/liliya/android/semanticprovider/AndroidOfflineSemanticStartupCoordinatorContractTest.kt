package pro.liliya.android.semanticprovider

import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.Test
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemoryRecordSnapshot
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.persistence.PersistentBackendMetadata

class AndroidOfflineSemanticStartupCoordinatorContractTest {

    @Test
    fun startup_orders_load_then_authoritative_snapshot_then_full_rebuild_before_ready() {
        val events = mutableListOf<String>()
        val runtime = FakeRuntime(events)
        val snapshot = snapshot()
        val coordinator = AndroidOfflineSemanticStartupCoordinator(
            runtime = runtime,
            authoritativeSnapshots = AndroidOfflineSemanticAuthoritativeSnapshotSource {
                events += "snapshot"
                snapshot
            }
        )

        assertEquals(
            AndroidOfflineSemanticStartupResult.Ready(2),
            coordinator.start(File("/private"), File("/private/model.onnx"))
        )
        assertEquals(
            listOf("load", "snapshot", "rebuild"),
            events
        )
        assertEquals(snapshot.memory, runtime.rebuiltMemory)
        assertEquals(snapshot.knowledge, runtime.rebuiltKnowledge)
        assertEquals(AndroidOfflineSemanticStartupState.READY, coordinator.state())
    }

    @Test
    fun artifact_failure_prevents_authoritative_snapshot_and_rebuild() {
        val events = mutableListOf<String>()
        val runtime = FakeRuntime(events).apply {
            loadResult = AndroidOfflineSemanticProviderLoadResult.ArtifactRejected
        }
        val coordinator = AndroidOfflineSemanticStartupCoordinator(
            runtime = runtime,
            authoritativeSnapshots = AndroidOfflineSemanticAuthoritativeSnapshotSource {
                events += "snapshot"
                snapshot()
            }
        )

        assertEquals(
            AndroidOfflineSemanticStartupResult.ArtifactRejected,
            coordinator.start(File("/private"), File("/private/model.onnx"))
        )
        assertEquals(listOf("load"), events)
        assertEquals(AndroidOfflineSemanticStartupState.FAILED, coordinator.state())
    }

    @Test
    fun authoritative_snapshot_failure_closes_loaded_runtime_and_fails_closed() {
        val events = mutableListOf<String>()
        val runtime = FakeRuntime(events)
        val coordinator = AndroidOfflineSemanticStartupCoordinator(
            runtime = runtime,
            authoritativeSnapshots = AndroidOfflineSemanticAuthoritativeSnapshotSource {
                events += "snapshot"
                error("private backend failure")
            }
        )

        assertEquals(
            AndroidOfflineSemanticStartupResult.AuthoritativeSnapshotFailed,
            coordinator.start(File("/private"), File("/private/model.onnx"))
        )
        assertEquals(listOf("load", "snapshot", "close"), events)
        assertEquals(AndroidOfflineSemanticStartupState.FAILED, coordinator.state())
    }

    @Test
    fun rebuild_failure_never_publishes_startup_ready() {
        val events = mutableListOf<String>()
        val runtime = FakeRuntime(events).apply {
            rebuildResult = AndroidOfflineSemanticProviderRebuildResult.Failed
        }
        val coordinator = AndroidOfflineSemanticStartupCoordinator(
            runtime = runtime,
            authoritativeSnapshots = AndroidOfflineSemanticAuthoritativeSnapshotSource {
                events += "snapshot"
                snapshot()
            }
        )

        assertEquals(
            AndroidOfflineSemanticStartupResult.RebuildFailed,
            coordinator.start(File("/private"), File("/private/model.onnx"))
        )
        assertEquals(listOf("load", "snapshot", "rebuild"), events)
        assertEquals(AndroidOfflineSemanticStartupState.FAILED, coordinator.state())
    }

    @Test
    fun startup_is_single_attempt_and_does_not_hide_retry() {
        val events = mutableListOf<String>()
        val runtime = FakeRuntime(events)
        val coordinator = AndroidOfflineSemanticStartupCoordinator(
            runtime = runtime,
            authoritativeSnapshots = AndroidOfflineSemanticAuthoritativeSnapshotSource {
                events += "snapshot"
                snapshot()
            }
        )

        assertEquals(
            AndroidOfflineSemanticStartupResult.Ready(2),
            coordinator.start(File("/private"), File("/private/model.onnx"))
        )
        assertEquals(
            AndroidOfflineSemanticStartupResult.Busy,
            coordinator.start(File("/private"), File("/private/model.onnx"))
        )
        assertEquals(listOf("load", "snapshot", "rebuild"), events)
    }

    @Test
    fun valid_checkpoint_restores_ready_without_authoritative_snapshot_or_embedding_rebuild() {
        val events = mutableListOf<String>()
        val runtime = FakeRuntime(events)
        val metadata = authoritativeMetadata()
        val checkpoint = SemanticIndexCheckpoint(
            version = SemanticIndexCheckpoint.CURRENT_VERSION,
            model = SemanticCheckpointModelBinding.production(),
            authoritative = metadata,
            seeds = listOf(checkpointSeed())
        )
        val store = FakeCheckpointStore(
            events = events,
            readResult = SemanticCheckpointReadResult.Loaded(checkpoint)
        )
        val coordinator = AndroidOfflineSemanticStartupCoordinator(
            runtime = runtime,
            authoritativeSnapshots = AndroidOfflineSemanticAuthoritativeSnapshotSource {
                events += "snapshot"
                snapshot()
            },
            authoritativeMetadata = SemanticAuthoritativeMetadataSource {
                events += "metadata"
                metadata
            },
            checkpointStore = store
        )

        assertEquals(
            AndroidOfflineSemanticStartupResult.Ready(1),
            coordinator.start(File("/private"), File("/private/model.onnx"))
        )
        assertEquals(
            listOf("load", "metadata", "checkpoint-read", "restore"),
            events
        )
        assertEquals(AndroidOfflineSemanticStartupState.READY, coordinator.state())
    }

    @Test
    fun stale_checkpoint_falls_back_to_rebuild_and_writes_fresh_checkpoint() {
        val events = mutableListOf<String>()
        val runtime = FakeRuntime(events).apply {
            checkpointSeedsResult = listOf(checkpointSeed())
        }
        val currentMetadata = authoritativeMetadata()
        val staleMetadata = currentMetadata.copy(
            memory = currentMetadata.memory.copy(revision = currentMetadata.memory.revision - 1L)
        )
        val store = FakeCheckpointStore(
            events = events,
            readResult = SemanticCheckpointReadResult.Loaded(
                SemanticIndexCheckpoint(
                    version = SemanticIndexCheckpoint.CURRENT_VERSION,
                    model = SemanticCheckpointModelBinding.production(),
                    authoritative = staleMetadata,
                    seeds = listOf(checkpointSeed())
                )
            )
        )
        val coordinator = AndroidOfflineSemanticStartupCoordinator(
            runtime = runtime,
            authoritativeSnapshots = AndroidOfflineSemanticAuthoritativeSnapshotSource {
                events += "snapshot"
                snapshot()
            },
            authoritativeMetadata = SemanticAuthoritativeMetadataSource {
                events += "metadata"
                currentMetadata
            },
            checkpointStore = store
        )

        assertEquals(
            AndroidOfflineSemanticStartupResult.Ready(2),
            coordinator.start(File("/private"), File("/private/model.onnx"))
        )
        assertEquals(
            listOf(
                "load",
                "metadata",
                "checkpoint-read",
                "metadata",
                "snapshot",
                "rebuild",
                "metadata",
                "checkpoint-seeds",
                "checkpoint-write"
            ),
            events
        )
        assertEquals(currentMetadata, store.written?.authoritative)
        assertEquals(SemanticCheckpointModelBinding.production(), store.written?.model)
        assertEquals(AndroidOfflineSemanticStartupState.READY, coordinator.state())
    }

    @Test
    fun ready_runtime_persists_current_checkpoint_after_post_commit_semantic_sync() {
        val events = mutableListOf<String>()
        val runtime = FakeRuntime(events)
        val metadata = authoritativeMetadata()
        val store = FakeCheckpointStore(
            events = events,
            readResult = SemanticCheckpointReadResult.Missing
        )
        val coordinator = AndroidOfflineSemanticStartupCoordinator(
            runtime = runtime,
            authoritativeSnapshots = AndroidOfflineSemanticAuthoritativeSnapshotSource {
                events += "snapshot"
                snapshot()
            },
            authoritativeMetadata = SemanticAuthoritativeMetadataSource {
                events += "metadata"
                metadata
            },
            checkpointStore = store
        )

        assertEquals(
            AndroidOfflineSemanticStartupResult.Ready(2),
            coordinator.start(File("/private"), File("/private/model.onnx"))
        )
        runtime.checkpointSeedsResult = listOf(checkpointSeed())
        store.written = null
        events.clear()

        assertEquals(
            AndroidOfflineSemanticCheckpointPersistResult.Written,
            coordinator.persistCheckpointIfCurrent()
        )
        assertEquals(
            listOf("metadata", "checkpoint-seeds", "metadata", "checkpoint-write"),
            events
        )
        assertEquals(metadata, store.written?.authoritative)
    }

    @Test
    fun ready_shard_runtime_persists_manifest_against_current_authoritative_metadata() {
        val events = mutableListOf<String>()
        val runtime = FakeRuntime(events)
        val metadata = authoritativeMetadata()
        val coordinator = AndroidOfflineSemanticStartupCoordinator(
            runtime = runtime,
            authoritativeSnapshots = AndroidOfflineSemanticAuthoritativeSnapshotSource {
                events += "snapshot"
                snapshot()
            },
            authoritativeMetadata = SemanticAuthoritativeMetadataSource {
                events += "metadata"
                metadata
            }
        )

        assertEquals(
            AndroidOfflineSemanticStartupResult.Ready(2),
            coordinator.start(File("/private"), File("/private/model.onnx"))
        )
        runtime.shardPersistResult = true
        events.clear()

        assertEquals(
            AndroidOfflineSemanticCheckpointPersistResult.Written,
            coordinator.persistCheckpointIfCurrent()
        )
        assertEquals(
            listOf("metadata", "persist-shard-manifest", "metadata"),
            events
        )
    }

    @Test
    fun metadata_change_during_rebuild_never_publishes_a_durable_checkpoint() {
        val events = mutableListOf<String>()
        val runtime = FakeRuntime(events).apply {
            checkpointSeedsResult = listOf(checkpointSeed())
        }
        val stable = authoritativeMetadata()
        val changed = stable.copy(
            memory = stable.memory.copy(
                revision = stable.memory.revision + 1L,
                highWatermark = stable.memory.highWatermark + 1L,
                entryCount = stable.memory.entryCount + 1L
            )
        )
        var metadataCalls = 0
        val store = FakeCheckpointStore(
            events = events,
            readResult = SemanticCheckpointReadResult.Missing
        )
        val coordinator = AndroidOfflineSemanticStartupCoordinator(
            runtime = runtime,
            authoritativeSnapshots = AndroidOfflineSemanticAuthoritativeSnapshotSource {
                events += "snapshot"
                snapshot()
            },
            authoritativeMetadata = SemanticAuthoritativeMetadataSource {
                events += "metadata"
                metadataCalls += 1
                if (metadataCalls < 3) stable else changed
            },
            checkpointStore = store
        )

        assertEquals(
            AndroidOfflineSemanticStartupResult.Ready(2),
            coordinator.start(File("/private"), File("/private/model.onnx"))
        )
        assertEquals(null, store.written)
        assertEquals(
            listOf(
                "load",
                "metadata",
                "checkpoint-read",
                "metadata",
                "snapshot",
                "rebuild",
                "metadata"
            ),
            events
        )
    }

    @Test
    fun paged_shard_rebuild_starts_above_twenty_thousand_without_legacy_snapshot() {
        val totalMemory = 20_001
        val events = mutableListOf<String>()
        val runtime = FakeRuntime(events).apply {
            embedShardPages = true
        }
        val metadata = SemanticAuthoritativeMetadataCheckpoint(
            memory = PersistentBackendMetadata(
                revision = totalMemory.toLong() + 1L,
                highWatermark = totalMemory.toLong(),
                entryCount = totalMemory.toLong()
            ),
            knowledge = PersistentBackendMetadata(
                revision = 1L,
                highWatermark = 0L,
                entryCount = 0L
            )
        )
        val shardStorage = InMemoryShardStorage()
        val pageSource = AndroidOfflineSemanticAuthoritativePageSource {
            GeneratedMemoryPageReader(totalMemory)
        }
        val coordinator = AndroidOfflineSemanticStartupCoordinator(
            runtime = runtime,
            authoritativeSnapshots = AndroidOfflineSemanticAuthoritativeSnapshotSource {
                error("legacy authoritative snapshot must not be called")
            },
            authoritativeMetadata = SemanticAuthoritativeMetadataSource { metadata },
            authoritativePages = pageSource,
            shardStorage = shardStorage
        )

        assertEquals(
            AndroidOfflineSemanticStartupResult.Ready(totalMemory),
            coordinator.start(File("/private"), File("/private/model.onnx"))
        )
        assertEquals(AndroidOfflineSemanticStartupState.READY, coordinator.state())
        assertEquals(false, events.contains("rebuild"))
        assertEquals(false, events.contains("restore"))
        assertEquals(true, events.contains("activate-shards"))
        assertEquals(10, shardStorage.shardBlobCount())
    }

    private class InMemoryShardStorage : AndroidOfflineSemanticShardStorage {
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

        fun shardBlobCount(): Int =
            blobs.keys.count { it != AndroidOfflineSemanticShardStorageKey.MANIFEST.value }
    }

    private class GeneratedMemoryPageReader(
        private val total: Int
    ) : AndroidOfflineSemanticAuthoritativePageReader {
        private var nextGeneration: Int = 1
        private var knowledgeEnded: Boolean = false

        override fun nextMemoryPage(): AndroidOfflineSemanticMemoryPageResult {
            if (nextGeneration > total) return AndroidOfflineSemanticMemoryPageResult.End
            val last = minOf(total, nextGeneration + MAX_PAGE_ENTRIES - 1)
            val entries = ArrayList<MemoryRecordSnapshot>(last - nextGeneration + 1)
            while (nextGeneration <= last) {
                val generation = nextGeneration.toLong()
                entries += MemoryRecordSnapshot(
                    record = MemoryRecord(
                        id = MemoryRecordId("paged-memory-" + generation),
                        provenance = MemoryProvenance(MemorySourceId("paged-startup")),
                        content = "memory " + generation,
                        createdAt = BASE.plusSeconds(generation)
                    ),
                    generation = MemoryGeneration(generation)
                )
                nextGeneration += 1
            }
            return AndroidOfflineSemanticMemoryPageResult.Loaded(entries)
        }

        override fun nextKnowledgePage(): AndroidOfflineSemanticKnowledgePageResult {
            if (knowledgeEnded) return AndroidOfflineSemanticKnowledgePageResult.End
            knowledgeEnded = true
            return AndroidOfflineSemanticKnowledgePageResult.End
        }
    }

    private class FakeCheckpointStore(
        private val events: MutableList<String>,
        private val readResult: SemanticCheckpointReadResult
    ) : SemanticCheckpointStore {
        var written: SemanticIndexCheckpoint? = null

        override fun read(): SemanticCheckpointReadResult {
            events += "checkpoint-read"
            return readResult
        }

        override fun write(
            checkpoint: SemanticIndexCheckpoint
        ): SemanticCheckpointWriteResult {
            events += "checkpoint-write"
            written = checkpoint
            return SemanticCheckpointWriteResult.Written
        }
    }

    private class FakeRuntime(
        private val events: MutableList<String>
    ) : SemanticProductionRuntime {
        var loadResult: AndroidOfflineSemanticProviderLoadResult =
            AndroidOfflineSemanticProviderLoadResult.Loaded
        var restoreResult: AndroidOfflineSemanticProviderRebuildResult =
            AndroidOfflineSemanticProviderRebuildResult.Ready(1)
        var rebuildResult: AndroidOfflineSemanticProviderRebuildResult =
            AndroidOfflineSemanticProviderRebuildResult.Ready(2)
        var checkpointSeedsResult: List<SemanticIndexSeed>? = null
        var embedShardPages: Boolean = false
        var shardPersistResult: Boolean? = null
        var closeResult: AndroidOfflineSemanticProviderCloseResult =
            AndroidOfflineSemanticProviderCloseResult.Closed
        var rebuiltMemory: List<MemoryRecordSnapshot>? = null
        var rebuiltKnowledge: List<KnowledgeItemSnapshot>? = null

        override fun load(
            appPrivateRoot: File,
            encoderFile: File
        ): AndroidOfflineSemanticProviderLoadResult {
            events += "load"
            return loadResult
        }

        override fun restoreCheckpoint(
            checkpoint: SemanticIndexCheckpoint
        ): AndroidOfflineSemanticProviderRebuildResult {
            events += "restore"
            return restoreResult
        }

        override fun activateShardManifest(
            store: SemanticShardStore,
            manifest: SemanticShardManifest
        ): AndroidOfflineSemanticProviderRebuildResult {
            events += "activate-shards"
            return AndroidOfflineSemanticProviderRebuildResult.Ready(
                manifest.shards.sumOf { it.entryCount }
            )
        }

        override fun embedShardPage(
            observations: List<SemanticSourceObservation>
        ): OfflineSemanticShardEmbedResult {
            events += "embed-shard-page"
            if (!embedShardPages) {
                return OfflineSemanticShardEmbedResult.Embedded(emptyList())
            }
            return OfflineSemanticShardEmbedResult.Embedded(
                observations.map { observation ->
                    SemanticIndexSeed(
                        source = observation.source,
                        vector = SemanticEmbeddingVector(
                            FloatArray(SemanticEmbeddingVector.DIMENSION).also {
                                it[0] = 1.0f
                            }
                        )
                    )
                }
            )
        }

        override fun persistShardManifest(
            authoritative: SemanticAuthoritativeMetadataCheckpoint
        ): Boolean? {
            val result = shardPersistResult
            if (result != null) events += "persist-shard-manifest"
            return result
        }

        override fun rebuild(
            memory: List<MemoryRecordSnapshot>,
            knowledge: List<KnowledgeItemSnapshot>
        ): AndroidOfflineSemanticProviderRebuildResult {
            events += "rebuild"
            rebuiltMemory = memory
            rebuiltKnowledge = knowledge
            return rebuildResult
        }

        override fun checkpointSeeds(): List<SemanticIndexSeed>? {
            events += "checkpoint-seeds"
            return checkpointSeedsResult
        }

        override fun close(): AndroidOfflineSemanticProviderCloseResult {
            events += "close"
            return closeResult
        }
    }

    private fun authoritativeMetadata(): SemanticAuthoritativeMetadataCheckpoint =
        SemanticAuthoritativeMetadataCheckpoint(
            memory = PersistentBackendMetadata(
                revision = 7L,
                highWatermark = 11L,
                entryCount = 1L
            ),
            knowledge = PersistentBackendMetadata(
                revision = 5L,
                highWatermark = 9L,
                entryCount = 1L
            )
        )

    private fun checkpointSeed(): SemanticIndexSeed =
        SemanticIndexSeed(
            source = SemanticIndexSourceReference.Memory(
                id = MemoryRecordId("checkpoint-memory"),
                generation = MemoryGeneration(11L)
            ),
            vector = SemanticEmbeddingVector(
                FloatArray(SemanticEmbeddingVector.DIMENSION).also { it[0] = 1.0f }
            )
        )

    private fun snapshot(): AndroidOfflineSemanticAuthoritativeSnapshot =
        AndroidOfflineSemanticAuthoritativeSnapshot(
            memory = listOf(
                MemoryRecordSnapshot(
                    record = MemoryRecord(
                        id = MemoryRecordId("memory-startup"),
                        provenance = MemoryProvenance(MemorySourceId("startup-test")),
                        content = "memory content",
                        createdAt = BASE
                    ),
                    generation = MemoryGeneration(1)
                )
            ),
            knowledge = listOf(
                KnowledgeItemSnapshot(
                    item = KnowledgeItem(
                        id = KnowledgeItemId("knowledge-startup"),
                        origin = KnowledgeOrigin.Declared(KnowledgeSourceId("startup-test")),
                        content = "knowledge content",
                        createdAt = BASE.plusSeconds(1)
                    ),
                    generation = KnowledgeGeneration(2)
                )
            )
        )

    private companion object {
        val BASE: Instant = Instant.parse("2026-09-05T13:00:00Z")
    }
}
