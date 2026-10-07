package pro.liliya.core.asf

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class AgentParallelCoordinator(
    private val factory: AgentFactory,
    private val aggregateBudget: AgentAggregateBudget,
    private val bounds: AgentFactoryBounds,
    private val waveExecutor: AgentParallelWaveExecutor,
    private val workerFactory: AgentWorkerFactory? = null,
    private val timeSource: () -> Instant = { Instant.now() }
) {
    fun runParallel(
        plan: AgentCoordinatorPlan,
        runWindow: AgentCoordinatorRunWindow,
        baselineActiveAgents: Int = 0,
        baselineAgentsForRootTask: Int = 0,
        cancelled: () -> Boolean = { false }
    ): AgentCoordinatorResult {
        val schedule = when (
            val scheduled = AgentParallelScheduler.schedule(plan, aggregateBudget)
        ) {
            is AgentParallelScheduleResult.Ready -> scheduled
            is AgentParallelScheduleResult.Rejected ->
                return emptyResult(AgentCoordinatorTerminalState.BUDGET_EXHAUSTED)
        }

        val admission = when (
            val reserved = AgentParallelAdmissionReservationPlanner.reserve(
                plan = plan,
                bounds = bounds,
                baselineActiveAgents = baselineActiveAgents,
                baselineAgentsForRootTask = baselineAgentsForRootTask
            )
        ) {
            is AgentParallelAdmissionReservationResult.Ready -> reserved.reservation
            is AgentParallelAdmissionReservationResult.Rejected ->
                return emptyResult(AgentCoordinatorTerminalState.FAILED)
        }
        val parentReservations = admission.parents.associateBy { it.parentStepId }

        var usage = AgentAggregateUsage()
        var workerUsage = AgentWorkerAggregateUsage()
        val artifacts = mutableListOf<AgentArtifact>()
        val terminals = mutableListOf<AgentInstance>()
        val completed = mutableMapOf<AgentCoordinatorStepId, Pair<AgentCoordinatorStep, AgentInstance>>()
        val completedArtifacts = mutableMapOf<AgentCoordinatorStepId, AgentArtifact>()
        var completedSteps = 0

        for (wave in schedule.waves) {
            if (cancelled()) {
                return terminalForStop(
                    if (completedSteps == 0) AgentCoordinatorTerminalState.CANCELLED
                    else AgentCoordinatorTerminalState.PARTIAL,
                    artifacts,
                    terminals,
                    usage,
                    workerUsage,
                    completedSteps
                )
            }

            val remainingMillis = try {
                Duration.between(
                    timeSource(),
                    runWindow.expiresAt
                ).toMillis()
            } catch (_: Exception) {
                return terminalForStop(
                    if (completedSteps == 0) AgentCoordinatorTerminalState.FAILED
                    else AgentCoordinatorTerminalState.PARTIAL,
                    artifacts,
                    terminals,
                    usage,
                    workerUsage,
                    completedSteps
                )
            }
            if (remainingMillis <= 0L) {
                return terminalForStop(
                    if (completedSteps == 0) AgentCoordinatorTerminalState.FAILED
                    else AgentCoordinatorTerminalState.PARTIAL,
                    artifacts,
                    terminals,
                    usage,
                    workerUsage,
                    completedSteps
                )
            }

            val factoryResults = ConcurrentHashMap<AgentCoordinatorStepId, AgentFactoryResult>()
            val tasks = linkedMapOf<AgentCoordinatorStepId, AgentParallelWaveTask>()

            for (stepId in wave.stepIds) {
                val step = requireNotNull(plan.step(stepId))
                val parentId = step.parentStepId
                val parent = parentId?.let(completed::get)
                if (parentId != null && parent == null) {
                    return terminalForStop(
                        if (completedSteps == 0) AgentCoordinatorTerminalState.FAILED
                        else AgentCoordinatorTerminalState.PARTIAL,
                        artifacts,
                        terminals,
                        usage,
                        workerUsage,
                        completedSteps
                    )
                }

                val effectiveInputReferences = if (step.includeParentArtifact) {
                    val parentArtifact = parentId?.let(completedArtifacts::get)
                        ?: return terminalForStop(
                            if (completedSteps == 0) AgentCoordinatorTerminalState.FAILED
                            else AgentCoordinatorTerminalState.PARTIAL,
                            artifacts,
                            terminals,
                            usage,
                            workerUsage,
                            completedSteps
                        )
                    val artifactReference = "asf-artifact:${parentArtifact.id.value}"
                    val combined = step.inputReferences + artifactReference
                    if (combined.distinct().size != combined.size) {
                        return terminalForStop(
                            if (completedSteps == 0) AgentCoordinatorTerminalState.FAILED
                            else AgentCoordinatorTerminalState.PARTIAL,
                            artifacts,
                            terminals,
                            usage,
                            workerUsage,
                            completedSteps
                        )
                    }
                    combined.sorted()
                } else {
                    step.inputReferences
                }

                val provenance = AgentSpawnProvenance(
                    rootTaskId = plan.rootTaskId,
                    parentAgentId = parent?.second?.id,
                    parentGeneration = parent?.second?.generation,
                    depth = parent?.second?.provenance?.depth?.plus(1) ?: 0
                )
                val request = AgentSpawnRequest.create(
                    blueprint = step.blueprint,
                    provenance = provenance,
                    cognitiveScope = step.cognitiveScope,
                    budget = step.budget,
                    logicalRoleAttempt = step.logicalRoleAttempt
                )

                val parentRemainingBudget = if (parentId == null) {
                    null
                } else {
                    val parentStep = requireNotNull(parent).first
                    val reservation = requireNotNull(parentReservations[parentId])
                    val currentCapacity = Math.addExact(
                        1,
                        step.budget.maxDescendants
                    )
                    val siblingCapacity = Math.subtractExact(
                        reservation.reservedDescendantCapacity,
                        currentCapacity
                    )
                    val remainingDescendants = Math.subtractExact(
                        Math.subtractExact(
                            parentStep.budget.maxDescendants,
                            siblingCapacity
                        ),
                        1
                    )
                    if (remainingDescendants < 0) {
                        return budgetExhausted(
                            artifacts,
                            terminals,
                            usage,
                            workerUsage,
                            completedSteps
                        )
                    }
                    parentStep.budget.copy(
                        maxDescendants = remainingDescendants
                    )
                }

                val projectedActiveAgents = Math.addExact(
                    baselineActiveAgents,
                    wave.stepIds.size - 1
                )
                val projectedRootAgents = Math.addExact(
                    Math.addExact(
                        baselineAgentsForRootTask,
                        terminals.size
                    ),
                    wave.stepIds.size - 1
                )
                val directChildrenForParent = parentId?.let {
                    maxOf(
                        0,
                        requireNotNull(parentReservations[it]).directChildren - 1
                    )
                } ?: 0

                tasks[stepId] = AgentParallelWaveTask {
                    val result = runStep(
                        step = step,
                        request = request,
                        population = AgentPopulationSnapshot(
                            activeAgents = projectedActiveAgents,
                            agentsForRootTask = projectedRootAgents,
                            directChildrenForParent = directChildrenForParent
                        ),
                        runWindow = runWindow,
                        inputReferences = effectiveInputReferences,
                        cancelled = cancelled,
                        parentScope = parent?.first?.cognitiveScope,
                        parentRemainingBudget = parentRemainingBudget
                    )

                    if (result == null) {
                        AgentParallelWaveTaskOutcome.Failed(
                            stepId = stepId,
                            reason = "parallel worker admission rejected"
                        )
                    } else {
                        factoryResults[stepId] = result
                        toWaveOutcome(stepId, result)
                    }
                }
            }

            val waveResult = try {
                waveExecutor.execute(
                    wave = wave,
                    tasks = tasks,
                    timeoutMillis = remainingMillis,
                    cancelled = cancelled
                )
            } catch (_: ArithmeticException) {
                return budgetExhausted(
                    artifacts,
                    terminals,
                    usage,
                    workerUsage,
                    completedSteps
                )
            }

            var waveFailed = waveResult.state != AgentParallelWaveExecutionState.COMPLETED
            var waveCancelled = waveResult.state == AgentParallelWaveExecutionState.CANCELLED
            var waveBudgetExhausted = false

            for (outcome in waveResult.outcomes) {
                val stepId = when (outcome) {
                    is AgentParallelWaveTaskOutcome.Completed -> outcome.stepId
                    is AgentParallelWaveTaskOutcome.Failed -> outcome.stepId
                }
                val step = requireNotNull(plan.step(stepId))
                val result = factoryResults[stepId]
                if (result == null) {
                    waveFailed = true
                    continue
                }

                when (result) {
                    is AgentFactoryResult.Rejected -> {
                        waveFailed = true
                    }

                    is AgentFactoryResult.Terminal -> {
                        usage = try {
                            usage.startAgent()
                        } catch (_: ArithmeticException) {
                            return budgetExhausted(
                                artifacts,
                                terminals,
                                usage,
                                workerUsage,
                                completedSteps
                            )
                        }

                        step.workerClass?.let { workerClass ->
                            workerUsage = try {
                                workerUsage.recordStarted(workerClass)
                            } catch (_: ArithmeticException) {
                                return budgetExhausted(
                                    artifacts,
                                    terminals,
                                    usage,
                                    workerUsage,
                                    completedSteps
                                )
                            }
                        }

                        terminals += result.instance

                        result.usage?.let { runtimeUsage ->
                            try {
                                usage = usage.plus(runtimeUsage)
                                step.workerClass?.let { workerClass ->
                                    workerUsage = workerUsage.plus(workerClass, runtimeUsage)
                                }
                            } catch (_: ArithmeticException) {
                                return budgetExhausted(
                                    artifacts,
                                    terminals,
                                    usage,
                                    workerUsage,
                                    completedSteps
                                )
                            }
                        }

                        if (!aggregateBudget.allows(usage)) {
                            return budgetExhausted(
                                artifacts,
                                terminals,
                                usage,
                                workerUsage,
                                completedSteps
                            )
                        }

                        result.artifact?.let(artifacts::add)

                        when (result.instance.lifecycle) {
                            AgentLifecycleState.COMPLETED -> {
                                completedSteps++
                                completed[stepId] = step to result.instance
                                result.artifact?.let {
                                    completedArtifacts[stepId] = it
                                }
                            }

                            AgentLifecycleState.CANCELLED -> {
                                waveCancelled = true
                                waveFailed = true
                            }

                            AgentLifecycleState.BUDGET_EXHAUSTED -> {
                                waveBudgetExhausted = true
                                waveFailed = true
                            }

                            else -> {
                                waveFailed = true
                            }
                        }
                    }
                }
            }

            if (waveBudgetExhausted) {
                return budgetExhausted(
                    artifacts,
                    terminals,
                    usage,
                    workerUsage,
                    completedSteps
                )
            }
            if (waveCancelled || cancelled()) {
                return terminalForStop(
                    if (completedSteps == 0) AgentCoordinatorTerminalState.CANCELLED
                    else AgentCoordinatorTerminalState.PARTIAL,
                    artifacts,
                    terminals,
                    usage,
                    workerUsage,
                    completedSteps
                )
            }
            if (waveFailed) {
                return terminalForStop(
                    if (completedSteps == 0) AgentCoordinatorTerminalState.FAILED
                    else AgentCoordinatorTerminalState.PARTIAL,
                    artifacts,
                    terminals,
                    usage,
                    workerUsage,
                    completedSteps
                )
            }
        }

        return AgentCoordinatorResult(
            state = AgentCoordinatorTerminalState.COMPLETED,
            artifacts = artifacts.toList(),
            terminalInstances = terminals.toList(),
            aggregateUsage = usage,
            workerAggregateUsage = workerUsage,
            completedSteps = completedSteps
        )
    }

    private fun runStep(
        step: AgentCoordinatorStep,
        request: AgentSpawnRequest,
        population: AgentPopulationSnapshot,
        runWindow: AgentCoordinatorRunWindow,
        inputReferences: Collection<String>,
        cancelled: () -> Boolean,
        parentScope: AgentCognitiveScope?,
        parentRemainingBudget: AgentWorkBudget?
    ): AgentFactoryResult? {
        val workerClass = step.workerClass
        if (workerClass == null) {
            return factory.runSingle(
                request = request,
                population = population,
                generation = AgentInstanceGeneration(1),
                admittedAt = runWindow.admittedAt,
                expiresAt = runWindow.expiresAt,
                inputReferences = inputReferences,
                cancellationRequested = cancelled,
                parentScope = parentScope,
                parentRemainingBudget = parentRemainingBudget
            )
        }

        val workerExecutor = workerFactory ?: return null
        val runtime = step.runtime ?: return null
        return when (
            val workerResult = workerExecutor.runSingle(
                workerClass = workerClass,
                runtime = runtime,
                request = request,
                population = population,
                generation = AgentInstanceGeneration(1),
                admittedAt = runWindow.admittedAt,
                expiresAt = runWindow.expiresAt,
                inputReferences = inputReferences,
                protectedToolViewRequested = step.protectedToolViewRequested,
                cancellationRequested = cancelled,
                parentScope = parentScope,
                parentRemainingBudget = parentRemainingBudget
            )
        ) {
            is AgentWorkerFactoryResult.Rejected -> null
            is AgentWorkerFactoryResult.Delegated -> workerResult.result
        }
    }

    private fun toWaveOutcome(
        stepId: AgentCoordinatorStepId,
        result: AgentFactoryResult
    ): AgentParallelWaveTaskOutcome =
        when (result) {
            is AgentFactoryResult.Rejected ->
                AgentParallelWaveTaskOutcome.Failed(
                    stepId,
                    "parallel agent admission rejected"
                )

            is AgentFactoryResult.Terminal ->
                if (
                    result.instance.lifecycle == AgentLifecycleState.COMPLETED &&
                    result.artifact != null
                ) {
                    AgentParallelWaveTaskOutcome.Completed(
                        stepId = stepId,
                        artifactReference = "asf-artifact:${result.artifact.id.value}"
                    )
                } else {
                    AgentParallelWaveTaskOutcome.Failed(
                        stepId = stepId,
                        reason = "parallel agent terminal state"
                    )
                }
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

    private fun terminalForStop(
        state: AgentCoordinatorTerminalState,
        artifacts: List<AgentArtifact>,
        terminals: List<AgentInstance>,
        usage: AgentAggregateUsage,
        workerUsage: AgentWorkerAggregateUsage,
        completedSteps: Int
    ) = AgentCoordinatorResult(
        state = state,
        artifacts = artifacts.toList(),
        terminalInstances = terminals.toList(),
        aggregateUsage = usage,
        workerAggregateUsage = workerUsage,
        completedSteps = completedSteps
    )

    private fun budgetExhausted(
        artifacts: List<AgentArtifact>,
        terminals: List<AgentInstance>,
        usage: AgentAggregateUsage,
        workerUsage: AgentWorkerAggregateUsage,
        completedSteps: Int
    ) = terminalForStop(
        AgentCoordinatorTerminalState.BUDGET_EXHAUSTED,
        artifacts,
        terminals,
        usage,
        workerUsage,
        completedSteps
    )
}
