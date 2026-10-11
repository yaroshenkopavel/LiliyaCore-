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

    fun get(stepId: AgentCoordinatorStepId): AgentParallelCompletedParent? = completed[stepId]
}
