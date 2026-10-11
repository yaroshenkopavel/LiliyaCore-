package pro.liliya.core.asf

/**
 * Ephemeral record captured from the actual completed factory instance.
 * Advisory only: this is neither a durable artifact receipt nor authority.
 */
data class AgentParallelCompletedParent(
    val instanceId: AgentInstanceId,
    val generation: AgentInstanceGeneration,
    val artifactReference: String
)

class AgentParallelParentEvidence {
    private val completed = java.util.concurrent.ConcurrentHashMap<AgentCoordinatorStepId, AgentParallelCompletedParent>()

    fun record(stepId: AgentCoordinatorStepId, instance: AgentInstance, artifact: AgentArtifact): Boolean {
        if (instance.lifecycle != AgentLifecycleState.COMPLETED) return false
        if (artifact.producerId != instance.id || artifact.producerGeneration != instance.generation) return false
        val reference = "asf-artifact:${artifact.id.value}"
        return completed.putIfAbsent(
            stepId, AgentParallelCompletedParent(
                instance.id, instance.generation, reference
            )
        ) == null
    }

    /**
     * Checks only recorded provenance and the caller's explicit scope/budget
     * bounds. It does not authorize child execution or validate persistence.
     */
    fun matchesChild(
        parentStepId: AgentCoordinatorStepId,
        child: AgentSpawnRequest,
        parentScope: AgentCognitiveScope,
        parentRemainingBudget: AgentWorkBudget,
        verifiedArtifactReference: String
    ): Boolean {
        val parent = completed[parentStepId] ?: return false
        return child.provenance.parentAgentId == parent.instanceId &&
            child.provenance.parentGeneration == parent.generation &&
            child.provenance.depth > 0 &&
            parent.artifactReference == verifiedArtifactReference &&
            child.cognitiveScope.isWithin(parentScope) &&
            child.budget.isWithin(parentRemainingBudget)
    }

    fun get(stepId: AgentCoordinatorStepId): AgentParallelCompletedParent? = completed[stepId]
}
