package pro.liliya.core.lifecycle

import pro.liliya.core.runtime.RuntimeState

sealed interface LifecycleResult {
    data class Applied(
        val phase: LifecyclePhase,
        val resultingState: RuntimeState
    ) : LifecycleResult

    data class Rejected(
        val phase: LifecyclePhase,
        val currentState: RuntimeState,
        val reason: String
    ) : LifecycleResult
}
