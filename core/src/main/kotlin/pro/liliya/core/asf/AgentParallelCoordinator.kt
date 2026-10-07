package pro.liliya.core.asf

import java.util.concurrent.ExecutorService

/**
 * Production-facing parallel coordinator over the existing ASF factory/runtime contracts.
 *
 * This class owns no Authority or execution permission. It only coordinates bounded cognitive
 * workers that were already described by AgentCoordinatorPlan.
 */
class AgentParallelCoordinator(
    private val factory: AgentFactory,
    private val aggregateBudget: AgentAggregateBudget,
    private val executor: ExecutorService,
    private val workerFactory: AgentWorkerFactory? = null,
    private val bounds: AgentFactoryBounds = AgentFactoryBounds.PROTOTYPE
) {
    fun run(
        plan: AgentCoordinatorPlan,
        runWindow: AgentCoordinatorRunWindow,
        timeoutPerWaveMillis: Long,
        cancelled: () -> Boolean = { false }
    ): AgentCoordinatorResult {
        when (val schedule = AgentParallelScheduler.schedule(plan, aggregateBudget)) {
            is AgentParallelScheduleResult.Rejected ->
                return emptyResult(AgentCoordinatorTerminalState.BUDGET_EXHAUSTED)

            is AgentParallelScheduleResult.Ready -> Unit
        }

        val snapshot = when (val admission = AgentParallelAdmissionReservation.reserve(plan, bounds)) {
            is AgentParallelAdmissionResult.Ready -> admission.snapshot
            is AgentParallelAdmissionResult.Rejected ->
                return emptyResult(mapAdmissionRejection(admission.reason))
        }

        val tasks = AgentParallelFactoryTaskFactory(
            plan = plan,
            snapshot = snapshot,
            factory = factory,
            workerFactory = workerFactory,
            runWindow = runWindow,
            cancellationRequested = cancelled
        )

        val execution = AgentParallelPlanExecutor(
            waveExecutor = AgentParallelWaveExecutor(executor),
            taskFactory = tasks
        ).execute(
            plan = plan,
            aggregateBudget = aggregateBudget,
            timeoutPerWaveMillis = timeoutPerWaveMillis,
            cancelled = cancelled
        )

        return AgentParallelCoordinatorResultAssembler.assemble(
            plan = plan,
            execution = execution,
            taskFactory = tasks,
            aggregateBudget = aggregateBudget
        )
    }

    private fun mapAdmissionRejection(
        reason: AgentParallelAdmissionRejection
    ): AgentCoordinatorTerminalState =
        when (reason) {
            AgentParallelAdmissionRejection.ACTIVE_POPULATION_LIMIT,
            AgentParallelAdmissionRejection.ROOT_POPULATION_LIMIT,
            AgentParallelAdmissionRejection.DIRECT_CHILD_LIMIT,
            AgentParallelAdmissionRejection.DESCENDANT_BUDGET_EXCEEDED ->
                AgentCoordinatorTerminalState.BUDGET_EXHAUSTED

            AgentParallelAdmissionRejection.RETRY_LIMIT,
            AgentParallelAdmissionRejection.PARENT_SCOPE_TOO_NARROW,
            AgentParallelAdmissionRejection.CHILD_BUDGET_WIDENING,
            AgentParallelAdmissionRejection.MAX_DEPTH_EXCEEDED ->
                AgentCoordinatorTerminalState.FAILED
        }

    private fun emptyResult(
        state: AgentCoordinatorTerminalState
    ) = AgentCoordinatorResult(
        state = state,
        artifacts = emptyList(),
        terminalInstances = emptyList(),
        aggregateUsage = AgentAggregateUsage(),
        workerAggregateUsage = AgentWorkerAggregateUsage(),
        completedSteps = 0
    )
}
