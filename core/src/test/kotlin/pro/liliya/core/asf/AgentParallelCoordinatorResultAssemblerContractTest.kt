package pro.liliya.core.asf

import java.time.Instant
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AgentParallelCoordinatorResultAssemblerContractTest {
    private val now = Instant.parse("2026-10-07T18:30:00Z")
    private val expires = Instant.parse("2026-10-07T18:35:00Z")
    private val scope = AgentCognitiveScope.create(listOf("analysis"))
    private val blueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "parallel-result",
        "bounded-result",
        scope
    )
    private val rootBudget = AgentWorkBudget(5_000, 5_000, 32_000, 8, 4, 4)
    private val childBudget = AgentWorkBudget(2_000, 2_000, 8_000, 2, 1, 0)
    private val globalBudget = AgentWorkBudget(30_000, 30_000, 128_000, 32, 16, 12)
    private val aggregate = AgentAggregateBudget(20_000, 20_000, 64_000, 16, 8, 4)

    @Test
    fun completed_parallel_execution_assembles_existing_coordinator_contract() {
        val run = runPlan(
            adapter = AgentRuntimeAdapter { context ->
                AgentRuntimeOutcome.Completed(
                    kind = "result",
                    payloadDigest = "sha256:" + context.instanceId.value,
                    provenanceReferences = context.workspace.inputReferences,
                    usage = AgentRuntimeUsage(3, 5, context.workspace.contextBytes, 0, 1)
                )
            }
        )

        val result = AgentParallelCoordinatorResultAssembler.assemble(
            plan = run.plan,
            execution = run.execution,
            taskFactory = run.tasks,
            aggregateBudget = aggregate
        )

        assertEquals(AgentCoordinatorTerminalState.COMPLETED, result.state)
        assertEquals(3, result.completedSteps)
        assertEquals(3, result.aggregateUsage.agentsStarted)
        assertEquals(15, result.aggregateUsage.inferenceUnits)
        assertEquals(3, result.artifacts.size)
        assertEquals(
            run.plan.steps.map { it.id.value },
            run.tasks.terminalsInPlanOrder().map { it.first.id.value }
        )
    }

    @Test
    fun one_failed_sibling_produces_partial_without_hiding_other_terminal_usage() {
        val run = runPlan(
            adapter = AgentRuntimeAdapter { context ->
                if ("evidence:child-a" in context.workspace.inputReferences) {
                    AgentRuntimeOutcome.Failed(
                        reason = "bounded failure",
                        usage = AgentRuntimeUsage(
                            2, 4, context.workspace.contextBytes, 0, 0
                        )
                    )
                } else {
                    AgentRuntimeOutcome.Completed(
                        kind = "result",
                        payloadDigest = "sha256:" + context.instanceId.value,
                        provenanceReferences = context.workspace.inputReferences,
                        usage = AgentRuntimeUsage(
                            2, 4, context.workspace.contextBytes, 0, 1
                        )
                    )
                }
            }
        )

        val result = AgentParallelCoordinatorResultAssembler.assemble(
            run.plan,
            run.execution,
            run.tasks,
            aggregate
        )

        assertEquals(AgentParallelPlanExecutionState.PARTIAL, run.execution.state)
        assertEquals(AgentCoordinatorTerminalState.PARTIAL, result.state)
        assertEquals(2, result.completedSteps)
        assertEquals(3, result.terminalInstances.size)
        assertEquals(3, result.aggregateUsage.agentsStarted)
        assertEquals(12, result.aggregateUsage.inferenceUnits)
        assertEquals(2, result.artifacts.size)
    }

    @Test
    fun cancellation_before_first_wave_maps_to_coordinator_cancelled() {
        val plan = plan()
        val snapshot = assertIs<AgentParallelAdmissionResult.Ready>(
            AgentParallelAdmissionReservation.reserve(plan)
        ).snapshot
        val tasks = AgentParallelFactoryTaskFactory(
            plan = plan,
            snapshot = snapshot,
            factory = factory(
                AgentRuntimeAdapter { error("cancelled plan must not run") }
            ),
            runWindow = AgentCoordinatorRunWindow(now, expires),
            cancellationRequested = { true }
        )
        val pool = Executors.newFixedThreadPool(2)

        try {
            val execution = AgentParallelPlanExecutor(
                AgentParallelWaveExecutor(pool),
                tasks
            ).execute(
                plan = plan,
                aggregateBudget = aggregate,
                timeoutPerWaveMillis = 2_000,
                cancelled = { true }
            )

            val result = AgentParallelCoordinatorResultAssembler.assemble(
                plan,
                execution,
                tasks,
                aggregate
            )

            assertEquals(AgentParallelPlanExecutionState.CANCELLED, execution.state)
            assertEquals(AgentCoordinatorTerminalState.CANCELLED, result.state)
            assertEquals(0, result.aggregateUsage.agentsStarted)
            assertEquals(0, result.completedSteps)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun late_terminal_after_wave_timeout_is_not_published_into_coordinator_result() {
        val one = AgentCoordinatorPlan(
            AgentRootTaskId("parallel-late-root"),
            listOf(
                AgentCoordinatorStep.create(
                    id = AgentCoordinatorStepId("root"),
                    parentStepId = null,
                    blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
                    cognitiveScope = scope,
                    budget = childBudget,
                    inputReferences = listOf("evidence:root")
                )
            )
        )
        val snapshot = assertIs<AgentParallelAdmissionResult.Ready>(
            AgentParallelAdmissionReservation.reserve(one)
        ).snapshot
        val tasks = AgentParallelFactoryTaskFactory(
            plan = one,
            snapshot = snapshot,
            factory = factory(
                AgentRuntimeAdapter { context ->
                    try {
                        Thread.sleep(10_000)
                    } catch (_: InterruptedException) {
                        // Simulate a provider that finishes after Future cancellation.
                    }
                    AgentRuntimeOutcome.Completed(
                        kind = "late-result",
                        payloadDigest = "sha256:late",
                        provenanceReferences = context.workspace.inputReferences,
                        usage = AgentRuntimeUsage(
                            1, 1, context.workspace.contextBytes, 0, 1
                        )
                    )
                }
            ),
            runWindow = AgentCoordinatorRunWindow(now, expires)
        )
        val pool = Executors.newFixedThreadPool(1)

        try {
            val execution = AgentParallelPlanExecutor(
                AgentParallelWaveExecutor(pool),
                tasks
            ).execute(
                plan = one,
                aggregateBudget = aggregate,
                timeoutPerWaveMillis = 25
            )

            repeat(100) {
                if (tasks.terminal(AgentCoordinatorStepId("root")) != null) return@repeat
                Thread.sleep(5)
            }
            kotlin.test.assertNotNull(tasks.terminal(AgentCoordinatorStepId("root")))

            val result = AgentParallelCoordinatorResultAssembler.assemble(
                one,
                execution,
                tasks,
                aggregate
            )

            assertEquals(AgentParallelPlanExecutionState.TIMED_OUT, execution.state)
            assertEquals(AgentCoordinatorTerminalState.FAILED, result.state)
            assertEquals(0, result.completedSteps)
            assertEquals(0, result.aggregateUsage.agentsStarted)
            assertEquals(0, result.terminalInstances.size)
            assertEquals(0, result.artifacts.size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun factory_budget_exhaustion_maps_to_coordinator_budget_exhausted() {
        val one = AgentCoordinatorPlan(
            AgentRootTaskId("parallel-budget-root"),
            listOf(
                AgentCoordinatorStep.create(
                    id = AgentCoordinatorStepId("root"),
                    parentStepId = null,
                    blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
                    cognitiveScope = scope,
                    budget = childBudget,
                    inputReferences = listOf("evidence:root")
                )
            )
        )
        val snapshot = assertIs<AgentParallelAdmissionResult.Ready>(
            AgentParallelAdmissionReservation.reserve(one)
        ).snapshot
        val tasks = AgentParallelFactoryTaskFactory(
            plan = one,
            snapshot = snapshot,
            factory = factory(
                AgentRuntimeAdapter { context ->
                    AgentRuntimeOutcome.Completed(
                        kind = "over-budget",
                        payloadDigest = "sha256:over-budget",
                        provenanceReferences = context.workspace.inputReferences,
                        usage = AgentRuntimeUsage(
                            wallClockMillis = 1,
                            inferenceUnits = childBudget.maxInferenceUnits + 1,
                            contextBytes = context.workspace.contextBytes,
                            retrievalItems = 0,
                            artifactCount = 1
                        )
                    )
                }
            ),
            runWindow = AgentCoordinatorRunWindow(now, expires)
        )
        val pool = Executors.newFixedThreadPool(1)

        try {
            val execution = AgentParallelPlanExecutor(
                AgentParallelWaveExecutor(pool),
                tasks
            ).execute(one, aggregate, 2_000)

            val result = AgentParallelCoordinatorResultAssembler.assemble(
                one,
                execution,
                tasks,
                aggregate
            )

            assertEquals(AgentCoordinatorTerminalState.BUDGET_EXHAUSTED, result.state)
            assertEquals(1, result.aggregateUsage.agentsStarted)
            assertEquals(0, result.completedSteps)
            assertEquals(0, result.artifacts.size)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun runPlan(
        adapter: AgentRuntimeAdapter
    ): ParallelRun {
        val plan = plan()
        val snapshot = assertIs<AgentParallelAdmissionResult.Ready>(
            AgentParallelAdmissionReservation.reserve(plan)
        ).snapshot
        val tasks = AgentParallelFactoryTaskFactory(
            plan = plan,
            snapshot = snapshot,
            factory = factory(adapter),
            runWindow = AgentCoordinatorRunWindow(now, expires)
        )
        val pool = Executors.newFixedThreadPool(2)
        return try {
            val execution = AgentParallelPlanExecutor(
                AgentParallelWaveExecutor(pool),
                tasks
            ).execute(plan, aggregate, 2_000)
            ParallelRun(plan, tasks, execution)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun plan() =
        AgentCoordinatorPlan(
            AgentRootTaskId("parallel-result-root"),
            listOf(
                step("root", null, rootBudget, false),
                step("child-a", "root", childBudget, true),
                step("child-b", "root", childBudget, true)
            )
        )

    private fun step(
        id: String,
        parent: String?,
        budget: AgentWorkBudget,
        includeParentArtifact: Boolean
    ) = AgentCoordinatorStep.create(
        id = AgentCoordinatorStepId(id),
        parentStepId = parent?.let(::AgentCoordinatorStepId),
        blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
        cognitiveScope = scope,
        budget = budget,
        inputReferences = listOf("evidence:$id"),
        includeParentArtifact = includeParentArtifact
    )

    private fun factory(
        adapter: AgentRuntimeAdapter
    ) = AgentFactory(
        registry = AgentBlueprintRegistry(listOf(blueprint)),
        admissionPolicy = AgentAdmissionPolicy(
            AgentFactoryBounds.PROTOTYPE,
            scope,
            globalBudget
        ),
        runtimeAdapter = adapter,
        auditLedger = AgentAuditLedger { },
        timeSource = { now }
    )

    private data class ParallelRun(
        val plan: AgentCoordinatorPlan,
        val tasks: AgentParallelFactoryTaskFactory,
        val execution: AgentParallelPlanExecutionResult
    )
}
