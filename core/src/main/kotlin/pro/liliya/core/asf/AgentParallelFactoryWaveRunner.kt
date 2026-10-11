package pro.liliya.core.asf

import java.time.Instant

/**
 * Experimental factory-backed wave runner. This never grants tool authority,
 * and its artifacts are NOT durable commit receipts. Production coordinator
 * execution remains sequential until atomic admission and persistence exist.
 */
class AgentParallelFactoryWaveRunner(
    private val factory: AgentFactory,
    private val executor: AgentParallelWaveExecutor
) {
    data class Entry(
        val request: AgentSpawnRequest,
        val generation: AgentInstanceGeneration,
        val inputReferences: List<String>,
        val parentScope: AgentCognitiveScope? = null,
        val parentRemainingBudget: AgentWorkBudget? = null
    )

    fun run(
        wave: AgentParallelWave,
        entries: Map<AgentCoordinatorStepId, Entry>,
        baselinePopulation: AgentPopulationSnapshot,
        admittedAt: Instant,
        expiresAt: Instant,
        timeoutMillis: Long,
        cancelled: () -> Boolean = { false }
    ): AgentParallelWaveExecutionResult {
        require(entries.keys == wave.stepIds.toSet())
        require(wave.stepIds.size <= wave.reservation.maxAgents)
        require(entries.values.map { it.request.id }.distinct().size == entries.size) {
            "parallel factory wave requires distinct spawn identities"
        }
        require(entries.values.map { it.request.provenance.rootTaskId }.distinct().size == 1) {
            "parallel factory wave must share one root task"
        }
        // Snapshot-based population admission is safe only when the owner has
        // atomically reserved all wave slots. This API does not provide those
        // reservations and must not be exposed as a production admission path.
        val tasks = wave.stepIds.withIndex().associate { (offset, stepId) ->
            val entry = entries.getValue(stepId)
            val population = AgentPopulationSnapshot(
                activeAgents = Math.addExact(baselinePopulation.activeAgents, offset),
                agentsForRootTask = Math.addExact(baselinePopulation.agentsForRootTask, offset),
                directChildrenForParent = baselinePopulation.directChildrenForParent
            )
            stepId to AgentParallelWaveTask {
                val result = factory.runSingle(
                    request = entry.request,
                    population = population,
                    generation = entry.generation,
                    admittedAt = admittedAt,
                    expiresAt = expiresAt,
                    inputReferences = entry.inputReferences,
                    cancellationRequested = cancelled,
                    parentScope = entry.parentScope,
                    parentRemainingBudget = entry.parentRemainingBudget
                )
                when (result) {
                    is AgentFactoryResult.Rejected ->
                        AgentParallelWaveTaskOutcome.Failed(stepId, "agent factory admission rejected")
                    is AgentFactoryResult.Terminal -> {
                        val artifact = result.artifact
                        if (result.instance.lifecycle != AgentLifecycleState.COMPLETED ||
                            artifact == null || !result.workspaceDisposed
                        ) {
                            AgentParallelWaveTaskOutcome.Failed(stepId, "agent factory did not complete")
                        } else {
                            AgentParallelWaveTaskOutcome.Completed(
                                stepId, "asf-artifact:${artifact.id.value}"
                            )
                        }
                    }
                }
            }
        }
        return executor.executeAfterAdmission(
            wave = wave,
            tasks = tasks,
            timeoutMillis = timeoutMillis,
            admit = { stepId ->
                val entry = entries.getValue(stepId)
                val offset = wave.stepIds.indexOf(stepId)
                factory.previewAdmission(
                    request = entry.request,
                    population = AgentPopulationSnapshot(
                        Math.addExact(baselinePopulation.activeAgents, offset),
                        Math.addExact(baselinePopulation.agentsForRootTask, offset),
                        baselinePopulation.directChildrenForParent
                    ),
                    parentScope = entry.parentScope,
                    parentRemainingBudget = entry.parentRemainingBudget
                ) is AgentAdmissionDecision.Admissible
            },
            cancelled = cancelled
        )
    }
}
