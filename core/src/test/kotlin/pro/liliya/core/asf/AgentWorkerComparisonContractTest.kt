package pro.liliya.core.asf

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentWorkerComparisonContractTest {
    private val now = Instant.parse("2026-09-30T12:30:00Z")
    private val expires = Instant.parse("2026-09-30T12:40:00Z")
    private val rootTask = AgentRootTaskId("root-asf-h-comparison")
    private val scope = AgentCognitiveScope.create(listOf("verification"))

    private val blueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "comparison-worker",
        "bounded-verification",
        scope
    )

    private val nanoBudget = AgentWorkBudget(5_000, 2_000, 16_000, 4, 1, 0)
    private val microBudget = AgentWorkBudget(30_000, 20_000, 128_000, 16, 4, 0)
    private val fullBudget = AgentWorkBudget(120_000, 100_000, 1_000_000, 128, 32, 2)
    private val fullLeafBudget = fullBudget.copy(maxDescendants = 0)

    private val profiles = AgentWorkerProfileSet(
        listOf(
            AgentWorkerProfile(AgentWorkerClass.NANO, nanoBudget, 0, false),
            AgentWorkerProfile(AgentWorkerClass.MICRO, microBudget, 1, false),
            AgentWorkerProfile(AgentWorkerClass.FULL, fullBudget, 1, true)
        )
    )

    private val fullRuntime = AgentWorkerRuntimeDescriptor(
        "full-comparison-v1",
        AgentWorkerRuntimeKind.LLM,
        "bounded-test-model"
    )
    private val microRuntime = AgentWorkerRuntimeDescriptor(
        "micro-comparison-v1",
        AgentWorkerRuntimeKind.LLM,
        "bounded-test-model"
    )
    private val nanoRuntime = AgentWorkerRuntimeDescriptor(
        "nano-comparison-v1",
        AgentWorkerRuntimeKind.DETERMINISTIC
    )

    @Test
    fun hierarchical_workers_preserve_quality_contract_with_lower_modeled_resource_vector() {
        val fullOnly = coordinator().runSequential(
            plan(
                verifierClass = AgentWorkerClass.FULL,
                verifierRuntime = fullRuntime,
                verifierBudget = fullLeafBudget,
                atomicClass = AgentWorkerClass.FULL,
                atomicRuntime = fullRuntime,
                atomicBudget = fullLeafBudget
            ),
            AgentCoordinatorRunWindow(now, expires)
        )

        val hierarchical = coordinator().runSequential(
            plan(
                verifierClass = AgentWorkerClass.MICRO,
                verifierRuntime = microRuntime,
                verifierBudget = microBudget,
                atomicClass = AgentWorkerClass.NANO,
                atomicRuntime = nanoRuntime,
                atomicBudget = nanoBudget
            ),
            AgentCoordinatorRunWindow(now, expires)
        )

        val fullQuality = qualityVector(fullOnly)
        val hierarchicalQuality = qualityVector(hierarchical)

        assertEquals(AgentCoordinatorTerminalState.COMPLETED, fullOnly.state)
        assertEquals(AgentCoordinatorTerminalState.COMPLETED, hierarchical.state)
        assertEquals(fullQuality, hierarchicalQuality)

        assertTrue(
            hierarchical.aggregateUsage.wallClockMillis < fullOnly.aggregateUsage.wallClockMillis,
            "hierarchical routing should reduce modeled wall-clock work"
        )
        assertTrue(
            hierarchical.aggregateUsage.inferenceUnits < fullOnly.aggregateUsage.inferenceUnits,
            "hierarchical routing should reduce modeled inference work"
        )
        assertTrue(
            hierarchical.aggregateUsage.contextBytes < fullOnly.aggregateUsage.contextBytes,
            "hierarchical routing should reduce modeled context consumption"
        )
        assertTrue(
            hierarchical.aggregateUsage.retrievalItems <= fullOnly.aggregateUsage.retrievalItems,
            "hierarchical routing must not increase modeled retrieval consumption"
        )
        assertEquals(fullOnly.aggregateUsage.artifactCount, hierarchical.aggregateUsage.artifactCount)
        assertEquals(fullOnly.aggregateUsage.agentsStarted, hierarchical.aggregateUsage.agentsStarted)

        assertEquals(3, fullOnly.workerAggregateUsage.full.agentsStarted)
        assertEquals(0, fullOnly.workerAggregateUsage.micro.agentsStarted)
        assertEquals(0, fullOnly.workerAggregateUsage.nano.agentsStarted)

        assertEquals(1, hierarchical.workerAggregateUsage.full.agentsStarted)
        assertEquals(1, hierarchical.workerAggregateUsage.micro.agentsStarted)
        assertEquals(1, hierarchical.workerAggregateUsage.nano.agentsStarted)

        assertEquals(fullOnly.aggregateUsage, fullOnly.workerAggregateUsage.total())
        assertEquals(hierarchical.aggregateUsage, hierarchical.workerAggregateUsage.total())
    }

    private fun plan(
        verifierClass: AgentWorkerClass,
        verifierRuntime: AgentWorkerRuntimeDescriptor,
        verifierBudget: AgentWorkBudget,
        atomicClass: AgentWorkerClass,
        atomicRuntime: AgentWorkerRuntimeDescriptor,
        atomicBudget: AgentWorkBudget
    ) = AgentCoordinatorPlan(
        rootTask,
        listOf(
            step(
                id = "broad-root",
                parent = null,
                workerClass = AgentWorkerClass.FULL,
                runtime = fullRuntime,
                budget = fullBudget,
                input = "evidence:broad"
            ),
            step(
                id = "narrow-verify",
                parent = "broad-root",
                workerClass = verifierClass,
                runtime = verifierRuntime,
                budget = verifierBudget,
                input = "evidence:narrow"
            ),
            step(
                id = "atomic-check",
                parent = "broad-root",
                workerClass = atomicClass,
                runtime = atomicRuntime,
                budget = atomicBudget,
                input = "evidence:atomic"
            )
        )
    )

    private fun step(
        id: String,
        parent: String?,
        workerClass: AgentWorkerClass,
        runtime: AgentWorkerRuntimeDescriptor,
        budget: AgentWorkBudget,
        input: String
    ) = AgentCoordinatorStep.create(
        id = AgentCoordinatorStepId(id),
        parentStepId = parent?.let(::AgentCoordinatorStepId),
        blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
        cognitiveScope = scope,
        budget = budget,
        inputReferences = listOf(input),
        workerClass = workerClass,
        runtime = runtime
    )

    private fun coordinator(): AgentCoordinator {
        val delegate = AgentFactory(
            registry = AgentBlueprintRegistry(listOf(blueprint)),
            admissionPolicy = AgentAdmissionPolicy(
                AgentFactoryBounds.PROTOTYPE,
                scope,
                fullBudget.copy(maxDescendants = 12)
            ),
            runtimeAdapter = comparisonAdapter(),
            auditLedger = AgentAuditLedger { },
            timeSource = { now }
        )
        return AgentCoordinator(
            factory = delegate,
            aggregateBudget = AgentAggregateBudget(
                maxWallClockMillis = 500_000,
                maxInferenceUnits = 500_000,
                maxContextBytes = 4_000_000,
                maxRetrievalItems = 512,
                maxArtifacts = 64,
                maxAgents = 8
            ),
            workerFactory = AgentWorkerFactory(
                AgentWorkerAdmissionPolicy(profiles),
                delegate
            )
        )
    }

    private fun comparisonAdapter() = AgentRuntimeAdapter { context ->
        val input = context.workspace.inputReferences.single()
        val usage = when (context.budget.maxInferenceUnits) {
            fullBudget.maxInferenceUnits -> AgentRuntimeUsage(
                wallClockMillis = 120,
                inferenceUnits = 1_000,
                contextBytes = 4_000,
                retrievalItems = 4,
                artifactCount = 1
            )
            microBudget.maxInferenceUnits -> AgentRuntimeUsage(
                wallClockMillis = 40,
                inferenceUnits = 250,
                contextBytes = 1_000,
                retrievalItems = 2,
                artifactCount = 1
            )
            nanoBudget.maxInferenceUnits -> AgentRuntimeUsage(
                wallClockMillis = 8,
                inferenceUnits = 25,
                contextBytes = 128,
                retrievalItems = 0,
                artifactCount = 1
            )
            else -> error("unexpected comparison budget")
        }
        AgentRuntimeOutcome.Completed(
            kind = "comparison-result",
            payloadDigest = "sha256:$input",
            provenanceReferences = context.workspace.inputReferences,
            usage = usage
        )
    }

    private fun qualityVector(result: AgentCoordinatorResult) = QualityVector(
        completedSteps = result.completedSteps,
        artifactKinds = result.artifacts.map { it.kind },
        payloadDigests = result.artifacts.map { it.payloadDigest },
        provenance = result.artifacts.map { it.provenanceReferences },
        rootTaskIds = result.artifacts.map { it.rootTaskId }
    )

    private data class QualityVector(
        val completedSteps: Int,
        val artifactKinds: List<String>,
        val payloadDigests: List<String>,
        val provenance: List<List<String>>,
        val rootTaskIds: List<AgentRootTaskId>
    )
}
