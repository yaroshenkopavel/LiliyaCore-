package pro.liliya.core.asf

data class AgentParallelAdmissionSnapshot(
    val directChildrenByStep: Map<AgentCoordinatorStepId, Int>,
    val descendantsByStep: Map<AgentCoordinatorStepId, Int>,
    val depthByStep: Map<AgentCoordinatorStepId, Int>
) {
    init {
        require(directChildrenByStep.values.all { it >= 0 })
        require(descendantsByStep.values.all { it >= 0 })
        require(depthByStep.values.all { it >= 0 })
    }
}

sealed interface AgentParallelAdmissionResult {
    data class Ready(
        val snapshot: AgentParallelAdmissionSnapshot
    ) : AgentParallelAdmissionResult

    data class Rejected(
        val reason: AgentParallelAdmissionRejection,
        val stepId: AgentCoordinatorStepId
    ) : AgentParallelAdmissionResult
}

enum class AgentParallelAdmissionRejection {
    DIRECT_CHILD_LIMIT,
    DESCENDANT_BUDGET_EXCEEDED,
    PARENT_SCOPE_TOO_NARROW,
    CHILD_BUDGET_WIDENING,
    MAX_DEPTH_EXCEEDED
}

/**
 * Preflights structural admission invariants for a parallel coordinator plan.
 *
 * Sequential execution can discover parent population/descendant state incrementally. Concurrent
 * sibling launch cannot safely share that mutable admission state, so the complete bounded plan is
 * reserved structurally before any worker is admitted.
 */
object AgentParallelAdmissionReservation {
    fun reserve(
        plan: AgentCoordinatorPlan,
        bounds: AgentFactoryBounds = AgentFactoryBounds.PROTOTYPE
    ): AgentParallelAdmissionResult {
        val directChildren = plan.steps.associate { it.id to 0 }.toMutableMap()
        val descendants = plan.steps.associate { it.id to 0 }.toMutableMap()
        val depth = LinkedHashMap<AgentCoordinatorStepId, Int>(plan.steps.size)

        for (step in plan.steps) {
            val parentId = step.parentStepId
            if (parentId == null) {
                depth[step.id] = 0
                continue
            }

            val parent = requireNotNull(plan.step(parentId))
            val parentDepth = requireNotNull(depth[parentId])
            val stepDepth = Math.addExact(parentDepth, 1)
            if (stepDepth > bounds.maxSpawnDepth) {
                return AgentParallelAdmissionResult.Rejected(
                    AgentParallelAdmissionRejection.MAX_DEPTH_EXCEEDED,
                    step.id
                )
            }
            depth[step.id] = stepDepth

            if (!step.cognitiveScope.isWithin(parent.cognitiveScope)) {
                return AgentParallelAdmissionResult.Rejected(
                    AgentParallelAdmissionRejection.PARENT_SCOPE_TOO_NARROW,
                    step.id
                )
            }

            if (!step.budget.isWithin(parent.budget)) {
                return AgentParallelAdmissionResult.Rejected(
                    AgentParallelAdmissionRejection.CHILD_BUDGET_WIDENING,
                    step.id
                )
            }

            val nextDirect = Math.addExact(directChildren.getValue(parentId), 1)
            if (nextDirect > bounds.maxDirectChildren) {
                return AgentParallelAdmissionResult.Rejected(
                    AgentParallelAdmissionRejection.DIRECT_CHILD_LIMIT,
                    step.id
                )
            }
            directChildren[parentId] = nextDirect

            var ancestorId: AgentCoordinatorStepId? = parentId
            while (ancestorId != null) {
                val nextDescendants = Math.addExact(descendants.getValue(ancestorId), 1)
                val ancestor = requireNotNull(plan.step(ancestorId))
                if (nextDescendants > ancestor.budget.maxDescendants) {
                    return AgentParallelAdmissionResult.Rejected(
                        AgentParallelAdmissionRejection.DESCENDANT_BUDGET_EXCEEDED,
                        step.id
                    )
                }
                descendants[ancestorId] = nextDescendants
                ancestorId = ancestor.parentStepId
            }
        }

        return AgentParallelAdmissionResult.Ready(
            AgentParallelAdmissionSnapshot(
                directChildrenByStep = directChildren.toSortedMap(compareBy { it.value }),
                descendantsByStep = descendants.toSortedMap(compareBy { it.value }),
                depthByStep = depth.toSortedMap(compareBy { it.value })
            )
        )
    }
}
