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

    private class FakeRuntime(
        private val events: MutableList<String>
    ) : SemanticProductionRuntime {
        var loadResult: AndroidOfflineSemanticProviderLoadResult =
            AndroidOfflineSemanticProviderLoadResult.Loaded
        var rebuildResult: AndroidOfflineSemanticProviderRebuildResult =
            AndroidOfflineSemanticProviderRebuildResult.Ready(2)
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

        override fun rebuild(
            memory: List<MemoryRecordSnapshot>,
            knowledge: List<KnowledgeItemSnapshot>
        ): AndroidOfflineSemanticProviderRebuildResult {
            events += "rebuild"
            rebuiltMemory = memory
            rebuiltKnowledge = knowledge
            return rebuildResult
        }

        override fun close(): AndroidOfflineSemanticProviderCloseResult {
            events += "close"
            return closeResult
        }
    }

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
