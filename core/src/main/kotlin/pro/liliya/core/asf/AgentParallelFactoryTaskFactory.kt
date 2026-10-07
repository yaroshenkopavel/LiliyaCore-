package pro.liliya.core.asf

import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges pre-reserved parallel plan steps into the existing AgentFactory / AgentWorkerFactory
 * admission and runtime path.
 *
 * All population/provenance counters come from AgentParallelAdmissionSnapshot. No shared mutable
 * admission counters are incremented while sibling tasks run concurrently.
 */
class AgentParallelFactoryTaskFactory(
    private val plan: AgentCoordinatorPlan,
    private val snapshot: AgentParallelAdmissionSnapshot,
    private val factory: AgentFactory,
    private val workerFactory: AgentWorkerFactory? = null,
    private val runWindow: AgentCoordinatorRunWindow,
    private val cancellationRequested: () -> Boolean = { false }
) : AgentParallelPlanTaskFactory {
    private val terminals =
        ConcurrentHashMap<AgentCoordinatorStepId, AgentFactoryResult.Terminal>()

    override fun create(
        step: AgentCoordinatorStep,
        parentArtifactReference: String?
    ): AgentParallelWaveTask {
        val parentId = step.parentStepId
        val parentTerminal = parentId?.let(terminals::get)

        if (parentId != null && parentTerminal == null) {
            return failedTask(step.id, "parallel factory parent terminal unavailable")
        }

        val effectiveInputs = if (step.includeParentArtifact) {
            if (parentArtifactReference == null) {
                return failedTask(step.id, "parallel factory parent artifact unavailable")
            }
            val combined = step.inputReferences + parentArtifactReference
            if (combined.distinct().size != combined.size) {
                return failedTask(step.id, "parallel factory input provenance collision")
            }
            combined.sorted()
        } else {
            step.inputReferences
        }

        val provenance = AgentSpawnProvenance(
            rootTaskId = plan.rootTaskId,
            parentAgentId = parentTerminal?.instance?.id,
            parentGeneration = parentTerminal?.instance?.generation,
            depth = snapshot.depthByStep.getValue(step.id)
        )
        val request = AgentSpawnRequest.create(
            blueprint = step.blueprint,
            provenance = provenance,
            cognitiveScope = step.cognitiveScope,
            budget = step.budget,
            logicalRoleAttempt = step.logicalRoleAttempt
        )
        val population = AgentPopulationSnapshot(
            activeAgents = snapshot.priorActiveAgentsByStep.getValue(step.id),
            agentsForRootTask = snapshot.priorAgentsForRootTaskByStep.getValue(step.id),
            directChildrenForParent =
                snapshot.priorDirectChildrenForParentByStep.getValue(step.id)
        )
        val parentScope = parentId?.let { requireNotNull(plan.step(it)).cognitiveScope }
        val parentRemainingBudget = parentId?.let {
            requireNotNull(plan.step(it)).budget.copy(
                maxDescendants =
                    snapshot.parentRemainingDescendantsByStep.getValue(step.id)
            )
        }

        return AgentParallelWaveTask {
            val result = runStep(
                step = step,
                request = request,
                population = population,
                inputReferences = effectiveInputs,
                parentScope = parentScope,
                parentRemainingBudget = parentRemainingBudget
            )

            when (result) {
                null ->
                    AgentParallelWaveTaskOutcome.Failed(
                        step.id,
                        "parallel factory worker execution unavailable"
                    )

                is AgentFactoryResult.Rejected ->
                    AgentParallelWaveTaskOutcome.Failed(
                        step.id,
                        "parallel factory admission rejected: " + result.reason.name
                    )

                is AgentFactoryResult.Terminal -> {
                    terminals[step.id] = result
                    if (result.instance.lifecycle == AgentLifecycleState.COMPLETED) {
                        val artifact = result.artifact
                            ?: return@AgentParallelWaveTask AgentParallelWaveTaskOutcome.Failed(
                                step.id,
                                "parallel factory completed without artifact"
                            )
                        AgentParallelWaveTaskOutcome.Completed(
                            stepId = step.id,
                            artifactReference = "asf-artifact:" + artifact.id.value
                        )
                    } else {
                        AgentParallelWaveTaskOutcome.Failed(
                            step.id,
                            "parallel factory terminal lifecycle: " +
                                result.instance.lifecycle.name
                        )
                    }
                }
            }
        }
    }

    fun terminal(
        stepId: AgentCoordinatorStepId
    ): AgentFactoryResult.Terminal? = terminals[stepId]

    fun terminalsInPlanOrder(): List<Pair<AgentCoordinatorStep, AgentFactoryResult.Terminal>> =
        plan.steps.mapNotNull { step ->
            terminals[step.id]?.let { terminal -> step to terminal }
        }

    private fun runStep(
        step: AgentCoordinatorStep,
        request: AgentSpawnRequest,
        population: AgentPopulationSnapshot,
        inputReferences: Collection<String>,
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
                cancellationRequested = cancellationRequested,
                parentScope = parentScope,
                parentRemainingBudget = parentRemainingBudget
            )
        }

        val workers = workerFactory ?: return null
        val runtime = step.runtime ?: return null
        return when (
            val workerResult = workers.runSingle(
                workerClass = workerClass,
                runtime = runtime,
                request = request,
                population = population,
                generation = AgentInstanceGeneration(1),
                admittedAt = runWindow.admittedAt,
                expiresAt = runWindow.expiresAt,
                inputReferences = inputReferences,
                protectedToolViewRequested = step.protectedToolViewRequested,
                cancellationRequested = cancellationRequested,
                parentScope = parentScope,
                parentRemainingBudget = parentRemainingBudget
            )
        ) {
            is AgentWorkerFactoryResult.Rejected -> null
            is AgentWorkerFactoryResult.Delegated -> workerResult.result
        }
    }

    private fun failedTask(
        stepId: AgentCoordinatorStepId,
        reason: String
    ) = AgentParallelWaveTask {
        AgentParallelWaveTaskOutcome.Failed(stepId, reason)
    }
}
