package pro.liliya.core.asf

class AgentCoordinator(
    private val factory: AgentFactory,
    private val aggregateBudget: AgentAggregateBudget,
    private val workerFactory: AgentWorkerFactory? = null
) {
    /**
     * Read-only preflight for a potential parallel coordinator run.
     *
     * This does not admit agents, start tasks or grant authority. The existing
     * runSequential path remains the only production coordinator execution mode.
     */
    fun previewParallelSchedule(plan: AgentCoordinatorPlan): AgentParallelScheduleResult =
        AgentParallelScheduler.schedule(plan, aggregateBudget)

    /**
     * Advisory wave barrier preview only; supplied completion IDs are not proof
     * of authorization, successful runtime execution or artifact commitment.
     * Requires completed waves to be a contiguous prefix and never exposes a
     * partially completed wave as ready for further dispatch.
     */
    fun previewNextParallelWave(
        plan: AgentCoordinatorPlan,
        completedStepIds: Set<AgentCoordinatorStepId>
    ): AgentParallelWave? {
        val scheduled = previewParallelSchedule(plan) as? AgentParallelScheduleResult.Ready
            ?: return null
        val completedPrefix = mutableSetOf<AgentCoordinatorStepId>()
        for (wave in scheduled.waves) {
            if (completedStepIds == completedPrefix) return wave
            completedPrefix.addAll(wave.stepIds)
        }
        return null
    }

    /**
     * Advisory post-wave checkpoint. Rechecks this coordinator's budgeted plan
     * before accepting the exact completed wave. Never authorizes a task, agent,
     * tool, artifact or dispatch; the production execution path remains sequential.
     */
    fun previewAdvanceParallelWave(
        plan: AgentCoordinatorPlan,
        progress: AgentParallelWaveProgress,
        result: AgentParallelWaveExecutionResult
    ): AgentParallelWaveProgress? {
        val schedule = previewParallelSchedule(plan) as? AgentParallelScheduleResult.Ready
            ?: return null
        return progress.advance(schedule, result)
    }

    /**
     * Advisory verified wave checkpoint, not a dispatch authorization.
     * The caller must independently establish provenance of committedStepIds;
     * this method only checks exact membership against the budgeted schedule.
     */
    fun previewAdvanceVerifiedParallelWave(
        plan: AgentCoordinatorPlan,
        progress: AgentParallelWaveProgress,
        result: AgentParallelWaveExecutionResult,
        committedStepIds: Set<AgentCoordinatorStepId>
    ): AgentParallelWaveProgress? {
        val schedule = previewParallelSchedule(plan) as? AgentParallelScheduleResult.Ready
            ?: return null
        return progress.advanceVerified(schedule, result, committedStepIds)
    }

    fun runSequential(
        plan: AgentCoordinatorPlan,
        runWindow: AgentCoordinatorRunWindow,
        cancelled: () -> Boolean = { false }
    ): AgentCoordinatorResult {
        var usage = AgentAggregateUsage()
        var workerUsage = AgentWorkerAggregateUsage()
        val artifacts = mutableListOf<AgentArtifact>()
        val terminals = mutableListOf<AgentInstance>()
        val completed = mutableMapOf<AgentCoordinatorStepId, Pair<AgentCoordinatorStep, AgentInstance>>()
        val completedArtifacts = mutableMapOf<AgentCoordinatorStepId, AgentArtifact>()
        val directChildren = mutableMapOf<AgentCoordinatorStepId, Int>()
        val descendants = mutableMapOf<AgentCoordinatorStepId, Int>()
        var completedSteps = 0

        plan.steps.forEach { step ->
            if (cancelled()) {
                return terminalForStop(
                    if (completedSteps == 0) AgentCoordinatorTerminalState.CANCELLED
                    else AgentCoordinatorTerminalState.PARTIAL,
                    artifacts, terminals, usage, workerUsage, completedSteps
                )
            }

            val projectedStarted = try {
                usage.startAgent()
            } catch (_: ArithmeticException) {
                return budgetExhausted(artifacts, terminals, usage, workerUsage, completedSteps)
            }
            if (!aggregateBudget.allows(projectedStarted)) {
                return budgetExhausted(artifacts, terminals, usage, workerUsage, completedSteps)
            }

            val parentId = step.parentStepId
            val parent = parentId?.let { completed[it] }
            if (parentId != null && parent == null) {
                return terminalForStop(
                    if (completedSteps == 0) AgentCoordinatorTerminalState.FAILED
                    else AgentCoordinatorTerminalState.PARTIAL,
                    artifacts, terminals, usage, workerUsage, completedSteps
                )
            }

            val depth = parent?.second?.provenance?.depth?.plus(1) ?: 0
            val provenance = AgentSpawnProvenance(
                rootTaskId = plan.rootTaskId,
                parentAgentId = parent?.second?.id,
                parentGeneration = parent?.second?.generation,
                depth = depth
            )

            val remainingAggregate = aggregateBudget.remainingAsWorkBudget(
                usage = usage,
                descendantCap = maxOf(0, aggregateBudget.maxAgents - usage.agentsStarted)
            )

            val parentScope: AgentCognitiveScope?
            val parentRemainingBudget: AgentWorkBudget?
            if (parent == null) {
                parentScope = null
                parentRemainingBudget = null
            } else {
                val ancestorIds = ancestorChain(plan, parentId)
                if (ancestorIds.any { ancestorId ->
                        val ancestor = requireNotNull(plan.step(ancestorId))
                        (descendants[ancestorId] ?: 0) >= ancestor.budget.maxDescendants
                    }
                ) {
                    return budgetExhausted(artifacts, terminals, usage, workerUsage, completedSteps)
                }

                val parentStep = parent.first
                val parentUsedDescendants = descendants[parentId] ?: 0
                val parentRemainingDescendants =
                    parentStep.budget.maxDescendants - parentUsedDescendants - 1
                if (parentRemainingDescendants < 0 || remainingAggregate == null) {
                    return budgetExhausted(artifacts, terminals, usage, workerUsage, completedSteps)
                }

                parentScope = parentStep.cognitiveScope
                parentRemainingBudget = parentStep.budget.copy(
                    maxDescendants = parentRemainingDescendants
                ).intersect(remainingAggregate)
            }

            val request = AgentSpawnRequest.create(
                blueprint = step.blueprint,
                provenance = provenance,
                cognitiveScope = step.cognitiveScope,
                budget = step.budget,
                logicalRoleAttempt = step.logicalRoleAttempt
            )

            val effectiveInputReferences = if (step.includeParentArtifact) {
                val parentArtifact = parentId?.let { completedArtifacts[it] }
                    ?: return terminalForStop(
                        if (completedSteps == 0) AgentCoordinatorTerminalState.FAILED
                        else AgentCoordinatorTerminalState.PARTIAL,
                        artifacts, terminals, usage, workerUsage, completedSteps
                    )
                val artifactReference = "asf-artifact:${parentArtifact.id.value}"
                val combined = step.inputReferences + artifactReference
                if (combined.distinct().size != combined.size) {
                    return terminalForStop(
                        if (completedSteps == 0) AgentCoordinatorTerminalState.FAILED
                        else AgentCoordinatorTerminalState.PARTIAL,
                        artifacts, terminals, usage, workerUsage, completedSteps
                    )
                }
                combined.sorted()
            } else {
                step.inputReferences
            }

            val result = runStep(
                step = step,
                request = request,
                population = AgentPopulationSnapshot(
                    activeAgents = 0,
                    agentsForRootTask = terminals.size,
                    directChildrenForParent = parentId?.let { directChildren[it] ?: 0 } ?: 0
                ),
                runWindow = runWindow,
                inputReferences = effectiveInputReferences,
                cancelled = cancelled,
                parentScope = parentScope,
                parentRemainingBudget = parentRemainingBudget
            ) ?: return terminalForStop(
                if (completedSteps == 0) AgentCoordinatorTerminalState.FAILED
                else AgentCoordinatorTerminalState.PARTIAL,
                artifacts, terminals, usage, workerUsage, completedSteps
            )

            when (result) {
                is AgentFactoryResult.Rejected -> {
                    return terminalForStop(
                        if (completedSteps == 0) AgentCoordinatorTerminalState.FAILED
                        else AgentCoordinatorTerminalState.PARTIAL,
                        artifacts, terminals, usage, workerUsage, completedSteps
                    )
                }

                is AgentFactoryResult.Terminal -> {
                    usage = projectedStarted
                    step.workerClass?.let { workerClass ->
                        workerUsage = try {
                            workerUsage.recordStarted(workerClass)
                        } catch (_: ArithmeticException) {
                            return budgetExhausted(
                                artifacts, terminals, usage, workerUsage, completedSteps
                            )
                        }
                    }
                    terminals += result.instance

                    if (parentId != null) {
                        directChildren[parentId] = (directChildren[parentId] ?: 0) + 1
                        ancestorChain(plan, parentId).forEach { ancestorId ->
                            descendants[ancestorId] = (descendants[ancestorId] ?: 0) + 1
                        }
                    }

                    result.usage?.let { runtimeUsage ->
                        try {
                            usage = usage.plus(runtimeUsage)
                            step.workerClass?.let { workerClass ->
                                workerUsage = workerUsage.plus(workerClass, runtimeUsage)
                            }
                        } catch (_: ArithmeticException) {
                            return budgetExhausted(
                                artifacts, terminals, usage, workerUsage, completedSteps
                            )
                        }
                    }

                    if (!aggregateBudget.allows(usage)) {
                        return budgetExhausted(artifacts, terminals, usage, workerUsage, completedSteps)
                    }

                    result.artifact?.let { artifacts += it }

                    when (result.instance.lifecycle) {
                        AgentLifecycleState.COMPLETED -> {
                            completedSteps++
                            completed[step.id] = step to result.instance
                            result.artifact?.let { completedArtifacts[step.id] = it }
                        }

                        AgentLifecycleState.CANCELLED -> {
                            return terminalForStop(
                                if (completedSteps == 0) AgentCoordinatorTerminalState.CANCELLED
                                else AgentCoordinatorTerminalState.PARTIAL,
                                artifacts, terminals, usage, workerUsage, completedSteps
                            )
                        }

                        AgentLifecycleState.BUDGET_EXHAUSTED -> {
                            return budgetExhausted(artifacts, terminals, usage, workerUsage, completedSteps)
                        }

                        else -> {
                            return terminalForStop(
                                if (completedSteps == 0) AgentCoordinatorTerminalState.FAILED
                                else AgentCoordinatorTerminalState.PARTIAL,
                                artifacts, terminals, usage, workerUsage, completedSteps
                            )
                        }
                    }
                }
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

    private fun ancestorChain(
        plan: AgentCoordinatorPlan,
        start: AgentCoordinatorStepId
    ): List<AgentCoordinatorStepId> {
        val result = mutableListOf<AgentCoordinatorStepId>()
        var current: AgentCoordinatorStepId? = start
        while (current != null) {
            result += current
            current = requireNotNull(plan.step(current)).parentStepId
        }
        return result
    }

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
