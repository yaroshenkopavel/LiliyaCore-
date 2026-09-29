package pro.liliya.core.asf

class AgentCoordinator(
    private val factory: AgentFactory,
    private val aggregateBudget: AgentAggregateBudget
) {
    fun runSequential(
        plan: AgentCoordinatorPlan,
        runWindow: AgentCoordinatorRunWindow,
        cancelled: () -> Boolean = { false }
    ): AgentCoordinatorResult {
        var usage = AgentAggregateUsage()
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
                    artifacts, terminals, usage, completedSteps
                )
            }

            val projectedStarted = try {
                usage.startAgent()
            } catch (_: ArithmeticException) {
                return budgetExhausted(artifacts, terminals, usage, completedSteps)
            }
            if (!aggregateBudget.allows(projectedStarted)) {
                return budgetExhausted(artifacts, terminals, usage, completedSteps)
            }

            val parentId = step.parentStepId
            val parent = parentId?.let { completed[it] }
            if (parentId != null && parent == null) {
                return terminalForStop(
                    if (completedSteps == 0) AgentCoordinatorTerminalState.FAILED
                    else AgentCoordinatorTerminalState.PARTIAL,
                    artifacts, terminals, usage, completedSteps
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
                    return budgetExhausted(artifacts, terminals, usage, completedSteps)
                }

                val parentStep = parent.first
                val parentUsedDescendants = descendants[parentId] ?: 0
                val parentRemainingDescendants =
                    parentStep.budget.maxDescendants - parentUsedDescendants - 1
                if (parentRemainingDescendants < 0 || remainingAggregate == null) {
                    return budgetExhausted(artifacts, terminals, usage, completedSteps)
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
                        artifacts, terminals, usage, completedSteps
                    )
                val artifactReference = "asf-artifact:${parentArtifact.id.value}"
                val combined = step.inputReferences + artifactReference
                if (combined.distinct().size != combined.size) {
                    return terminalForStop(
                        if (completedSteps == 0) AgentCoordinatorTerminalState.FAILED
                        else AgentCoordinatorTerminalState.PARTIAL,
                        artifacts, terminals, usage, completedSteps
                    )
                }
                combined.sorted()
            } else {
                step.inputReferences
            }

            val result = factory.runSingle(
                request = request,
                population = AgentPopulationSnapshot(
                    activeAgents = 0,
                    agentsForRootTask = terminals.size,
                    directChildrenForParent = parentId?.let { directChildren[it] ?: 0 } ?: 0
                ),
                generation = AgentInstanceGeneration(1),
                admittedAt = runWindow.admittedAt,
                expiresAt = runWindow.expiresAt,
                inputReferences = effectiveInputReferences,
                cancellationRequested = cancelled,
                parentScope = parentScope,
                parentRemainingBudget = parentRemainingBudget
            )

            when (result) {
                is AgentFactoryResult.Rejected -> {
                    return terminalForStop(
                        if (completedSteps == 0) AgentCoordinatorTerminalState.FAILED
                        else AgentCoordinatorTerminalState.PARTIAL,
                        artifacts, terminals, usage, completedSteps
                    )
                }

                is AgentFactoryResult.Terminal -> {
                    usage = projectedStarted
                    terminals += result.instance

                    if (parentId != null) {
                        directChildren[parentId] = (directChildren[parentId] ?: 0) + 1
                        ancestorChain(plan, parentId).forEach { ancestorId ->
                            descendants[ancestorId] = (descendants[ancestorId] ?: 0) + 1
                        }
                    }

                    result.usage?.let {
                        usage = try {
                            usage.plus(it)
                        } catch (_: ArithmeticException) {
                            return budgetExhausted(artifacts, terminals, usage, completedSteps)
                        }
                    }

                    if (!aggregateBudget.allows(usage)) {
                        return budgetExhausted(artifacts, terminals, usage, completedSteps)
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
                                artifacts, terminals, usage, completedSteps
                            )
                        }

                        AgentLifecycleState.BUDGET_EXHAUSTED -> {
                            return budgetExhausted(artifacts, terminals, usage, completedSteps)
                        }

                        else -> {
                            return terminalForStop(
                                if (completedSteps == 0) AgentCoordinatorTerminalState.FAILED
                                else AgentCoordinatorTerminalState.PARTIAL,
                                artifacts, terminals, usage, completedSteps
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
            completedSteps = completedSteps
        )
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
        completedSteps: Int
    ) = AgentCoordinatorResult(
        state = state,
        artifacts = artifacts.toList(),
        terminalInstances = terminals.toList(),
        aggregateUsage = usage,
        completedSteps = completedSteps
    )

    private fun budgetExhausted(
        artifacts: List<AgentArtifact>,
        terminals: List<AgentInstance>,
        usage: AgentAggregateUsage,
        completedSteps: Int
    ) = terminalForStop(
        AgentCoordinatorTerminalState.BUDGET_EXHAUSTED,
        artifacts,
        terminals,
        usage,
        completedSteps
    )
}
