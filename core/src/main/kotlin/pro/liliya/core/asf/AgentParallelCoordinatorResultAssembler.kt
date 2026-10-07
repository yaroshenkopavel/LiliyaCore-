package pro.liliya.core.asf

/**
 * Reconstructs the existing AgentCoordinatorResult contract from concurrent factory terminals.
 *
 * Terminal instances, artifacts and usage are folded strictly in canonical plan order so final
 * observable accounting is deterministic even when sibling completion order differs.
 */
object AgentParallelCoordinatorResultAssembler {
    fun assemble(
        plan: AgentCoordinatorPlan,
        execution: AgentParallelPlanExecutionResult,
        taskFactory: AgentParallelFactoryTaskFactory,
        aggregateBudget: AgentAggregateBudget
    ): AgentCoordinatorResult {
        var usage = AgentAggregateUsage()
        var workerUsage = AgentWorkerAggregateUsage()
        val artifacts = mutableListOf<AgentArtifact>()
        val terminals = mutableListOf<AgentInstance>()
        var completedSteps = 0
        var budgetExceeded = false
        var sawBudgetTerminal = false
        var sawCancelledTerminal = false
        val publishedStepIds = execution.outcomes.map { outcome ->
            when (outcome) {
                is AgentParallelWaveTaskOutcome.Completed -> outcome.stepId
                is AgentParallelWaveTaskOutcome.Failed -> outcome.stepId
            }
        }.toSet()

        for ((step, terminal) in taskFactory.terminalsInPlanOrder()) {
            if (step.id !in publishedStepIds) continue
            try {
                usage = usage.startAgent()
                step.workerClass?.let { workerClass ->
                    workerUsage = workerUsage.recordStarted(workerClass)
                }
                terminal.usage?.let { runtimeUsage ->
                    usage = usage.plus(runtimeUsage)
                    step.workerClass?.let { workerClass ->
                        workerUsage = workerUsage.plus(workerClass, runtimeUsage)
                    }
                }
            } catch (_: ArithmeticException) {
                budgetExceeded = true
            }

            terminals += terminal.instance
            terminal.artifact?.let(artifacts::add)

            when (terminal.instance.lifecycle) {
                AgentLifecycleState.COMPLETED -> completedSteps++
                AgentLifecycleState.BUDGET_EXHAUSTED -> sawBudgetTerminal = true
                AgentLifecycleState.CANCELLED -> sawCancelledTerminal = true
                else -> Unit
            }

            if (!aggregateBudget.allows(usage)) {
                budgetExceeded = true
            }
        }

        val state = when {
            budgetExceeded || sawBudgetTerminal ->
                AgentCoordinatorTerminalState.BUDGET_EXHAUSTED

            execution.state == AgentParallelPlanExecutionState.CANCELLED ||
                sawCancelledTerminal ->
                if (completedSteps == 0) {
                    AgentCoordinatorTerminalState.CANCELLED
                } else {
                    AgentCoordinatorTerminalState.PARTIAL
                }

            execution.state == AgentParallelPlanExecutionState.COMPLETED &&
                completedSteps == plan.steps.size ->
                AgentCoordinatorTerminalState.COMPLETED

            execution.state == AgentParallelPlanExecutionState.REJECTED &&
                completedSteps == 0 ->
                AgentCoordinatorTerminalState.FAILED

            execution.state == AgentParallelPlanExecutionState.TIMED_OUT &&
                completedSteps == 0 ->
                AgentCoordinatorTerminalState.FAILED

            completedSteps == 0 ->
                AgentCoordinatorTerminalState.FAILED

            else ->
                AgentCoordinatorTerminalState.PARTIAL
        }

        return AgentCoordinatorResult(
            state = state,
            artifacts = artifacts,
            terminalInstances = terminals,
            aggregateUsage = usage,
            workerAggregateUsage = workerUsage,
            completedSteps = completedSteps
        )
    }
}
