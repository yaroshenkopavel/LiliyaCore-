package pro.liliya.core.asf

fun interface AgentParallelPlanTaskFactory {
    fun create(
        step: AgentCoordinatorStep,
        parentArtifactReference: String?
    ): AgentParallelWaveTask
}

enum class AgentParallelPlanExecutionState {
    COMPLETED,
    PARTIAL,
    CANCELLED,
    TIMED_OUT,
    REJECTED
}

data class AgentParallelPlanExecutionResult(
    val state: AgentParallelPlanExecutionState,
    val completedWaves: Int,
    val outcomes: List<AgentParallelWaveTaskOutcome>
) {
    init {
        require(completedWaves >= 0)
        val ids = outcomes.map {
            when (it) {
                is AgentParallelWaveTaskOutcome.Completed -> it.stepId
                is AgentParallelWaveTaskOutcome.Failed -> it.stepId
            }
        }
        require(ids.distinct().size == ids.size)
    }
}

/**
 * Executes dependency waves serially while allowing sibling steps inside a wave to run
 * concurrently through AgentParallelWaveExecutor.
 *
 * A child requesting its parent artifact is admitted only after the parent's terminal completed
 * outcome has been published. Failed/partial waves stop later dependency waves.
 */
class AgentParallelPlanExecutor(
    private val waveExecutor: AgentParallelWaveExecutor,
    private val taskFactory: AgentParallelPlanTaskFactory
) {
    fun execute(
        plan: AgentCoordinatorPlan,
        aggregateBudget: AgentAggregateBudget,
        timeoutPerWaveMillis: Long,
        cancelled: () -> Boolean = { false }
    ): AgentParallelPlanExecutionResult {
        val schedule = when (val result = AgentParallelScheduler.schedule(plan, aggregateBudget)) {
            is AgentParallelScheduleResult.Ready -> result
            is AgentParallelScheduleResult.Rejected ->
                return AgentParallelPlanExecutionResult(
                    state = AgentParallelPlanExecutionState.REJECTED,
                    completedWaves = 0,
                    outcomes = emptyList()
                )
        }

        val outcomes = mutableListOf<AgentParallelWaveTaskOutcome>()
        val completedArtifacts = mutableMapOf<AgentCoordinatorStepId, String?>()
        var completedWaves = 0

        for (wave in schedule.waves) {
            if (cancelled()) {
                return terminal(
                    AgentParallelPlanExecutionState.CANCELLED,
                    completedWaves,
                    outcomes
                )
            }

            val tasks = linkedMapOf<AgentCoordinatorStepId, AgentParallelWaveTask>()
            for (stepId in wave.stepIds) {
                val step = requireNotNull(plan.step(stepId))
                val parentArtifact = step.parentStepId?.let(completedArtifacts::get)

                if (step.includeParentArtifact && parentArtifact == null) {
                    return terminal(
                        AgentParallelPlanExecutionState.PARTIAL,
                        completedWaves,
                        outcomes
                    )
                }

                tasks[stepId] = taskFactory.create(
                    step = step,
                    parentArtifactReference = parentArtifact
                )
            }

            val result = waveExecutor.execute(
                wave = wave,
                tasks = tasks,
                timeoutMillis = timeoutPerWaveMillis,
                cancelled = cancelled
            )
            outcomes += result.outcomes

            result.outcomes.forEach { outcome ->
                if (outcome is AgentParallelWaveTaskOutcome.Completed) {
                    completedArtifacts[outcome.stepId] = outcome.artifactReference
                }
            }

            when (result.state) {
                AgentParallelWaveExecutionState.COMPLETED -> completedWaves++
                AgentParallelWaveExecutionState.CANCELLED ->
                    return terminal(
                        AgentParallelPlanExecutionState.CANCELLED,
                        completedWaves,
                        outcomes
                    )
                AgentParallelWaveExecutionState.TIMED_OUT ->
                    return terminal(
                        AgentParallelPlanExecutionState.TIMED_OUT,
                        completedWaves,
                        outcomes
                    )
                AgentParallelWaveExecutionState.PARTIAL ->
                    return terminal(
                        AgentParallelPlanExecutionState.PARTIAL,
                        completedWaves,
                        outcomes
                    )
            }
        }

        return AgentParallelPlanExecutionResult(
            state = AgentParallelPlanExecutionState.COMPLETED,
            completedWaves = completedWaves,
            outcomes = outcomes
        )
    }

    private fun terminal(
        state: AgentParallelPlanExecutionState,
        completedWaves: Int,
        outcomes: List<AgentParallelWaveTaskOutcome>
    ) = AgentParallelPlanExecutionResult(
        state = state,
        completedWaves = completedWaves,
        outcomes = outcomes
    )
}
