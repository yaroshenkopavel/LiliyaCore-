package pro.liliya.core.asf

data class AgentWorkerExecutionBinding(
    val workerClass: AgentWorkerClass,
    val profile: AgentWorkerProfile,
    val runtime: AgentWorkerRuntimeDescriptor
) {
    init {
        require(profile.workerClass == workerClass) {
            "worker execution binding profile class mismatch"
        }
    }

    companion object {
        internal fun from(
            decision: AgentWorkerAdmissionDecision.Admissible
        ) = AgentWorkerExecutionBinding(
            workerClass = decision.workerClass,
            profile = decision.profile,
            runtime = decision.runtime
        )
    }
}

sealed interface AgentWorkerFactoryResult {
    data class Rejected(
        val reason: AgentWorkerAdmissionRejection
    ) : AgentWorkerFactoryResult

    data class Delegated(
        val binding: AgentWorkerExecutionBinding,
        val result: AgentFactoryResult
    ) : AgentWorkerFactoryResult
}

class AgentWorkerFactory(
    private val admissionPolicy: AgentWorkerAdmissionPolicy,
    private val delegate: AgentFactory
) {
    fun runSingle(
        workerClass: AgentWorkerClass,
        runtime: AgentWorkerRuntimeDescriptor,
        request: AgentSpawnRequest,
        population: AgentPopulationSnapshot,
        generation: AgentInstanceGeneration,
        admittedAt: java.time.Instant,
        expiresAt: java.time.Instant,
        inputReferences: Collection<String>,
        protectedToolViewRequested: Boolean = false,
        cancellationRequested: () -> Boolean = { false },
        parentScope: AgentCognitiveScope? = null,
        parentRemainingBudget: AgentWorkBudget? = null
    ): AgentWorkerFactoryResult {
        return when (
            val decision = admissionPolicy.evaluate(
                workerClass = workerClass,
                request = request,
                runtime = runtime,
                protectedToolViewRequested = protectedToolViewRequested
            )
        ) {
            is AgentWorkerAdmissionDecision.Rejected ->
                AgentWorkerFactoryResult.Rejected(decision.reason)

            is AgentWorkerAdmissionDecision.Admissible ->
                AgentWorkerFactoryResult.Delegated(
                    binding = AgentWorkerExecutionBinding.from(decision),
                    result = delegate.runSingle(
                        request = request,
                        population = population,
                        generation = generation,
                        admittedAt = admittedAt,
                        expiresAt = expiresAt,
                        inputReferences = inputReferences,
                        cancellationRequested = cancellationRequested,
                        parentScope = parentScope,
                        parentRemainingBudget = parentRemainingBudget,
                        workerRuntime = runtime
                    )
                )
        }
    }
}
