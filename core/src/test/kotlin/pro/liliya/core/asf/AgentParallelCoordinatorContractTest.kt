package pro.liliya.core.asf

import java.time.Instant
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentParallelCoordinatorContractTest {
    private val now = Instant.parse("2026-10-07T19:00:00Z")
    private val expires = Instant.parse("2026-10-07T19:05:00Z")
    private val scope = AgentCognitiveScope.create(listOf("analysis"))
    private val blueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "parallel-coordinator",
        "bounded-coordination",
        scope
    )
    private val rootBudget = AgentWorkBudget(5_000, 5_000, 32_000, 8, 4, 4)
    private val childBudget = AgentWorkBudget(2_000, 2_000, 8_000, 2, 1, 0)
    private val globalBudget = AgentWorkBudget(30_000, 30_000, 128_000, 32, 16, 12)
    private val aggregate = AgentAggregateBudget(20_000, 20_000, 64_000, 16, 8, 4)

    @Test
    fun bounded_parallel_plan_returns_existing_coordinator_result_contract() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val coordinator = AgentParallelCoordinator(
                factory = factory(
                    AgentRuntimeAdapter { context ->
                        AgentRuntimeOutcome.Completed(
                            kind = "parallel-result",
                            payloadDigest = "sha256:" + context.instanceId.value,
                            provenanceReferences = context.workspace.inputReferences,
                            usage = AgentRuntimeUsage(
                                2, 3, context.workspace.contextBytes, 0, 1
                            )
                        )
                    }
                ),
                aggregateBudget = aggregate,
                executor = pool
            )

            val result = coordinator.run(
                plan = plan(),
                runWindow = AgentCoordinatorRunWindow(now, expires),
                timeoutPerWaveMillis = 2_000
            )

            assertEquals(AgentCoordinatorTerminalState.COMPLETED, result.state)
            assertEquals(3, result.completedSteps)
            assertEquals(3, result.aggregateUsage.agentsStarted)
            assertEquals(9, result.aggregateUsage.inferenceUnits)
            assertEquals(3, result.artifacts.size)
            assertEquals(3, result.terminalInstances.size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun aggregate_plan_ceiling_is_rejected_before_any_runtime_starts() {
        var calls = 0
        val pool = Executors.newFixedThreadPool(2)
        try {
            val coordinator = AgentParallelCoordinator(
                factory = factory(
                    AgentRuntimeAdapter {
                        calls += 1
                        error("aggregate-rejected plan must not run")
                    }
                ),
                aggregateBudget = aggregate.copy(maxAgents = 2),
                executor = pool
            )

            val result = coordinator.run(
                plan(),
                AgentCoordinatorRunWindow(now, expires),
                2_000
            )

            assertEquals(AgentCoordinatorTerminalState.BUDGET_EXHAUSTED, result.state)
            assertEquals(0, calls)
            assertEquals(0, result.aggregateUsage.agentsStarted)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun structural_scope_violation_is_rejected_before_any_runtime_starts() {
        var calls = 0
        val narrow = AgentCognitiveScope.create(listOf("verification"))
        val root = AgentCoordinatorStep.create(
            id = AgentCoordinatorStepId("root"),
            parentStepId = null,
            blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
            cognitiveScope = narrow,
            budget = rootBudget,
            inputReferences = listOf("evidence:root")
        )
        val child = AgentCoordinatorStep.create(
            id = AgentCoordinatorStepId("child"),
            parentStepId = AgentCoordinatorStepId("root"),
            blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
            cognitiveScope = scope,
            budget = childBudget,
            inputReferences = listOf("evidence:child")
        )
        val invalid = AgentCoordinatorPlan(
            AgentRootTaskId("parallel-invalid-root"),
            listOf(root, child)
        )
        val pool = Executors.newFixedThreadPool(2)

        try {
            val coordinator = AgentParallelCoordinator(
                factory = factory(
                    AgentRuntimeAdapter {
                        calls += 1
                        error("structurally rejected plan must not run")
                    }
                ),
                aggregateBudget = aggregate,
                executor = pool
            )

            val result = coordinator.run(
                invalid,
                AgentCoordinatorRunWindow(now, expires),
                2_000
            )

            assertEquals(AgentCoordinatorTerminalState.FAILED, result.state)
            assertEquals(0, calls)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun cancellation_before_launch_returns_cancelled_without_runtime_start() {
        var calls = 0
        val pool = Executors.newFixedThreadPool(2)
        try {
            val coordinator = AgentParallelCoordinator(
                factory = factory(
                    AgentRuntimeAdapter {
                        calls += 1
                        error("cancelled plan must not run")
                    }
                ),
                aggregateBudget = aggregate,
                executor = pool
            )

            val result = coordinator.run(
                plan(),
                AgentCoordinatorRunWindow(now, expires),
                2_000,
                cancelled = { true }
            )

            assertEquals(AgentCoordinatorTerminalState.CANCELLED, result.state)
            assertEquals(0, calls)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun plan() =
        AgentCoordinatorPlan(
            AgentRootTaskId("parallel-coordinator-root"),
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
            AgentCognitiveScope.create(listOf("analysis", "verification")),
            globalBudget
        ),
        runtimeAdapter = adapter,
        auditLedger = AgentAuditLedger { },
        timeSource = { now }
    )
}
