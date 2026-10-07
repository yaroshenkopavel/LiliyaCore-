package pro.liliya.core.asf

import java.time.Instant
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentParallelFactoryTaskFactoryContractTest {
    private val now = Instant.parse("2026-10-07T18:00:00Z")
    private val expires = Instant.parse("2026-10-07T18:05:00Z")
    private val scope = AgentCognitiveScope.create(listOf("analysis"))
    private val blueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "parallel-factory",
        "bounded-analysis",
        scope
    )
    private val rootBudget = AgentWorkBudget(5_000, 5_000, 32_000, 8, 4, 4)
    private val childBudget = AgentWorkBudget(2_000, 2_000, 8_000, 2, 1, 0)
    private val globalBudget = AgentWorkBudget(30_000, 30_000, 128_000, 32, 16, 12)
    private val aggregate = AgentAggregateBudget(20_000, 20_000, 64_000, 16, 8, 4)

    @Test
    fun parallel_factory_path_preserves_parent_provenance_and_artifact_input() {
        val plan = plan()
        val snapshot = assertIs<AgentParallelAdmissionResult.Ready>(
            AgentParallelAdmissionReservation.reserve(plan)
        ).snapshot
        val contexts = Collections.synchronizedList(mutableListOf<AgentRuntimeContext>())
        val factory = factory(
            AgentRuntimeAdapter { context ->
                contexts += context
                AgentRuntimeOutcome.Completed(
                    kind = "parallel-result",
                    payloadDigest = "sha256:" + context.instanceId.value,
                    provenanceReferences = context.workspace.inputReferences,
                    usage = AgentRuntimeUsage(1, 1, context.workspace.contextBytes, 0, 1)
                )
            }
        )
        val tasks = AgentParallelFactoryTaskFactory(
            plan = plan,
            snapshot = snapshot,
            factory = factory,
            runWindow = AgentCoordinatorRunWindow(now, expires)
        )
        val pool = Executors.newFixedThreadPool(2)

        try {
            val result = AgentParallelPlanExecutor(
                waveExecutor = AgentParallelWaveExecutor(pool),
                taskFactory = tasks
            ).execute(
                plan = plan,
                aggregateBudget = aggregate,
                timeoutPerWaveMillis = 2_000
            )

            assertEquals(AgentParallelPlanExecutionState.COMPLETED, result.state)
            assertEquals(2, result.completedWaves)

            val terminals = tasks.terminalsInPlanOrder()
            assertEquals(
                listOf("root", "child-a", "child-b"),
                terminals.map { it.first.id.value }
            )

            val rootTerminal = assertNotNull(tasks.terminal(AgentCoordinatorStepId("root")))
            val childA = assertNotNull(tasks.terminal(AgentCoordinatorStepId("child-a")))
            val childB = assertNotNull(tasks.terminal(AgentCoordinatorStepId("child-b")))

            assertEquals(rootTerminal.instance.id, childA.instance.provenance.parentAgentId)
            assertEquals(rootTerminal.instance.id, childB.instance.provenance.parentAgentId)
            assertEquals(1, childA.instance.provenance.depth)
            assertEquals(1, childB.instance.provenance.depth)

            val childContexts = contexts.filter { it.instanceId != rootTerminal.instance.id }
            assertEquals(2, childContexts.size)
            childContexts.forEach { context ->
                assertTrue(
                    context.workspace.inputReferences.any { it.startsWith("asf-artifact:") }
                )
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun worker_profile_path_delegates_through_agent_worker_factory() {
        val runtime = AgentWorkerRuntimeDescriptor(
            runtimeId = "parallel-nano-v1",
            kind = AgentWorkerRuntimeKind.DETERMINISTIC
        )
        val workerBudget = childBudget.copy(maxDescendants = 0)
        val workerPlan = AgentCoordinatorPlan(
            rootTaskId = AgentRootTaskId("parallel-worker-root"),
            steps = listOf(
                AgentCoordinatorStep.create(
                    id = AgentCoordinatorStepId("root"),
                    parentStepId = null,
                    blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
                    cognitiveScope = scope,
                    budget = workerBudget,
                    inputReferences = listOf("evidence:worker"),
                    workerClass = AgentWorkerClass.NANO,
                    runtime = runtime
                )
            )
        )
        val snapshot = assertIs<AgentParallelAdmissionResult.Ready>(
            AgentParallelAdmissionReservation.reserve(workerPlan)
        ).snapshot
        var observedRuntime: AgentWorkerRuntimeDescriptor? = null
        val delegate = factory(
            AgentRuntimeAdapter { context ->
                observedRuntime = context.workerRuntime
                AgentRuntimeOutcome.Completed(
                    kind = "worker-result",
                    payloadDigest = "sha256:worker",
                    provenanceReferences = context.workspace.inputReferences,
                    usage = AgentRuntimeUsage(1, 1, context.workspace.contextBytes, 0, 1)
                )
            }
        )
        val workerFactory = AgentWorkerFactory(
            admissionPolicy = AgentWorkerAdmissionPolicy(
                AgentWorkerProfileSet(
                    listOf(
                        AgentWorkerProfile(
                            workerClass = AgentWorkerClass.NANO,
                            budgetCeiling = workerBudget,
                            maxRetryPerLogicalRole = 0,
                            protectedToolViewAllowed = false
                        )
                    )
                )
            ),
            delegate = delegate
        )
        val tasks = AgentParallelFactoryTaskFactory(
            plan = workerPlan,
            snapshot = snapshot,
            factory = delegate,
            workerFactory = workerFactory,
            runWindow = AgentCoordinatorRunWindow(now, expires)
        )
        val pool = Executors.newFixedThreadPool(1)

        try {
            val result = AgentParallelPlanExecutor(
                AgentParallelWaveExecutor(pool),
                tasks
            ).execute(
                plan = workerPlan,
                aggregateBudget = aggregate,
                timeoutPerWaveMillis = 2_000
            )

            assertEquals(AgentParallelPlanExecutionState.COMPLETED, result.state)
            assertEquals(runtime, observedRuntime)
            assertNotNull(tasks.terminal(AgentCoordinatorStepId("root")))
        } finally {
            pool.shutdownNow()
        }
    }

    private fun plan(): AgentCoordinatorPlan =
        AgentCoordinatorPlan(
            rootTaskId = AgentRootTaskId("parallel-factory-root"),
            steps = listOf(
                AgentCoordinatorStep.create(
                    id = AgentCoordinatorStepId("root"),
                    parentStepId = null,
                    blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
                    cognitiveScope = scope,
                    budget = rootBudget,
                    inputReferences = listOf("evidence:root")
                ),
                child("child-a"),
                child("child-b")
            )
        )

    private fun child(
        id: String
    ) = AgentCoordinatorStep.create(
        id = AgentCoordinatorStepId(id),
        parentStepId = AgentCoordinatorStepId("root"),
        blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
        cognitiveScope = scope,
        budget = childBudget,
        inputReferences = listOf("evidence:$id"),
        includeParentArtifact = true
    )

    private fun factory(
        adapter: AgentRuntimeAdapter
    ): AgentFactory =
        AgentFactory(
            registry = AgentBlueprintRegistry(listOf(blueprint)),
            admissionPolicy = AgentAdmissionPolicy(
                bounds = AgentFactoryBounds.PROTOTYPE,
                globalScope = scope,
                globalBudget = globalBudget
            ),
            runtimeAdapter = adapter,
            auditLedger = AgentAuditLedger { },
            timeSource = { now }
        )
}
