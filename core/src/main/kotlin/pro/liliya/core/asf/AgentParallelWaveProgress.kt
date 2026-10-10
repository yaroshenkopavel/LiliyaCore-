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

    /**
     * Verified commit evidence must be supplied independently by the runtime.
     * Bare executor completion is insufficient for a dependent wave.
     */
    fun advanceVerified(
        schedule: AgentParallelScheduleResult.Ready,
        waveResult: AgentParallelWaveExecutionResult,
        committedStepIds: Set<AgentCoordinatorStepId>
    ): AgentParallelWaveProgress? {
        val wave = schedule.waves.getOrNull(nextWaveIndex) ?: return null
        if (committedStepIds != wave.stepIds.toSet()) return null
        return advance(schedule, waveResult)
    }

    /**
     * Advisory equality check between executor artifact references and separately
     * supplied commit receipts. The caller must verify receipt authenticity and
     * durable persistence; this method does NOT establish either property.
     */
    fun advanceArtifactMatched(
        schedule: AgentParallelScheduleResult.Ready,
        waveResult: AgentParallelWaveExecutionResult,
        committedArtifacts: Map<AgentCoordinatorStepId, String>
    ): AgentParallelWaveProgress? {
        val wave = schedule.waves.getOrNull(nextWaveIndex) ?: return null
        if (committedArtifacts.keys != wave.stepIds.toSet()) return null
        val completed = waveResult.outcomes.map {
            it as? AgentParallelWaveTaskOutcome.Completed ?: return null
        }
        if (completed.size != wave.stepIds.size) return null
        if (completed.any {
                val reference = it.artifactReference
                reference == null || committedArtifacts[it.stepId] != reference
            }
        ) return null
        return advanceVerified(schedule, waveResult, committedArtifacts.keys)
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
