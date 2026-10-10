package pro.liliya.core.asf

/**
 * Pure fail-closed checkpoint for a sequence of dependency waves.
 *
 * This state is advisory only: successful task outcomes are not an authorization,
 * admission or durable artifact commit. The owner of a future runtime integration
 * must separately validate all those conditions before dispatching another wave.
 */
data class AgentParallelWaveProgress(
    val completedStepIds: Set<AgentCoordinatorStepId> = emptySet(),
    val nextWaveIndex: Int = 0
) {
    init {
        require(nextWaveIndex >= 0)
    }

    fun advance(
        schedule: AgentParallelScheduleResult.Ready,
        waveResult: AgentParallelWaveExecutionResult
    ): AgentParallelWaveProgress? {
        val wave = schedule.waves.getOrNull(nextWaveIndex) ?: return null
        val expectedPrior = schedule.waves.take(nextWaveIndex)
            .flatMap { it.stepIds }.toSet()
        if (completedStepIds != expectedPrior) return null
        if (waveResult.state != AgentParallelWaveExecutionState.COMPLETED) return null
        val completed = waveResult.outcomes.map {
            when (it) {
                is AgentParallelWaveTaskOutcome.Completed -> it.stepId
                is AgentParallelWaveTaskOutcome.Failed -> return null
            }
        }
        if (completed != wave.stepIds) return null
        return copy(
            completedStepIds = completedStepIds + completed,
            nextWaveIndex = nextWaveIndex + 1
        )
    }
}
