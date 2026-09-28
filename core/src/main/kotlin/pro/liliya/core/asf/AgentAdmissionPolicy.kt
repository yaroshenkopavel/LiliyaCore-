package pro.liliya.core.asf

data class AgentFactoryBounds(
    val maxActiveAgents: Int,
    val maxAgentsPerRootTask: Int,
    val maxSpawnDepth: Int,
    val maxDirectChildren: Int,
    val maxRetryPerLogicalRole: Int
) {
    init {
        require(maxActiveAgents > 0) { "max active agents must be positive" }
        require(maxAgentsPerRootTask > 0) { "max agents per root task must be positive" }
        require(maxSpawnDepth >= 0) { "max spawn depth must not be negative" }
        require(maxDirectChildren >= 0) { "max direct children must not be negative" }
        require(maxRetryPerLogicalRole >= 0) { "max retry per logical role must not be negative" }
    }
    companion object {
        val PROTOTYPE = AgentFactoryBounds(8, 12, 3, 4, 1)
    }
}

data class AgentPopulationSnapshot(
    val activeAgents: Int,
    val agentsForRootTask: Int,
    val directChildrenForParent: Int
) {
    init {
        require(activeAgents >= 0) { "active agent count must not be negative" }
        require(agentsForRootTask >= 0) { "root agent count must not be negative" }
        require(directChildrenForParent >= 0) { "direct child count must not be negative" }
    }
}

sealed interface AgentAdmissionDecision {
    data class Admissible(val request: AgentSpawnRequest) : AgentAdmissionDecision
    data class Rejected(val reason: AgentAdmissionRejection) : AgentAdmissionDecision
}

enum class AgentAdmissionRejection {
    UNKNOWN_BLUEPRINT,
    BLUEPRINT_VERSION_MISMATCH,
    SCOPE_EXCEEDS_BLUEPRINT,
    SCOPE_EXCEEDS_PARENT,
    SCOPE_EXCEEDS_GLOBAL,
    BUDGET_EXCEEDS_PARENT,
    BUDGET_EXCEEDS_GLOBAL,
    ACTIVE_POPULATION_LIMIT,
    ROOT_POPULATION_LIMIT,
    SPAWN_DEPTH_LIMIT,
    DIRECT_CHILD_LIMIT,
    RETRY_LIMIT
}

class AgentAdmissionPolicy(
    private val bounds: AgentFactoryBounds,
    private val globalScope: AgentCognitiveScope,
    private val globalBudget: AgentWorkBudget
) {
    fun evaluate(
        request: AgentSpawnRequest,
        blueprint: AgentBlueprint?,
        population: AgentPopulationSnapshot,
        parentScope: AgentCognitiveScope? = null,
        parentRemainingBudget: AgentWorkBudget? = null
    ): AgentAdmissionDecision {
        if (blueprint == null) return reject(AgentAdmissionRejection.UNKNOWN_BLUEPRINT)
        if (request.blueprint.id != blueprint.id || request.blueprint.version != blueprint.version) {
            return reject(AgentAdmissionRejection.BLUEPRINT_VERSION_MISMATCH)
        }
        if (!request.cognitiveScope.isWithin(blueprint.cognitiveScope)) {
            return reject(AgentAdmissionRejection.SCOPE_EXCEEDS_BLUEPRINT)
        }
        if (!request.cognitiveScope.isWithin(globalScope)) {
            return reject(AgentAdmissionRejection.SCOPE_EXCEEDS_GLOBAL)
        }
        if (parentScope != null && !request.cognitiveScope.isWithin(parentScope)) {
            return reject(AgentAdmissionRejection.SCOPE_EXCEEDS_PARENT)
        }
        if (!request.budget.isWithin(globalBudget)) {
            return reject(AgentAdmissionRejection.BUDGET_EXCEEDS_GLOBAL)
        }
        if (parentRemainingBudget != null && !request.budget.isWithin(parentRemainingBudget)) {
            return reject(AgentAdmissionRejection.BUDGET_EXCEEDS_PARENT)
        }
        if (population.activeAgents >= bounds.maxActiveAgents) {
            return reject(AgentAdmissionRejection.ACTIVE_POPULATION_LIMIT)
        }
        if (population.agentsForRootTask >= bounds.maxAgentsPerRootTask) {
            return reject(AgentAdmissionRejection.ROOT_POPULATION_LIMIT)
        }
        if (request.provenance.depth > bounds.maxSpawnDepth) {
            return reject(AgentAdmissionRejection.SPAWN_DEPTH_LIMIT)
        }
        if (request.provenance.parentAgentId != null &&
            population.directChildrenForParent >= bounds.maxDirectChildren
        ) {
            return reject(AgentAdmissionRejection.DIRECT_CHILD_LIMIT)
        }
        if (request.logicalRoleAttempt > bounds.maxRetryPerLogicalRole) {
            return reject(AgentAdmissionRejection.RETRY_LIMIT)
        }
        return AgentAdmissionDecision.Admissible(request)
    }

    private fun reject(reason: AgentAdmissionRejection) = AgentAdmissionDecision.Rejected(reason)
}
