package pro.liliya.core.asf

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentParallelCoordinatorContractTest {
    private val now = Instant.parse("2026-10-07T18:30:00Z")
    private val expires = Instant.parse("2026-10-07T18:40:00Z")
    private val rootTask = AgentRootTaskId("parallel-coordinator-root")
    private val broadScope = AgentCognitiveScope.create(listOf("analysis", "verification"))
    private val verifyScope = AgentCognitiveScope.create(listOf("verification"))

    private val blueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "parallel-coordinator-worker",
        "bounded-parallel-coordination",
        broadScope
    )

    private val nanoBudget = AgentWorkBudget(2_000, 2_000, 8_000, 2, 1, 0)
    private val microBudget = AgentWorkBudget(4_000, 4_000, 16_000, 4, 1, 0)
    private val fullBudget = AgentWorkBudget(10_000, 10_000, 64_000, 16, 4, 4)

    private val nanoRuntime = AgentWorkerRuntimeDescriptor(
        "parallel-nano-v1",
        AgentWorkerRuntimeKind.DETERMINISTIC
    )
    private val microRuntime = AgentWorkerRuntimeDescriptor(
        "parallel-micro-v1",
        AgentWorkerRuntimeKind.LLM,
        "parallel-micro-model"
    )
    private val fullRuntime = AgentWorkerRuntimeDescriptor(
        "parallel-full-v1",
        AgentWorkerRuntimeKind.LLM,
        "parallel-full-model"
    )

    private val aggregate = AgentAggregateBudget(
        maxWallClockMillis = 20_000,
        maxInferenceUnits = 20_000,
        maxContextBytes = 128_000,
        maxRetrievalItems = 32,
        maxArtifacts = 8,
        maxAgents = 4
    )

    @Test
    fun root_commits_then_distinct_runtime_siblings_overlap_and_publish_canonically() {
        val childStarted = CountDownLatch(2)
        val childRelease = CountDownLatch(1)
        val childOverlapObserved = java.util.concurrent.atomic.AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(2)

        try {
            val coordinator = coordinator(
                pool = pool,
                adapter = AgentRuntimeAdapter { context ->
                    val runtime = requireNotNull(context.workerRuntime)
                    if (runtime == microRuntime || runtime == nanoRuntime) {
                        childStarted.countDown()
                        if (childStarted.await(1, TimeUnit.SECONDS)) {
                            childOverlapObserved.set(true)
                        }
                        childRelease.await(1, TimeUnit.SECONDS)
                    }
                    completed(context)
                }
            )

            val releaser = Thread {
                if (childStarted.await(1, TimeUnit.SECONDS)) {
                    childRelease.countDown()
                }
            }.apply { start() }

            val result = coordinator.runParallel(
                plan = plan(),
                runWindow = AgentCoordinatorRunWindow(now, expires)
            )

            releaser.join(1_000)

            assertEquals(AgentCoordinatorTerminalState.COMPLETED, result.state)
            assertEquals(3, result.completedSteps)
            assertEquals(3, result.terminalInstances.size)
            assertEquals(3, result.artifacts.size)
            assertEquals(3, result.aggregateUsage.agentsStarted)
            assertTrue(childOverlapObserved.get())
            assertEquals(1, result.workerAggregateUsage.full.agentsStarted)
            assertEquals(1, result.workerAggregateUsage.micro.agentsStarted)
            assertEquals(1, result.workerAggregateUsage.nano.agentsStarted)

            val rootInstance = result.terminalInstances.first()
            assertTrue(
                result.terminalInstances.drop(1).all {
                    it.provenance.parentAgentId == rootInstance.id &&
                        it.provenance.parentGeneration == rootInstance.generation
                }
            )
            assertTrue(
                result.artifacts.drop(1).all { artifact ->
                    artifact.provenanceReferences.any {
                        it.startsWith("asf-artifact:")
                    }
                }
            )
        } finally {
            childRelease.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun sibling_failure_returns_partial_without_launching_dependency_after_failed_wave() {
        val runtimeCalls = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val coordinator = coordinator(
                pool = pool,
                adapter = AgentRuntimeAdapter { context ->
                    runtimeCalls.incrementAndGet()
                    if (context.workerRuntime == microRuntime) {
                        AgentRuntimeOutcome.Failed(
                            "bounded child failure",
                            AgentRuntimeUsage(10, 10, 10, 0, 0)
                        )
                    } else {
                        completed(context)
                    }
                }
            )

            val result = coordinator.runParallel(
                plan = plan(),
                runWindow = AgentCoordinatorRunWindow(now, expires)
            )

            assertEquals(AgentCoordinatorTerminalState.PARTIAL, result.state)
            assertEquals(3, runtimeCalls.get())
            assertEquals(2, result.completedSteps)
            assertEquals(3, result.terminalInstances.size)
            assertEquals(2, result.artifacts.size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun cancellation_during_sibling_wave_does_not_publish_late_child_results() {
        val childStarted = CountDownLatch(2)
        val holdChildren = CountDownLatch(1)
        val cancel = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val coordinator = coordinator(
                pool = pool,
                adapter = AgentRuntimeAdapter { context ->
                    if (context.workerRuntime == microRuntime || context.workerRuntime == nanoRuntime) {
                        childStarted.countDown()
                        try {
                            holdChildren.await(1, TimeUnit.SECONDS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                    completed(context)
                }
            )

            val canceller = Thread {
                if (childStarted.await(1, TimeUnit.SECONDS)) {
                    cancel.set(true)
                }
            }.apply { start() }

            val result = coordinator.runParallel(
                plan = plan(),
                runWindow = AgentCoordinatorRunWindow(now, expires),
                cancelled = cancel::get
            )

            canceller.join(1_000)
            holdChildren.countDown()

            assertEquals(AgentCoordinatorTerminalState.PARTIAL, result.state)
            assertEquals(1, result.completedSteps)
            assertEquals(1, result.terminalInstances.size)
            assertEquals(1, result.artifacts.size)
            assertEquals(1, result.aggregateUsage.agentsStarted)
        } finally {
            cancel.set(true)
            holdChildren.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun timeout_during_sibling_wave_does_not_publish_late_child_results() {
        val childStarted = CountDownLatch(2)
        val holdChildren = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val coordinator = coordinator(
                pool = pool,
                adapter = AgentRuntimeAdapter { context ->
                    if (context.workerRuntime == microRuntime || context.workerRuntime == nanoRuntime) {
                        childStarted.countDown()
                        try {
                            holdChildren.await(1, TimeUnit.SECONDS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                    completed(context)
                },
                coordinatorTimeSource = { expires.minusMillis(75) }
            )

            val result = coordinator.runParallel(
                plan = plan(),
                runWindow = AgentCoordinatorRunWindow(now, expires)
            )

            holdChildren.countDown()

            assertEquals(AgentCoordinatorTerminalState.PARTIAL, result.state)
            assertEquals(1, result.completedSteps)
            assertEquals(1, result.terminalInstances.size)
            assertEquals(1, result.artifacts.size)
            assertEquals(1, result.aggregateUsage.agentsStarted)
        } finally {
            holdChildren.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun reverse_sibling_completion_still_publishes_canonical_terminal_order() {
        val childARelease = CountDownLatch(1)
        val childBFinished = CountDownLatch(1)
        val instanceByRuntime = java.util.concurrent.ConcurrentHashMap<String, AgentInstanceId>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val coordinator = coordinator(
                pool = pool,
                adapter = AgentRuntimeAdapter { context ->
                    val runtime = requireNotNull(context.workerRuntime)
                    instanceByRuntime[runtime.runtimeId] = context.instanceId
                    if (runtime == nanoRuntime) {
                        childBFinished.await(1, TimeUnit.SECONDS)
                        childARelease.await(1, TimeUnit.SECONDS)
                    } else if (runtime == microRuntime) {
                        childBFinished.countDown()
                    }
                    completed(context)
                }
            )

            val result = coordinator.runParallel(
                plan = plan(),
                runWindow = AgentCoordinatorRunWindow(now, expires)
            )
            childARelease.countDown()

            assertEquals(AgentCoordinatorTerminalState.COMPLETED, result.state)
            assertEquals(
                listOf(
                    instanceByRuntime.getValue(fullRuntime.runtimeId),
                    instanceByRuntime.getValue(nanoRuntime.runtimeId),
                    instanceByRuntime.getValue(microRuntime.runtimeId)
                ),
                result.terminalInstances.map { it.id }
            )
        } finally {
            childARelease.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun active_population_preflight_rejects_before_any_runtime_call() {
        val runtimeCalls = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val coordinator = coordinator(
                pool = pool,
                adapter = AgentRuntimeAdapter { context ->
                    runtimeCalls.incrementAndGet()
                    completed(context)
                }
            )

            val result = coordinator.runParallel(
                plan = plan(),
                runWindow = AgentCoordinatorRunWindow(now, expires),
                baselineActiveAgents = AgentFactoryBounds.PROTOTYPE.maxActiveAgents - 1
            )

            assertEquals(AgentCoordinatorTerminalState.FAILED, result.state)
            assertEquals(0, runtimeCalls.get())
            assertEquals(0, result.completedSteps)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun plan() =
        AgentCoordinatorPlan(
            rootTaskId = rootTask,
            steps = listOf(
                step(
                    id = "root",
                    parent = null,
                    scope = broadScope,
                    budget = fullBudget,
                    workerClass = AgentWorkerClass.FULL,
                    runtime = fullRuntime,
                    includeParentArtifact = false
                ),
                step(
                    id = "child-a",
                    parent = "root",
                    scope = verifyScope,
                    budget = nanoBudget,
                    workerClass = AgentWorkerClass.NANO,
                    runtime = nanoRuntime,
                    includeParentArtifact = true
                ),
                step(
                    id = "child-b",
                    parent = "root",
                    scope = broadScope,
                    budget = microBudget,
                    workerClass = AgentWorkerClass.MICRO,
                    runtime = microRuntime,
                    includeParentArtifact = true
                )
            )
        )

    private fun step(
        id: String,
        parent: String?,
        scope: AgentCognitiveScope,
        budget: AgentWorkBudget,
        workerClass: AgentWorkerClass,
        runtime: AgentWorkerRuntimeDescriptor,
        includeParentArtifact: Boolean
    ) = AgentCoordinatorStep.create(
        id = AgentCoordinatorStepId(id),
        parentStepId = parent?.let(::AgentCoordinatorStepId),
        blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
        cognitiveScope = scope,
        budget = budget,
        inputReferences = listOf("evidence:$id"),
        includeParentArtifact = includeParentArtifact,
        workerClass = workerClass,
        runtime = runtime
    )

    private fun completed(
        context: AgentRuntimeContext
    ) = AgentRuntimeOutcome.Completed(
        kind = "parallel-result",
        payloadDigest = "sha256:" + context.instanceId.value,
        provenanceReferences = context.workspace.inputReferences,
        usage = AgentRuntimeUsage(10, 10, 10, 0, 1)
    )

    private fun coordinator(
        pool: java.util.concurrent.ExecutorService,
        adapter: AgentRuntimeAdapter,
        coordinatorTimeSource: () -> Instant = { now }
    ): AgentParallelCoordinator {
        val profiles = AgentWorkerProfileSet(
            listOf(
                AgentWorkerProfile(AgentWorkerClass.NANO, nanoBudget, 0, false),
                AgentWorkerProfile(AgentWorkerClass.MICRO, microBudget, 1, false),
                AgentWorkerProfile(AgentWorkerClass.FULL, fullBudget, 1, true)
            )
        )
        val factory = AgentFactory(
            registry = AgentBlueprintRegistry(listOf(blueprint)),
            admissionPolicy = AgentAdmissionPolicy(
                bounds = AgentFactoryBounds.PROTOTYPE,
                globalScope = broadScope,
                globalBudget = fullBudget.copy(maxDescendants = 8)
            ),
            runtimeAdapter = adapter,
            auditLedger = AgentAuditLedger { },
            timeSource = { now }
        )
        return AgentParallelCoordinator(
            factory = factory,
            aggregateBudget = aggregate,
            bounds = AgentFactoryBounds.PROTOTYPE,
            waveExecutor = AgentParallelWaveExecutor(pool),
            workerFactory = AgentWorkerFactory(
                AgentWorkerAdmissionPolicy(profiles),
                factory
            ),
            timeSource = coordinatorTimeSource
        )
    }
}
