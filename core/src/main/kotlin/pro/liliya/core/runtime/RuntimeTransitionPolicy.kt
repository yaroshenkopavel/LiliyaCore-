package pro.liliya.core.runtime

class RuntimeTransitionPolicy(
    rules: Set<RuntimeTransitionRule> = defaultRules
) {
    private val allowed = rules.toSet()

    fun allows(from: RuntimeState, to: RuntimeState): Boolean =
        RuntimeTransitionRule(from, to) in allowed

    companion object {
        val defaultRules: Set<RuntimeTransitionRule> = setOf(
            RuntimeTransitionRule(RuntimeState.CREATED, RuntimeState.STARTING),
            RuntimeTransitionRule(RuntimeState.STARTING, RuntimeState.RUNNING),
            RuntimeTransitionRule(RuntimeState.STARTING, RuntimeState.FAILED),
            RuntimeTransitionRule(RuntimeState.RUNNING, RuntimeState.STOPPING),
            RuntimeTransitionRule(RuntimeState.RUNNING, RuntimeState.FAILED),
            RuntimeTransitionRule(RuntimeState.STOPPING, RuntimeState.STOPPED),
            RuntimeTransitionRule(RuntimeState.STOPPING, RuntimeState.FAILED)
        )
    }
}
