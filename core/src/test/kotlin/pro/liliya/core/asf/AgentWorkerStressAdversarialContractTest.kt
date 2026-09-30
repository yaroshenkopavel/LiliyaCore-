package pro.liliya.core.asf

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentWorkerStressAdversarialContractTest {
    private val now = Instant.parse("2026-09-30T11:00:00Z")
    private val expires = Instant.parse("2026-09-30T11:10:00Z")
    private val rootTask = AgentRootTaskId("root-asf-h-stress")
    private val scope = AgentCognitiveScope.create(listOf("verification"))

    private val blueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "stress-worker",
        "bounded-verification",
        scope
    )

    private val nanoBudget = AgentWorkBudget(5_000, 2_000, 16_000, 4, 1, 0)
    private val microBudget = AgentWorkBudget(30_000, 20_000, 128_000, 16, 4, 1)
    private val fullBudget = AgentWorkBudget(120_000, 100_000, 1_000_000, 128, 32, 8)

    private val profiles = AgentWorkerProfileSet(
        listOf(
            AgentWorkerProfile(AgentWorkerClass.NANO, nanoBudget, 0, false),
            AgentWorkerProfile(AgentWorkerClass.MICRO, microBudget, 1, false),
            AgentWorkerProfile(AgentWorkerClass.FULL, fullBudget, 1, true)
        )
    )

    private val nanoRuntime = AgentWorkerRuntimeDescriptor(
        "nano-stress-v1",
        AgentWorkerRuntimeKind.DETERMINISTIC
    )
    private val microRuntime = AgentWorkerRuntimeDescriptor(
        "micro-stress-v1",
        AgentWorkerRuntimeKind.LLM,
        "bounded-test-model"
    )
    private val fullRuntime = AgentWorkerRuntimeDescriptor(
        "full-stress-v1",
        AgentWorkerRuntimeKind.LLM,
        "bounded-test-model"
    )

    @Test
    fun runaway_mixed_population_stops_at_root_agent_cap_before_next_runtime() {
        var runtimeCalls = 0
        val coordinator = coordinator(
            aggregate = aggregate(maxAgents = 4),
            adapter = successfulAdapter { runtimeCalls++ }
        )

        val result = coordinator.runSequential(
            AgentCoordinatorPlan(
                rootTask,
                listOf(
                    step("root", null, AgentWorkerClass.FULL, fullRuntime, fullBudget.copy(maxDescendants = 4)),
                    step("micro-1", "root", AgentWorkerClass.MICRO, microRuntime, microBudget.copy(maxDescendants = 0)),
                    step("nano-1", "root", AgentWorkerClass.NANO, nanoRuntime, nanoBudget),
                    step("micro-2", "root", AgentWorkerClass.MICRO, microRuntime, microBudget.copy(maxDescendants = 0)),
                    step("nano-overflow", "root", AgentWorkerClass.NANO, nanoRuntime, nanoBudget)
                )
            ),
            AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.BUDGET_EXHAUSTED, result.state)
        assertEquals(4, runtimeCalls)
        assertEquals(4, result.completedSteps)
        assertEquals(4, result.aggregateUsage.agentsStarted)
        assertEquals(4, result.workerAggregateUsage.total().agentsStarted)
        assertEquals(1, result.workerAggregateUsage.full.agentsStarted)
        assertEquals(2, result.workerAggregateUsage.micro.agentsStarted)
        assertEquals(1, result.workerAggregateUsage.nano.agentsStarted)
    }

    @Test
    fun retry_storm_is_cut_off_at_first_profile_retry_violation() {
        var runtimeCalls = 0
        val coordinator = coordinator(
            adapter = successfulAdapter { runtimeCalls++ }
        )

        val result = coordinator.runSequential(
            AgentCoordinatorPlan(
                rootTask,
                listOf(
                    step("root", null, AgentWorkerClass.FULL, fullRuntime, fullBudget.copy(maxDescendants = 4)),
                    step(
                        "retry-2",
                        "root",
                        AgentWorkerClass.MICRO,
                        microRuntime,
                        microBudget.copy(maxDescendants = 0),
                        attempt = 2
                    ),
                    step("retry-3", "root", AgentWorkerClass.MICRO, microRuntime, microBudget.copy(maxDescendants = 0), attempt = 3),
                    step("retry-4", "root", AgentWorkerClass.NANO, nanoRuntime, nanoBudget, attempt = 4)
                )
            ),
            AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.PARTIAL, result.state)
        assertEquals(1, runtimeCalls)
        assertEquals(1, result.completedSteps)
        assertEquals(1, result.aggregateUsage.agentsStarted)
        assertEquals(1, result.workerAggregateUsage.full.agentsStarted)
        assertEquals(0, result.workerAggregateUsage.micro.agentsStarted)
        assertEquals(0, result.workerAggregateUsage.nano.agentsStarted)
    }

    @Test
    fun child_budget_widening_relative_to_parent_fails_before_child_runtime() {
        var runtimeCalls = 0
        val coordinator = coordinator(
            adapter = successfulAdapter { runtimeCalls++ }
        )
        val constrainedParent = fullBudget.copy(
            maxInferenceUnits = 100,
            maxContextBytes = 64_000,
            maxRetrievalItems = 8,
            maxArtifacts = 2,
            maxDescendants = 1
        )

        val result = coordinator.runSequential(
            AgentCoordinatorPlan(
                rootTask,
                listOf(
                    step("root", null, AgentWorkerClass.FULL, fullRuntime, constrainedParent),
                    step("widening-child", "root", AgentWorkerClass.MICRO, microRuntime, microBudget.copy(maxDescendants = 0))
                )
            ),
            AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.PARTIAL, result.state)
        assertEquals(1, runtimeCalls)
        assertEquals(1, result.completedSteps)
        assertEquals(1, result.aggregateUsage.agentsStarted)
        assertEquals(1, result.workerAggregateUsage.full.agentsStarted)
        assertEquals(0, result.workerAggregateUsage.micro.agentsStarted)
    }

    @Test
    fun provenance_loss_attempt_fails_closed_without_emitting_child_artifact() {
        var runtimeCalls = 0
        val coordinator = coordinator(
            adapter = AgentRuntimeAdapter { context ->
                runtimeCalls++
                val provenance = if (runtimeCalls == 1) {
                    context.workspace.inputReferences
                } else {
                    emptyList()
                }
                AgentRuntimeOutcome.Completed(
                    kind = "stress-result",
                    payloadDigest = "sha256:stress-$runtimeCalls",
                    provenanceReferences = provenance,
                    usage = AgentRuntimeUsage(10, 10, 10, 0, 1)
                )
            }
        )

        val result = coordinator.runSequential(
            AgentCoordinatorPlan(
                rootTask,
                listOf(
                    step("root", null, AgentWorkerClass.FULL, fullRuntime, fullBudget.copy(maxDescendants = 1)),
                    step("provenance-drop", "root", AgentWorkerClass.MICRO, microRuntime, microBudget.copy(maxDescendants = 0))
                )
            ),
            AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.PARTIAL, result.state)
        assertEquals(2, runtimeCalls)
        assertEquals(1, result.completedSteps)
        assertEquals(2, result.terminalInstances.size)
        assertEquals(AgentLifecycleState.FAILED, result.terminalInstances.last().lifecycle)
        assertEquals(1, result.artifacts.size)
        assertTrue(result.artifacts.single().provenanceReferences.isNotEmpty())
        assertEquals(2, result.aggregateUsage.agentsStarted)
        assertEquals(result.aggregateUsage, result.workerAggregateUsage.total())
    }

    private fun step(
        id: String,
        parent: String?,
        workerClass: AgentWorkerClass,
        runtime: AgentWorkerRuntimeDescriptor,
        budget: AgentWorkBudget,
        attempt: Int = 0
    ) = AgentCoordinatorStep.create(
        id = AgentCoordinatorStepId(id),
        parentStepId = parent?.let(::AgentCoordinatorStepId),
        blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
        cognitiveScope = scope,
        budget = budget,
        inputReferences = listOf("evidence:$id"),
        logicalRoleAttempt = attempt,
        workerClass = workerClass,
        runtime = runtime
    )

    private fun successfulAdapter(onRun: () -> Unit) =
        AgentRuntimeAdapter { context ->
            onRun()
            AgentRuntimeOutcome.Completed(
                kind = "stress-result",
                payloadDigest = "sha256:stress-result",
                provenanceReferences = context.workspace.inputReferences,
                usage = AgentRuntimeUsage(10, 10, 10, 0, 1)
            )
        }

    private fun coordinator(
        aggregate: AgentAggregateBudget = aggregate(maxAgents = 8),
        adapter: AgentRuntimeAdapter
    ): AgentCoordinator {
        val delegate = AgentFactory(
            registry = AgentBlueprintRegistry(listOf(blueprint)),
            admissionPolicy = AgentAdmissionPolicy(
                AgentFactoryBounds.PROTOTYPE,
                scope,
                fullBudget.copy(maxDescendants = 12)
            ),
            runtimeAdapter = adapter,
            auditLedger = AgentAuditLedger { },
            timeSource = { now }
        )
        return AgentCoordinator(
            factory = delegate,
            aggregateBudget = aggregate,
            workerFactory = AgentWorkerFactory(
                AgentWorkerAdmissionPolicy(profiles),
                delegate
            )
        )
    }

    private fun aggregate(maxAgents: Int) = AgentAggregateBudget(
        maxWallClockMillis = 240_000,
        maxInferenceUnits = 200_000,
        maxContextBytes = 2_000_000,
        maxRetrievalItems = 256,
        maxArtifacts = 64,
        maxAgents = maxAgents
    )
}
