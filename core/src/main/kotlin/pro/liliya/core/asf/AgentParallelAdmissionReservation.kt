package pro.liliya.core.asf

data class AgentParallelParentReservation(
    val parentStepId: AgentCoordinatorStepId,
    val directChildren: Int,
    val totalDescendants: Int
) {
    init {
        require(directChildren >= 0)
        require(totalDescendants >= directChildren)
    }
}

data class AgentParallelAdmissionReservation(
    val projectedAgentsForRootTask: Int,
    val maxConcurrentWaveAgents: Int,
    val parents: List<AgentParallelParentReservation>
) {
    init {
        require(projectedAgentsForRootTask > 0)
        require(maxConcurrentWaveAgents > 0)
        require(parents.map { it.parentStepId }.distinct().size == parents.size)
        require(parents == parents.sortedBy { it.parentStepId.value }) {
            "parallel admission parent reservations must use canonical order"
        }
    }
}

enum class AgentParallelAdmissionRejection {
    ACTIVE_POPULATION_LIMIT,
    ROOT_POPULATION_LIMIT,
    SPAWN_DEPTH_LIMIT,
    DIRECT_CHILD_LIMIT,
    DESCENDANT_BUDGET_EXCEEDED,
    CHILD_SCOPE_EXCEEDS_PARENT,
    CHILD_BUDGET_EXCEEDS_PARENT,
    ARITHMETIC_OVERFLOW
}

sealed interface AgentParallelAdmissionReservationResult {
    data class Ready(
        val reservation: AgentParallelAdmissionReservation
    ) : AgentParallelAdmissionReservationResult

    data class Rejected(
        val reason: AgentParallelAdmissionRejection
    ) : AgentParallelAdmissionReservationResult
}

/**
 * Immutable preflight reservation for one new parallel coordinator plan.
 *
 * Aggregate compute/context/retrieval/artifact ceilings are owned by AgentParallelScheduler.
 * This layer reserves population and hierarchy constraints that would otherwise race when sibling
 * admissions happen concurrently. Parent work budgets keep their existing meaning: each child
 * budget must be within its parent budget; descendant child budgets are not reinterpreted as one
 * shared parent compute wallet.
 */
object AgentParallelAdmissionReservationPlanner {
    fun reserve(
        plan: AgentCoordinatorPlan,
        bounds: AgentFactoryBounds,
        baselineActiveAgents: Int = 0,
        baselineAgentsForRootTask: Int = 0
    ): AgentParallelAdmissionReservationResult {
        require(baselineActiveAgents >= 0)
        require(baselineAgentsForRootTask >= 0)

        return try {
            val projectedRootPopulation = Math.addExact(
                baselineAgentsForRootTask,
                plan.steps.size
            )
            if (projectedRootPopulation > bounds.maxAgentsPerRootTask) {
                return reject(AgentParallelAdmissionRejection.ROOT_POPULATION_LIMIT)
            }

            val depthByStep = linkedMapOf<AgentCoordinatorStepId, Int>()
            val directChildren = linkedMapOf<AgentCoordinatorStepId, Int>()
            val descendants = linkedMapOf<AgentCoordinatorStepId, Int>()

            for (step in plan.steps) {
                val parentId = step.parentStepId
                val depth = if (parentId == null) {
                    0
                } else {
                    val parentDepth = requireNotNull(depthByStep[parentId])
                    Math.addExact(parentDepth, 1)
                }
                depthByStep[step.id] = depth
                if (depth > bounds.maxSpawnDepth) {
                    return reject(AgentParallelAdmissionRejection.SPAWN_DEPTH_LIMIT)
                }

                if (parentId != null) {
                    val parent = requireNotNull(plan.step(parentId))
                    if (!step.cognitiveScope.isWithin(parent.cognitiveScope)) {
                        return reject(AgentParallelAdmissionRejection.CHILD_SCOPE_EXCEEDS_PARENT)
                    }
                    if (!step.budget.isWithin(parent.budget)) {
                        return reject(AgentParallelAdmissionRejection.CHILD_BUDGET_EXCEEDS_PARENT)
                    }

                    directChildren[parentId] = Math.addExact(
                        directChildren[parentId] ?: 0,
                        1
                    )

                    var ancestorId: AgentCoordinatorStepId? = parentId
                    while (ancestorId != null) {
                        descendants[ancestorId] = Math.addExact(
                            descendants[ancestorId] ?: 0,
                            1
                        )
                        ancestorId = requireNotNull(plan.step(ancestorId)).parentStepId
                    }
                }
            }

            directChildren.forEach { (_, count) ->
                if (count > bounds.maxDirectChildren) {
                    return reject(AgentParallelAdmissionRejection.DIRECT_CHILD_LIMIT)
                }
            }

            descendants.forEach { (ancestorId, count) ->
                val ancestor = requireNotNull(plan.step(ancestorId))
                if (count > ancestor.budget.maxDescendants) {
                    return reject(AgentParallelAdmissionRejection.DESCENDANT_BUDGET_EXCEEDED)
                }
            }

            val maxWaveSize = depthByStep.values
                .groupingBy { it }
                .eachCount()
                .values
                .maxOrNull()
                ?: 0
            val projectedConcurrent = Math.addExact(
                baselineActiveAgents,
                maxWaveSize
            )
            if (projectedConcurrent > bounds.maxActiveAgents) {
                return reject(AgentParallelAdmissionRejection.ACTIVE_POPULATION_LIMIT)
            }

            val parentReservations = plan.steps
                .filter { (directChildren[it.id] ?: 0) > 0 }
                .map { parent ->
                    AgentParallelParentReservation(
                        parentStepId = parent.id,
                        directChildren = directChildren[parent.id] ?: 0,
                        totalDescendants = descendants[parent.id] ?: 0
                    )
                }
                .sortedBy { it.parentStepId.value }

            AgentParallelAdmissionReservationResult.Ready(
                AgentParallelAdmissionReservation(
                    projectedAgentsForRootTask = projectedRootPopulation,
                    maxConcurrentWaveAgents = maxWaveSize,
                    parents = parentReservations
                )
            )
        } catch (_: ArithmeticException) {
            reject(AgentParallelAdmissionRejection.ARITHMETIC_OVERFLOW)
        }
    }

    private fun reject(
        reason: AgentParallelAdmissionRejection
    ) = AgentParallelAdmissionReservationResult.Rejected(reason)
}
