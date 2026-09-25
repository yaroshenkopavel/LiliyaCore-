package pro.liliya.android.semanticprovider

import java.io.File
import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.memory.MemoryRecordSnapshot

/**
 * One host-owned authoritative snapshot used for a complete semantic rebuild.
 *
 * The semantic layer does not own Memory/Knowledge transactions. Production hosts must invoke this
 * source at a point where the returned snapshots are suitable as one startup/rebuild checkpoint.
 */
data class AndroidOfflineSemanticAuthoritativeSnapshot(
    val memory: List<MemoryRecordSnapshot>,
    val knowledge: List<KnowledgeItemSnapshot>
) {
    init {
        require(memory.size + knowledge.size <= MAX_TOTAL_ENTRIES) {
            "authoritative semantic snapshot exceeds v0.1 total entry bound"
        }
    }

    private companion object {
        const val MAX_TOTAL_ENTRIES = 20_000
    }
}

fun interface AndroidOfflineSemanticAuthoritativeSnapshotSource {
    fun snapshot(): AndroidOfflineSemanticAuthoritativeSnapshot
}

enum class AndroidOfflineSemanticStartupState {
    IDLE,
    STARTING,
    READY,
    FAILED,
    CLOSED
}

sealed interface AndroidOfflineSemanticStartupResult {
    data class Ready(val entryCount: Int) : AndroidOfflineSemanticStartupResult
    data object Busy : AndroidOfflineSemanticStartupResult
    data object ArtifactMissing : AndroidOfflineSemanticStartupResult
    data object ArtifactRejected : AndroidOfflineSemanticStartupResult
    data object ResourceRejected : AndroidOfflineSemanticStartupResult
    data object Unsupported : AndroidOfflineSemanticStartupResult
    data object ProviderFailed : AndroidOfflineSemanticStartupResult
    data object AuthoritativeSnapshotFailed : AndroidOfflineSemanticStartupResult
    data object RebuildFailed : AndroidOfflineSemanticStartupResult
}

sealed interface AndroidOfflineSemanticCheckpointPersistResult {
    data object Written : AndroidOfflineSemanticCheckpointPersistResult
    data object Skipped : AndroidOfflineSemanticCheckpointPersistResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : AndroidOfflineSemanticCheckpointPersistResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=" +
                (throwable?.javaClass?.name ?: "null") + ")"
    }
}

internal interface SemanticProductionRuntime {
    fun load(appPrivateRoot: File, encoderFile: File): AndroidOfflineSemanticProviderLoadResult
    fun restoreCheckpoint(
        checkpoint: SemanticIndexCheckpoint
    ): AndroidOfflineSemanticProviderRebuildResult
    fun rebuild(
        memory: List<MemoryRecordSnapshot>,
        knowledge: List<KnowledgeItemSnapshot>
    ): AndroidOfflineSemanticProviderRebuildResult
    fun checkpointSeeds(): List<SemanticIndexSeed>?
    fun close(): AndroidOfflineSemanticProviderCloseResult
}

internal class AssemblySemanticProductionRuntime(
    private val assembly: AndroidOfflineSemanticProviderAssembly
) : SemanticProductionRuntime {
    override fun load(
        appPrivateRoot: File,
        encoderFile: File
    ): AndroidOfflineSemanticProviderLoadResult =
        assembly.load(appPrivateRoot, encoderFile)

    override fun restoreCheckpoint(
        checkpoint: SemanticIndexCheckpoint
    ): AndroidOfflineSemanticProviderRebuildResult =
        assembly.restoreCheckpoint(checkpoint.seeds)

    override fun rebuild(
        memory: List<MemoryRecordSnapshot>,
        knowledge: List<KnowledgeItemSnapshot>
    ): AndroidOfflineSemanticProviderRebuildResult =
        assembly.rebuild(memory, knowledge)

    override fun checkpointSeeds(): List<SemanticIndexSeed>? =
        assembly.checkpointSeeds()

    override fun close(): AndroidOfflineSemanticProviderCloseResult =
        assembly.close()
}

/**
 * Explicit startup owner for local offline semantic readiness.
 *
 * Startup validates + loads the pinned ONNX artifacts first. If an exact model-bound,
 * authoritative-metadata-bound durable checkpoint is available, it restores the derived index
 * without enumerating Memory/Knowledge or re-embedding entries. Otherwise startup falls back to
 * one host-owned authoritative snapshot and a complete rebuild. A rebuilt checkpoint is published
 * only when authoritative metadata is unchanged across the rebuild.
 *
 * There is no hidden retry, remote fallback, truth authority or partial-ready state.
 */
