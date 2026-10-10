package pro.liliya.core.asf

data class AgentParallelWaveReservation(
    val maxWallClockMillis: Long,
    val maxInferenceUnits: Long,
    val maxContextBytes: Int,
    val maxRetrievalItems: Int,
    val maxArtifacts: Int,
    val maxAgents: Int
) {
    init {
        require(maxWallClockMillis > 0)
        require(maxInferenceUnits > 0)
        require(maxContextBytes > 0)
        require(maxRetrievalItems >= 0)
        require(maxArtifacts > 0)
        require(maxAgents > 0)
    }

    fun fitsWithin(aggregate: AgentAggregateBudget): Boolean =
        maxWallClockMillis <= aggregate.maxWallClockMillis &&
            maxInferenceUnits <= aggregate.maxInferenceUnits &&
            maxContextBytes <= aggregate.maxContextBytes &&
            maxRetrievalItems <= aggregate.maxRetrievalItems &&
            maxArtifacts <= aggregate.maxArtifacts &&
            maxAgents <= aggregate.maxAgents
}

data class AgentParallelWave(
    val index: Int,
    val stepIds: List<AgentCoordinatorStepId>,
    val reservation: AgentParallelWaveReservation
) {
    init {
        require(index >= 0)
        require(stepIds.isNotEmpty())
        require(stepIds == stepIds.sortedBy { it.value }) {
            "parallel wave step ids must use canonical order"
        }
        require(stepIds.distinct().size == stepIds.size)
    }
}

sealed interface AgentParallelScheduleResult {
    data class Ready(
        val waves: List<AgentParallelWave>
    ) : AgentParallelScheduleResult {
        init {
            require(waves.isNotEmpty())
            require(waves.indices.all { waves[it].index == it })
        }
    }

    data class Rejected(
        val reason: AgentParallelScheduleRejection
    ) : AgentParallelScheduleResult
}

enum class AgentParallelScheduleRejection {
    PLAN_AGGREGATE_BUDGET_EXCEEDED,
    PARENT_DESCENDANT_BUDGET_EXCEEDED,
    ARITHMETIC_OVERFLOW
}

/**
 * Deterministic dependency-wave scheduler for future concurrent ASF execution.
 *
 * This class does not execute workers. It computes dependency-ready sibling waves and reserves
 * each wave by worker budget ceilings before any future concurrent launch. The complete plan is
 * also reserved up front so aggregate ceilings cannot be bypassed across multiple waves.
 */
object AgentParallelScheduler {
    fun schedule(
        plan: AgentCoordinatorPlan,
        aggregateBudget: AgentAggregateBudget
    ): AgentParallelScheduleResult {
        val depthByStep = LinkedHashMap<AgentCoordinatorStepId, Int>(plan.steps.size)
        for (step in plan.steps) {
            val depth = step.parentStepId?.let { parent ->
                Math.addExact(requireNotNull(depthByStep[parent]), 1)
            } ?: 0
            depthByStep[step.id] = depth
        }

        // The whole descendant tree must fit within each ancestor's spawn cap.
        // Without this check, a sibling wave could appear schedulable even though
        // runSequential would deny the later descendant during admission.
        for (ancestor in plan.steps) {
            var descendantCount = 0
            for (candidate in plan.steps) {
                var parentId = candidate.parentStepId
                while (parentId != null) {
                    if (parentId == ancestor.id) {
                        descendantCount++
                        break
                    }
                    parentId = requireNotNull(plan.step(parentId)).parentStepId
                }
                if (descendantCount > ancestor.budget.maxDescendants) {
                    return AgentParallelScheduleResult.Rejected(
                        AgentParallelScheduleRejection.PARENT_DESCENDANT_BUDGET_EXCEEDED
                    )
                }
            }
        }

        val grouped = plan.steps.groupBy { depthByStep.getValue(it.id) }
        val waves = ArrayList<AgentParallelWave>(grouped.size)

        return try {
            val planReservation = reserve(plan.steps)
            if (!planReservation.fitsWithin(aggregateBudget)) {
                return AgentParallelScheduleResult.Rejected(
                    AgentParallelScheduleRejection.PLAN_AGGREGATE_BUDGET_EXCEEDED
                )
            }

            grouped.toSortedMap().forEach { (depth, steps) ->
                val ordered = steps.sortedBy { it.id.value }
                val reservation = reserve(ordered)
                waves += AgentParallelWave(
                    index = depth,
                    stepIds = ordered.map { it.id },
                    reservation = reservation
                )
            }
            AgentParallelScheduleResult.Ready(waves)
        } catch (_: ArithmeticException) {
            AgentParallelScheduleResult.Rejected(
                AgentParallelScheduleRejection.ARITHMETIC_OVERFLOW
            )
        }
    }

    private fun reserve(
        steps: List<AgentCoordinatorStep>
    ): AgentParallelWaveReservation {
        var wall = 0L
        var inference = 0L
        var context = 0
        var retrieval = 0
        var artifacts = 0

        steps.forEach { step ->
            wall = Math.addExact(wall, step.budget.maxWallClockMillis)
            inference = Math.addExact(inference, step.budget.maxInferenceUnits)
            context = Math.addExact(context, step.budget.maxContextBytes)
            retrieval = Math.addExact(retrieval, step.budget.maxRetrievalItems)
            artifacts = Math.addExact(artifacts, step.budget.maxArtifacts)
        }

        return AgentParallelWaveReservation(
            maxWallClockMillis = wall,
            maxInferenceUnits = inference,
            maxContextBytes = context,
            maxRetrievalItems = retrieval,
            maxArtifacts = artifacts,
            maxAgents = steps.size
        )
    }
}
