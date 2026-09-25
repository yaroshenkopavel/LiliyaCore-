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
            return OfflineSemanticShardEmbedResult.Embedded(emptyList())
        }

        override fun persistShardManifest(
            authoritative: SemanticAuthoritativeMetadataCheckpoint
        ): Boolean? {
            events += "persist-shard-manifest"
            return true
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
