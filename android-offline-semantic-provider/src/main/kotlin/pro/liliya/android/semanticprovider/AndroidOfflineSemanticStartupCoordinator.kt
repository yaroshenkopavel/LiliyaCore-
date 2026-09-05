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

internal interface SemanticProductionRuntime {
    fun load(appPrivateRoot: File, encoderFile: File): AndroidOfflineSemanticProviderLoadResult
    fun rebuild(
        memory: List<MemoryRecordSnapshot>,
        knowledge: List<KnowledgeItemSnapshot>
    ): AndroidOfflineSemanticProviderRebuildResult
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

    override fun rebuild(
        memory: List<MemoryRecordSnapshot>,
        knowledge: List<KnowledgeItemSnapshot>
    ): AndroidOfflineSemanticProviderRebuildResult =
        assembly.rebuild(memory, knowledge)

    override fun close(): AndroidOfflineSemanticProviderCloseResult =
        assembly.close()
}

/**
 * Explicit startup owner for local offline semantic readiness.
 *
 * Sequence is fixed:
 * 1. validate + load pinned ONNX artifacts;
 * 2. obtain one host-owned authoritative Memory/Knowledge snapshot;
 * 3. rebuild the complete derived semantic index;
 * 4. publish READY only after complete rebuild success.
 *
 * There is no hidden retry, remote fallback or partial-ready state.
 */
class AndroidOfflineSemanticStartupCoordinator internal constructor(
    private val runtime: SemanticProductionRuntime,
    private val authoritativeSnapshots: AndroidOfflineSemanticAuthoritativeSnapshotSource
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

        val snapshot = try {
            authoritativeSnapshots.snapshot()
        } catch (_: Exception) {
            runtime.close()
            return fail(AndroidOfflineSemanticStartupResult.AuthoritativeSnapshotFailed)
        }

        return when (val rebuilt = runtime.rebuild(snapshot.memory, snapshot.knowledge)) {
            is AndroidOfflineSemanticProviderRebuildResult.Ready -> {
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
    }
}
