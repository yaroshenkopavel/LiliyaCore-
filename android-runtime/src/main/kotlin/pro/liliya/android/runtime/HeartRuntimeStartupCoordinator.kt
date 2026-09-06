package pro.liliya.android.runtime

/**
 * Product-level cold-start state for the local Liliya heart.
 *
 * This owner sequences already-authoritative subsystem lifecycles. It does not replace their
 * storage, semantic-index, model-session, CognitiveTurn, License, Authority or Execution owners.
 */
enum class HeartRuntimeState {
    IDLE,
    STARTING_STORAGE,
    STARTING_SEMANTIC,
    STARTING_GENERATION,
    READY,
    CLOSING,
    CLOSED,
    FAILED
}

enum class HeartRuntimePhase {
    STORAGE,
    SEMANTIC,
    GENERATION
}

sealed interface HeartDependencyStartResult {
    data object Ready : HeartDependencyStartResult
    data object Failed : HeartDependencyStartResult
}

sealed interface HeartDependencyCloseResult {
    data object Closed : HeartDependencyCloseResult
    data object Failed : HeartDependencyCloseResult
}

fun interface HeartStorageStartupPort {
    fun start(): HeartDependencyStartResult
}

fun interface HeartStorageClosePort {
    fun close(): HeartDependencyCloseResult
}

fun interface HeartSemanticStartupPort {
    fun start(): HeartDependencyStartResult
}

fun interface HeartSemanticClosePort {
    fun close(): HeartDependencyCloseResult
}

fun interface HeartGenerationStartupPort {
    fun start(): HeartDependencyStartResult
}

fun interface HeartGenerationClosePort {
    fun close(): HeartDependencyCloseResult
}

sealed interface HeartRuntimeStartResult {
    data object Ready : HeartRuntimeStartResult
    data class Busy(val state: HeartRuntimeState) : HeartRuntimeStartResult
    data class Failed(val phase: HeartRuntimePhase) : HeartRuntimeStartResult
    data class CleanupFailed(
        val failedPhase: HeartRuntimePhase,
        val cleanupPhase: HeartRuntimePhase
    ) : HeartRuntimeStartResult
}

sealed interface HeartRuntimeCloseResult {
    data object Closed : HeartRuntimeCloseResult
    data object AlreadyClosed : HeartRuntimeCloseResult
    data class Failed(val phase: HeartRuntimePhase) : HeartRuntimeCloseResult
}

/**
 * Single process-local product readiness owner.
 *
 * Fixed startup order:
 * storage -> semantic -> generation -> READY.
 *
 * Startup failure compensates every attempted/active phase in reverse order. There is no retry,
 * fallback, replay or partial READY publication.
 */
class HeartRuntimeStartupCoordinator(
    private val storageStart: HeartStorageStartupPort,
    private val storageClose: HeartStorageClosePort,
    private val semanticStart: HeartSemanticStartupPort,
    private val semanticClose: HeartSemanticClosePort,
    private val generationStart: HeartGenerationStartupPort,
    private val generationClose: HeartGenerationClosePort
) {
    @Volatile
    private var currentState: HeartRuntimeState = HeartRuntimeState.IDLE

    private var storageActive = false
    private var semanticActive = false
    private var generationActive = false

    fun state(): HeartRuntimeState = currentState

    @Synchronized
    internal fun markFailedFromReady(): Boolean {
        if (currentState != HeartRuntimeState.READY) return false
        currentState = HeartRuntimeState.FAILED
        return true
    }

    @Synchronized
    internal fun markReadyAfterExplicitRecovery(): Boolean {
        if (currentState != HeartRuntimeState.FAILED) return false
        currentState = HeartRuntimeState.READY
        return true
    }

    @Synchronized
    fun start(): HeartRuntimeStartResult {
        if (currentState != HeartRuntimeState.IDLE) {
            return HeartRuntimeStartResult.Busy(currentState)
        }

        currentState = HeartRuntimeState.STARTING_STORAGE
        if (storageStart.start() != HeartDependencyStartResult.Ready) {
            storageActive = true
            return startupFailure(HeartRuntimePhase.STORAGE)
        }
        storageActive = true

        currentState = HeartRuntimeState.STARTING_SEMANTIC
        if (semanticStart.start() != HeartDependencyStartResult.Ready) {
            semanticActive = true
            return startupFailure(HeartRuntimePhase.SEMANTIC)
        }
        semanticActive = true

        currentState = HeartRuntimeState.STARTING_GENERATION
        if (generationStart.start() != HeartDependencyStartResult.Ready) {
            generationActive = true
            return startupFailure(HeartRuntimePhase.GENERATION)
        }
        generationActive = true

        currentState = HeartRuntimeState.READY
        return HeartRuntimeStartResult.Ready
    }

    @Synchronized
    fun close(): HeartRuntimeCloseResult {
        if (currentState == HeartRuntimeState.CLOSED) {
            return HeartRuntimeCloseResult.AlreadyClosed
        }
        currentState = HeartRuntimeState.CLOSING

        val generationFailure = closeGeneration()
        val semanticFailure = closeSemantic()
        val storageFailure = closeStorage()

        val failedPhase = generationFailure ?: semanticFailure ?: storageFailure
        return if (failedPhase == null) {
            currentState = HeartRuntimeState.CLOSED
            HeartRuntimeCloseResult.Closed
        } else {
            currentState = HeartRuntimeState.FAILED
            HeartRuntimeCloseResult.Failed(failedPhase)
        }
    }

    private fun startupFailure(failedPhase: HeartRuntimePhase): HeartRuntimeStartResult {
        val generationFailure = closeGeneration()
        val semanticFailure = closeSemantic()
        val storageFailure = closeStorage()
        val cleanupFailure = generationFailure ?: semanticFailure ?: storageFailure
        currentState = HeartRuntimeState.FAILED
        return if (cleanupFailure == null) {
            HeartRuntimeStartResult.Failed(failedPhase)
        } else {
            HeartRuntimeStartResult.CleanupFailed(
                failedPhase = failedPhase,
                cleanupPhase = cleanupFailure
            )
        }
    }

    private fun closeGeneration(): HeartRuntimePhase? {
        if (!generationActive) return null
        return if (generationClose.close() == HeartDependencyCloseResult.Closed) {
            generationActive = false
            null
        } else {
            HeartRuntimePhase.GENERATION
        }
    }

    private fun closeSemantic(): HeartRuntimePhase? {
        if (!semanticActive) return null
        return if (semanticClose.close() == HeartDependencyCloseResult.Closed) {
            semanticActive = false
            null
        } else {
            HeartRuntimePhase.SEMANTIC
        }
    }

    private fun closeStorage(): HeartRuntimePhase? {
        if (!storageActive) return null
        return if (storageClose.close() == HeartDependencyCloseResult.Closed) {
            storageActive = false
            null
        } else {
            HeartRuntimePhase.STORAGE
        }
    }
}
