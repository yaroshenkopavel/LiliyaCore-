package pro.liliya.core.runtime

sealed interface RuntimeTransitionResult {
    data class Applied(
        val transition: RuntimeTransition
    ) : RuntimeTransitionResult

    data class Rejected(
        val from: RuntimeState,
        val to: RuntimeState,
        val reason: String
    ) : RuntimeTransitionResult
}
