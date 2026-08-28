package pro.liliya.core.runtime

data class RuntimeTransitionRule(
    val from: RuntimeState,
    val to: RuntimeState
)
