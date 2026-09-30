package pro.liliya.core.asf

import java.time.Instant

@JvmInline
value class AgentCoordinatorStepId(val value: String) {
    init {
        require(value.isNotBlank()) { "coordinator step id must not be blank" }
        require(value.length <= 128) { "coordinator step id exceeds bounded size" }
    }
}

data class AgentCoordinatorStep(
    val id: AgentCoordinatorStepId,
    val parentStepId: AgentCoordinatorStepId?,
    val blueprint: AgentBlueprintReference,
    val cognitiveScope: AgentCognitiveScope,
    val budget: AgentWorkBudget,
    val inputReferences: List<String>,
    val includeParentArtifact: Boolean = false,
    val logicalRoleAttempt: Int = 0,
    val workerClass: AgentWorkerClass? = null,
    val runtime: AgentWorkerRuntimeDescriptor? = null,
    val protectedToolViewRequested: Boolean = false
) {
    init {
        require(inputReferences.isNotEmpty()) { "coordinator step requires input provenance" }
        require(inputReferences.distinct().size == inputReferences.size) {
            "coordinator step input references must be unique"
        }
        require(inputReferences == inputReferences.sorted()) {
            "coordinator step input references must use canonical order"
        }
        require(logicalRoleAttempt >= 0) { "logical role attempt must not be negative" }
        require((workerClass == null) == (runtime == null)) {
            "worker class and runtime descriptor must be present together"
        }
        require(!protectedToolViewRequested || workerClass != null) {
            "protected ToolView request requires a worker execution profile"
        }
        require(!includeParentArtifact || parentStepId != null) {
            "root coordinator step cannot request a parent artifact"
        }
    }

    companion object {
        fun create(
            id: AgentCoordinatorStepId,
            parentStepId: AgentCoordinatorStepId?,
            blueprint: AgentBlueprintReference,
            cognitiveScope: AgentCognitiveScope,
            budget: AgentWorkBudget,
            inputReferences: Collection<String>,
            includeParentArtifact: Boolean = false,
            logicalRoleAttempt: Int = 0,
            workerClass: AgentWorkerClass? = null,
            runtime: AgentWorkerRuntimeDescriptor? = null,
            protectedToolViewRequested: Boolean = false
        ) = AgentCoordinatorStep(
            id = id,
            parentStepId = parentStepId,
            blueprint = blueprint,
            cognitiveScope = cognitiveScope,
            budget = budget,
            inputReferences = inputReferences.sorted(),
            includeParentArtifact = includeParentArtifact,
            logicalRoleAttempt = logicalRoleAttempt,
            workerClass = workerClass,
            runtime = runtime,
            protectedToolViewRequested = protectedToolViewRequested
        )
    }
}

data class AgentCoordinatorPlan(
    val rootTaskId: AgentRootTaskId,
    val steps: List<AgentCoordinatorStep>
) {
    init {
        require(steps.isNotEmpty()) { "coordinator plan must contain at least one step" }
        require(steps.size <= AgentFactoryBounds.PROTOTYPE.maxAgentsPerRootTask) {
            "coordinator plan exceeds max agents per root task"
        }
        require(steps.map { it.id }.distinct().size == steps.size) {
            "coordinator step ids must be unique"
        }
        val seen = mutableSetOf<AgentCoordinatorStepId>()
        steps.forEachIndexed { index, step ->
            if (index == 0) {
                require(step.parentStepId == null) {
                    "first coordinator step must be the root"
                }
            } else {
                require(step.parentStepId != null) {
                    "non-root coordinator step requires an explicit parent"
                }
                require(step.parentStepId in seen) {
                    "coordinator parent must reference an earlier step"
                }
            }
            require(step.parentStepId != step.id) {
                "coordinator step cannot parent itself"
            }
            seen += step.id
        }
    }

    fun step(id: AgentCoordinatorStepId): AgentCoordinatorStep? =
        steps.firstOrNull { it.id == id }
}