class AndroidOfflineSemanticStartupCoordinator internal constructor(
    private val runtime: SemanticProductionRuntime,
    private val authoritativeSnapshots: AndroidOfflineSemanticAuthoritativeSnapshotSource,
    private val authoritativeMetadata: SemanticAuthoritativeMetadataSource? = null,
    private val checkpointStore: SemanticCheckpointStore? = null
) {
    @Volatile
    private var startupState: AndroidOfflineSemanticStartupState =
        AndroidOfflineSemanticStartupState.IDLE

    fun state(): AndroidOfflineSemanticStartupState = startupState

    @Synchronized
    fun start(
        appPrivateRoot: File,
        encoderFile: File
    ): AndroidOfflineSemanticStartupResult {
        if (startupState != AndroidOfflineSemanticStartupState.IDLE) {
            return AndroidOfflineSemanticStartupResult.Busy
        }
        startupState = AndroidOfflineSemanticStartupState.STARTING

        when (val loaded = runtime.load(appPrivateRoot, encoderFile)) {
            AndroidOfflineSemanticProviderLoadResult.Loaded -> Unit
            AndroidOfflineSemanticProviderLoadResult.Busy ->
                return fail(AndroidOfflineSemanticStartupResult.Busy)
            AndroidOfflineSemanticProviderLoadResult.ArtifactMissing ->
                return fail(AndroidOfflineSemanticStartupResult.ArtifactMissing)
            AndroidOfflineSemanticProviderLoadResult.ArtifactRejected ->
                return fail(AndroidOfflineSemanticStartupResult.ArtifactRejected)
            AndroidOfflineSemanticProviderLoadResult.ResourceRejected ->
                return fail(AndroidOfflineSemanticStartupResult.ResourceRejected)
            AndroidOfflineSemanticProviderLoadResult.Unsupported ->
                return fail(AndroidOfflineSemanticStartupResult.Unsupported)
            AndroidOfflineSemanticProviderLoadResult.ProviderFailed ->
                return fail(AndroidOfflineSemanticStartupResult.ProviderFailed)
        }

        val restoreMetadata = authoritativeMetadataSnapshot()
        if (restoreMetadata != null && checkpointStore != null) {
            val checkpoint = when (val read = safeReadCheckpoint(checkpointStore)) {
                is SemanticCheckpointReadResult.Loaded -> read.checkpoint
                SemanticCheckpointReadResult.Missing,
                SemanticCheckpointReadResult.Corrupt,
                is SemanticCheckpointReadResult.Incompatible,
                is SemanticCheckpointReadResult.Failed -> null
            }
            if (
                checkpoint != null &&
                checkpoint.matches(
                    expectedModel = SemanticCheckpointModelBinding.production(),
                    expectedAuthoritative = restoreMetadata
                )
            ) {
                when (val restored = runtime.restoreCheckpoint(checkpoint)) {
                    is AndroidOfflineSemanticProviderRebuildResult.Ready -> {
                        startupState = AndroidOfflineSemanticStartupState.READY
                        return AndroidOfflineSemanticStartupResult.Ready(restored.entryCount)
                    }
                    AndroidOfflineSemanticProviderRebuildResult.Busy,
                    AndroidOfflineSemanticProviderRebuildResult.NotLoaded,
                    AndroidOfflineSemanticProviderRebuildResult.Failed -> Unit
                }
            }
        }

        val rebuildMetadataBefore = authoritativeMetadataSnapshot()
        val snapshot = try {
            authoritativeSnapshots.snapshot()
        } catch (_: Exception) {
            runtime.close()
            return fail(AndroidOfflineSemanticStartupResult.AuthoritativeSnapshotFailed)
        }

        return when (val rebuilt = runtime.rebuild(snapshot.memory, snapshot.knowledge)) {
            is AndroidOfflineSemanticProviderRebuildResult.Ready -> {
                val checkpointMetadataAfter = authoritativeMetadataSnapshot()
                val store = checkpointStore
                if (
                    rebuildMetadataBefore != null &&
                    checkpointMetadataAfter == rebuildMetadataBefore &&
                    store != null
                ) {
                    val seeds = runtime.checkpointSeeds()
                    if (seeds != null) {
                        try {
                            safeWriteCheckpoint(
                                store,
                                SemanticIndexCheckpoint(
                                    version = SemanticIndexCheckpoint.CURRENT_VERSION,
                                    model = SemanticCheckpointModelBinding.production(),
                                    authoritative = rebuildMetadataBefore,
                                    seeds = seeds
                                )
                            )
                        } finally {
                            clearCheckpointSeeds(seeds)
                        }
                    }
                }
                startupState = AndroidOfflineSemanticStartupState.READY
                AndroidOfflineSemanticStartupResult.Ready(rebuilt.entryCount)
            }
            AndroidOfflineSemanticProviderRebuildResult.Busy,
            AndroidOfflineSemanticProviderRebuildResult.NotLoaded,
            AndroidOfflineSemanticProviderRebuildResult.Failed -> {
                startupState = AndroidOfflineSemanticStartupState.FAILED
                AndroidOfflineSemanticStartupResult.RebuildFailed
            }
        }
    }

    @Synchronized
    fun persistCheckpointIfCurrent(): AndroidOfflineSemanticCheckpointPersistResult {
        if (startupState != AndroidOfflineSemanticStartupState.READY) {
            return AndroidOfflineSemanticCheckpointPersistResult.Skipped
        }
        val store = checkpointStore
            ?: return AndroidOfflineSemanticCheckpointPersistResult.Skipped
        val before = authoritativeMetadataSnapshot()
            ?: return AndroidOfflineSemanticCheckpointPersistResult.Skipped
        val seeds = runtime.checkpointSeeds()
            ?: return AndroidOfflineSemanticCheckpointPersistResult.Skipped
        return try {
            val after = authoritativeMetadataSnapshot()
                ?: return AndroidOfflineSemanticCheckpointPersistResult.Skipped
            if (before != after) {
                return AndroidOfflineSemanticCheckpointPersistResult.Skipped
            }

            when (
                val written = safeWriteCheckpoint(
                    store,
                    SemanticIndexCheckpoint(
                        version = SemanticIndexCheckpoint.CURRENT_VERSION,
                        model = SemanticCheckpointModelBinding.production(),
                        authoritative = before,
                        seeds = seeds
                    )
                )
            ) {
                SemanticCheckpointWriteResult.Written ->
                    AndroidOfflineSemanticCheckpointPersistResult.Written
                is SemanticCheckpointWriteResult.Failed ->
                    AndroidOfflineSemanticCheckpointPersistResult.Failed(
                        written.reason,
                        written.throwable
                    )
            }
        } finally {
            clearCheckpointSeeds(seeds)
        }
    }

    @Synchronized
    fun close(): AndroidOfflineSemanticProviderCloseResult {
        val result = runtime.close()
        if (
            result == AndroidOfflineSemanticProviderCloseResult.Closed ||
            result == AndroidOfflineSemanticProviderCloseResult.AlreadyClosed
        ) {
            startupState = AndroidOfflineSemanticStartupState.CLOSED
        } else if (result == AndroidOfflineSemanticProviderCloseResult.ProviderFailed) {
            startupState = AndroidOfflineSemanticStartupState.FAILED
        }
        return result
    }

    private fun clearCheckpointSeeds(seeds: List<SemanticIndexSeed>) {
        seeds.forEach { it.vector.clear() }
    }

    private fun authoritativeMetadataSnapshot(): SemanticAuthoritativeMetadataCheckpoint? =
        try {
            authoritativeMetadata?.snapshot()
        } catch (_: Exception) {
            null
        }

    private fun safeReadCheckpoint(
        store: SemanticCheckpointStore
    ): SemanticCheckpointReadResult =
        try {
            store.read()
        } catch (failure: Exception) {
            SemanticCheckpointReadResult.Failed(
                reason = "semantic checkpoint read failed",
                throwable = failure
            )
        }

    private fun safeWriteCheckpoint(
        store: SemanticCheckpointStore,
        checkpoint: SemanticIndexCheckpoint
    ): SemanticCheckpointWriteResult =
        try {
            store.write(checkpoint)
        } catch (failure: Exception) {
            SemanticCheckpointWriteResult.Failed(
                reason = "semantic checkpoint write failed",
                throwable = failure
            )
        }

    private fun fail(
        result: AndroidOfflineSemanticStartupResult
    ): AndroidOfflineSemanticStartupResult {
        startupState = AndroidOfflineSemanticStartupState.FAILED
        return result
    }

    companion object {
        fun create(
            assembly: AndroidOfflineSemanticProviderAssembly,
            authoritativeSnapshots: AndroidOfflineSemanticAuthoritativeSnapshotSource
        ): AndroidOfflineSemanticStartupCoordinator =
            AndroidOfflineSemanticStartupCoordinator(
                runtime = AssemblySemanticProductionRuntime(assembly),
                authoritativeSnapshots = authoritativeSnapshots
            )

        fun create(
            assembly: AndroidOfflineSemanticProviderAssembly,
            authoritativeSnapshots: AndroidOfflineSemanticAuthoritativeSnapshotSource,
            authoritativeMetadata: AndroidOfflineSemanticAuthoritativeMetadataSource,
            checkpointStorage: AndroidOfflineSemanticCheckpointStorage
        ): AndroidOfflineSemanticStartupCoordinator =
            AndroidOfflineSemanticStartupCoordinator(
                runtime = AssemblySemanticProductionRuntime(assembly),
                authoritativeSnapshots = authoritativeSnapshots,
                authoritativeMetadata = SemanticAuthoritativeMetadataSource {
                    authoritativeMetadata.snapshot()?.toInternal()
                },
                checkpointStore = OpaqueSemanticCheckpointStore(checkpointStorage)
            )
    }
}
