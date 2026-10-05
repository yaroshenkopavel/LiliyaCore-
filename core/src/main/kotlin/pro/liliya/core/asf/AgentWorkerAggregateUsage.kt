package pro.liliya.core.asf

data class AgentWorkerAggregateUsage(
    val nano: AgentAggregateUsage = AgentAggregateUsage(),
    val micro: AgentAggregateUsage = AgentAggregateUsage(),
    val full: AgentAggregateUsage = AgentAggregateUsage()
) {
    fun forClass(workerClass: AgentWorkerClass): AgentAggregateUsage =
        when (workerClass) {
            AgentWorkerClass.NANO -> nano
            AgentWorkerClass.MICRO -> micro
            AgentWorkerClass.FULL -> full
        }

    fun recordStarted(workerClass: AgentWorkerClass): AgentWorkerAggregateUsage =
        update(workerClass) { it.startAgent() }

    fun plus(
        workerClass: AgentWorkerClass,
        usage: AgentRuntimeUsage
    ): AgentWorkerAggregateUsage =
        update(workerClass) { it.plus(usage) }

    fun total(): AgentAggregateUsage =
        nano.combine(micro).combine(full)

    private fun update(
        workerClass: AgentWorkerClass,
        transform: (AgentAggregateUsage) -> AgentAggregateUsage
    ): AgentWorkerAggregateUsage =
        when (workerClass) {
            AgentWorkerClass.NANO -> copy(nano = transform(nano))
            AgentWorkerClass.MICRO -> copy(micro = transform(micro))
            AgentWorkerClass.FULL -> copy(full = transform(full))
        }
}

internal fun AgentAggregateUsage.combine(other: AgentAggregateUsage): AgentAggregateUsage =
    AgentAggregateUsage(
        wallClockMillis = Math.addExact(wallClockMillis, other.wallClockMillis),
        inferenceUnits = Math.addExact(inferenceUnits, other.inferenceUnits),
        contextBytes = Math.addExact(contextBytes, other.contextBytes),
        retrievalItems = Math.addExact(retrievalItems, other.retrievalItems),
        artifactCount = Math.addExact(artifactCount, other.artifactCount),
        agentsStarted = Math.addExact(agentsStarted, other.agentsStarted)
    )

internal fun AgentAggregateUsage.isWithin(other: AgentAggregateUsage): Boolean =
    wallClockMillis <= other.wallClockMillis &&
        inferenceUnits <= other.inferenceUnits &&
        contextBytes <= other.contextBytes &&
        retrievalItems <= other.retrievalItems &&
        artifactCount <= other.artifactCount &&
        agentsStarted <= other.agentsStarted