data class AgentAggregateUsage(
    val wallClockMillis: Long = 0,
    val inferenceUnits: Long = 0,
    val contextBytes: Int = 0,
    val retrievalItems: Int = 0,
    val artifactCount: Int = 0,
    val agentsStarted: Int = 0
) {
    init {
        require(wallClockMillis >= 0)
        require(inferenceUnits >= 0)
        require(contextBytes >= 0)
        require(retrievalItems >= 0)
        require(artifactCount >= 0)
        require(agentsStarted >= 0)
    }

    fun startAgent(): AgentAggregateUsage =
        copy(agentsStarted = Math.addExact(agentsStarted, 1))

    fun plus(usage: AgentRuntimeUsage): AgentAggregateUsage =
        AgentAggregateUsage(
            wallClockMillis = Math.addExact(wallClockMillis, usage.wallClockMillis),
            inferenceUnits = Math.addExact(inferenceUnits, usage.inferenceUnits),
            contextBytes = Math.addExact(contextBytes, usage.contextBytes),
            retrievalItems = Math.addExact(retrievalItems, usage.retrievalItems),
            artifactCount = Math.addExact(artifactCount, usage.artifactCount),
            agentsStarted = agentsStarted
        )
}

data class AgentAggregateBudget(
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
        require(maxAgents <= AgentFactoryBounds.PROTOTYPE.maxAgentsPerRootTask)
    }

    fun allows(usage: AgentAggregateUsage): Boolean =
        usage.wallClockMillis <= maxWallClockMillis &&
            usage.inferenceUnits <= maxInferenceUnits &&
            usage.contextBytes <= maxContextBytes &&
            usage.retrievalItems <= maxRetrievalItems &&
            usage.artifactCount <= maxArtifacts &&
            usage.agentsStarted <= maxAgents
}

enum class AgentCoordinatorTerminalState {
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED,
    BUDGET_EXHAUSTED
}

data class AgentCoordinatorResult(
    val state: AgentCoordinatorTerminalState,
    val artifacts: List<AgentArtifact>,
    val terminalInstances: List<AgentInstance>,
    val aggregateUsage: AgentAggregateUsage,
    val completedSteps: Int
) {
    init {
        require(completedSteps >= 0)
        require(completedSteps <= terminalInstances.size)
        require(artifacts.all { artifact ->
            terminalInstances.any {
                it.id == artifact.producerId &&
                    it.generation == artifact.producerGeneration
            }
        }) { "coordinator artifact provenance must reference a terminal child instance" }
    }
}

internal fun AgentWorkBudget.intersect(other: AgentWorkBudget): AgentWorkBudget =
    AgentWorkBudget(
        maxWallClockMillis = minOf(maxWallClockMillis, other.maxWallClockMillis),
        maxInferenceUnits = minOf(maxInferenceUnits, other.maxInferenceUnits),
        maxContextBytes = minOf(maxContextBytes, other.maxContextBytes),
        maxRetrievalItems = minOf(maxRetrievalItems, other.maxRetrievalItems),
        maxArtifacts = minOf(maxArtifacts, other.maxArtifacts),
        maxDescendants = minOf(maxDescendants, other.maxDescendants)
    )

internal fun AgentAggregateBudget.remainingAsWorkBudget(
    usage: AgentAggregateUsage,
    descendantCap: Int
): AgentWorkBudget? {
    val wall = maxWallClockMillis - usage.wallClockMillis
    val inference = maxInferenceUnits - usage.inferenceUnits
    val context = maxContextBytes - usage.contextBytes
    val retrieval = maxRetrievalItems - usage.retrievalItems
    val artifacts = maxArtifacts - usage.artifactCount
    if (wall <= 0L || inference <= 0L || context <= 0 || retrieval < 0 || artifacts <= 0) return null
    return AgentWorkBudget(
        maxWallClockMillis = wall,
        maxInferenceUnits = inference,
        maxContextBytes = context,
        maxRetrievalItems = retrieval,
        maxArtifacts = artifacts,
        maxDescendants = maxOf(0, descendantCap)
    )
}

data class AgentCoordinatorRunWindow(
    val admittedAt: Instant,
    val expiresAt: Instant
) {
    init {
        require(expiresAt.isAfter(admittedAt)) {
            "coordinator run window must be positive"
        }
    }
}
