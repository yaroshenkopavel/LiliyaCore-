package pro.liliya.core.asf

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentWorkerExecutionContractTest {
    private val now = Instant.parse("2026-09-30T07:30:00Z")
    private val expires = Instant.parse("2026-09-30T07:40:00Z")
    private val rootTask = AgentRootTaskId("root-asf-h-exec")
    private val scope = AgentCognitiveScope.create(listOf("verification"))

    private val blueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "bounded-worker",
        "bounded-verification",
        scope
    )

    private val nanoBudget = AgentWorkBudget(5_000, 2_000, 16_000, 4, 1, 0)
    private val microBudget = AgentWorkBudget(30_000, 20_000, 128_000, 16, 4, 1)
    private val fullBudget = AgentWorkBudget(120_000, 100_000, 1_000_000, 128, 32, 4)

    private val profileSet = AgentWorkerProfileSet(
        listOf(
            AgentWorkerProfile(AgentWorkerClass.NANO, nanoBudget, 0, false),
            AgentWorkerProfile(AgentWorkerClass.MICRO, microBudget, 1, false),
            AgentWorkerProfile(AgentWorkerClass.FULL, fullBudget, 1, true)
        )
    )

    private val nanoRuntime = AgentWorkerRuntimeDescriptor(
        "nano-deterministic-v1",
        AgentWorkerRuntimeKind.DETERMINISTIC
    )
    private val microRuntime = AgentWorkerRuntimeDescriptor(
        "micro-llm-v1",
        AgentWorkerRuntimeKind.LLM,
        "bounded-test-model"
    )
    private val fullRuntime = AgentWorkerRuntimeDescriptor(
        "full-llm-v1",
        AgentWorkerRuntimeKind.LLM,
        "bounded-test-model"
    )

    @Test
    fun worker_factory_rejects_nano_descendant_widening_before_runtime() {
        var runtimeCalls = 0
        val rejected = assertIs<AgentWorkerFactoryResult.Rejected>(
            workerFactory(
                AgentRuntimeAdapter {
                    runtimeCalls++
                    error("must not run")
                }
            ).runSingle(
                workerClass = AgentWorkerClass.NANO,
                runtime = nanoRuntime,
                request = request(nanoBudget.copy(maxDescendants = 1)),
                population = AgentPopulationSnapshot(0, 0, 0),
                generation = AgentInstanceGeneration(1),
                admittedAt = now,
                expiresAt = expires,
                inputReferences = listOf("evidence:nano")
            )
        )

        assertEquals(AgentWorkerAdmissionRejection.NANO_DESCENDANTS_FORBIDDEN, rejected.reason)
        assertEquals(0, runtimeCalls)
    }

    @Test
    fun worker_factory_binds_profile_and_delegates_to_existing_agent_factory() {
        var runtimeCalls = 0
        val delegated = assertIs<AgentWorkerFactoryResult.Delegated>(
            workerFactory(
                AgentRuntimeAdapter { context ->
                    runtimeCalls++
                    AgentRuntimeOutcome.Completed(
                        kind = "nano-validation",
                        payloadDigest = "sha256:nano-result",
                        provenanceReferences = context.workspace.inputReferences,
                        usage = AgentRuntimeUsage(10, 10, 10, 0, 1)
                    )
                }
            ).runSingle(
                workerClass = AgentWorkerClass.NANO,
                runtime = nanoRuntime,
                request = request(nanoBudget),
                population = AgentPopulationSnapshot(0, 0, 0),
                generation = AgentInstanceGeneration(1),
                admittedAt = now,
                expiresAt = expires,
                inputReferences = listOf("evidence:nano")
            )
        )

        assertEquals(1, runtimeCalls)
        assertEquals(AgentWorkerClass.NANO, delegated.binding.workerClass)
        assertEquals(nanoRuntime, delegated.binding.runtime)
        assertEquals(profileSet.profile(AgentWorkerClass.NANO), delegated.binding.profile)

        val terminal = assertIs<AgentFactoryResult.Terminal>(delegated.result)
        assertEquals(AgentLifecycleState.COMPLETED, terminal.instance.lifecycle)
        val artifact = assertNotNull(terminal.artifact)
        assertEquals(listOf("evidence:nano"), artifact.provenanceReferences)
        assertEquals(rootTask, artifact.rootTaskId)
    }

    @Test
    fun protected_tool_view_widening_is_rejected_before_runtime() {
        var runtimeCalls = 0
        val rejected = assertIs<AgentWorkerFactoryResult.Rejected>(
            workerFactory(
                AgentRuntimeAdapter {
                    runtimeCalls++
                    error("must not run")
                }
            ).runSingle(
                workerClass = AgentWorkerClass.NANO,
                runtime = nanoRuntime,
                request = request(nanoBudget),
                population = AgentPopulationSnapshot(0, 0, 0),
                generation = AgentInstanceGeneration(1),
                admittedAt = now,
                expiresAt = expires,
                inputReferences = listOf("evidence:nano"),
                protectedToolViewRequested = true
            )
        )

        assertEquals(
            AgentWorkerAdmissionRejection.PROTECTED_TOOL_VIEW_NOT_ALLOWED,
            rejected.reason
        )
        assertEquals(0, runtimeCalls)
    }

    @Test
    fun nano_cannot_spawn_a_child_through_worker_aware_coordinator() {
        var runtimeCalls = 0
        val coordinator = workerCoordinator(
            adapter = successfulAdapter { runtimeCalls++ }
        )

        val result = coordinator.runSequential(
            AgentCoordinatorPlan(
                rootTask,
                listOf(
                    workerStep(
                        "full-root",
                        null,
                        AgentWorkerClass.FULL,
                        fullRuntime,
                        fullBudget.copy(maxDescendants = 2)
                    ),
                    workerStep(
                        "nano-child",
                        "full-root",
                        AgentWorkerClass.NANO,
                        nanoRuntime,
                        nanoBudget
                    ),
                    workerStep(
                        "micro-grandchild",
                        "nano-child",
                        AgentWorkerClass.MICRO,
                        microRuntime,
                        microBudget.copy(maxDescendants = 0)
                    )
                )
            ),
            AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.BUDGET_EXHAUSTED, result.state)
        assertEquals(2, runtimeCalls)
        assertEquals(2, result.completedSteps)
        assertEquals(2, result.terminalInstances.size)
    }

    @Test
    fun mixed_worker_population_cap_stops_next_worker_before_runtime() {
        var runtimeCalls = 0
        val coordinator = workerCoordinator(
            aggregate = AgentAggregateBudget(
                maxWallClockMillis = 120_000,
                maxInferenceUnits = 100_000,
                maxContextBytes = 1_000_000,
                maxRetrievalItems = 128,
                maxArtifacts = 32,
                maxAgents = 1
            ),
            adapter = successfulAdapter { runtimeCalls++ }
        )

        val result = coordinator.runSequential(
            AgentCoordinatorPlan(
                rootTask,
                listOf(
                    workerStep(
                        "full-root",
                        null,
                        AgentWorkerClass.FULL,
                        fullRuntime,
                        fullBudget.copy(maxDescendants = 1)
                    ),
                    workerStep(
                        "micro-child",
                        "full-root",
                        AgentWorkerClass.MICRO,
                        microRuntime,
                        microBudget.copy(maxDescendants = 0)
                    )
                )
            ),
            AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.BUDGET_EXHAUSTED, result.state)
        assertEquals(1, runtimeCalls)
        assertEquals(1, result.completedSteps)
        assertEquals(1, result.aggregateUsage.agentsStarted)
    }

    @Test
    fun worker_step_without_worker_factory_fails_closed_before_runtime() {
        var runtimeCalls = 0
        val coordinator = AgentCoordinator(
            coreFactory(
                AgentRuntimeAdapter {
                    runtimeCalls++
                    error("must not run")
                }
            ),
            defaultAggregate()
        )

        val result = coordinator.runSequential(
            AgentCoordinatorPlan(
                rootTask,
                listOf(
                    workerStep(
                        "nano-root",
                        null,
                        AgentWorkerClass.NANO,
                        nanoRuntime,
                        nanoBudget
                    )
                )
            ),
            AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.FAILED, result.state)
        assertEquals(0, runtimeCalls)
        assertEquals(0, result.completedSteps)
    }

    @Test
    fun worker_metadata_is_all_or_nothing_and_capability_neutral() {
        assertFailsWith<IllegalArgumentException> {
            AgentCoordinatorStep.create(
                id = AgentCoordinatorStepId("invalid"),
                parentStepId = null,
                blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
                cognitiveScope = scope,
                budget = nanoBudget,
                inputReferences = listOf("evidence:invalid"),
                workerClass = AgentWorkerClass.NANO
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentCoordinatorStep.create(
                id = AgentCoordinatorStepId("invalid-tool"),
                parentStepId = null,
                blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
                cognitiveScope = scope,
                budget = nanoBudget,
                inputReferences = listOf("evidence:invalid-tool"),
                protectedToolViewRequested = true
            )
        }

        val forbidden = listOf(
            "authority", "permission", "capability", "executiongrant",
            "credential", "secret", "token", "license", "principal"
        )
        listOf(
            AgentWorkerExecutionBinding::class.java,
            AgentWorkerFactoryResult.Delegated::class.java
        ).forEach { javaType ->
            val fields = javaType.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(
                    fields.none { word in it },
                    javaType.simpleName + " contains forbidden field: " + word
                )
            }
        }
    }

    @Test
    fun mixed_worker_root_accounting_reconciles_per_class_with_root_total() {
        val coordinator = workerCoordinator(
            adapter = successfulAdapter { }
        )

        val result = coordinator.runSequential(
            AgentCoordinatorPlan(
                rootTask,
                listOf(
                    workerStep(
                        "full-root",
                        null,
                        AgentWorkerClass.FULL,
                        fullRuntime,
                        fullBudget.copy(maxDescendants = 2)
                    ),
                    workerStep(
                        "micro-child",
                        "full-root",
                        AgentWorkerClass.MICRO,
                        microRuntime,
                        microBudget.copy(maxDescendants = 0)
                    ),
                    workerStep(
                        "nano-child",
                        "full-root",
                        AgentWorkerClass.NANO,
                        nanoRuntime,
                        nanoBudget
                    )
                )
            ),
            AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.COMPLETED, result.state)
        assertEquals(3, result.aggregateUsage.agentsStarted)
        assertEquals(1, result.workerAggregateUsage.full.agentsStarted)
        assertEquals(1, result.workerAggregateUsage.micro.agentsStarted)
        assertEquals(1, result.workerAggregateUsage.nano.agentsStarted)
        assertEquals(result.aggregateUsage, result.workerAggregateUsage.total())
    }

    private fun request(
        budget: AgentWorkBudget,
        attempt: Int = 0
    ) = AgentSpawnRequest.create(
        blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
        provenance = AgentSpawnProvenance(rootTask, null, null, 0),
        cognitiveScope = scope,
        budget = budget,
        logicalRoleAttempt = attempt
    )

    private fun workerStep(
        id: String,
        parent: String?,
        workerClass: AgentWorkerClass,
        runtime: AgentWorkerRuntimeDescriptor,
        budget: AgentWorkBudget
    ) = AgentCoordinatorStep.create(
        id = AgentCoordinatorStepId(id),
        parentStepId = parent?.let(::AgentCoordinatorStepId),
        blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
        cognitiveScope = scope,
        budget = budget,
        inputReferences = listOf("evidence:" + id),
        workerClass = workerClass,
        runtime = runtime
    )

    private fun successfulAdapter(onRun: () -> Unit) =
        AgentRuntimeAdapter { context ->
            onRun()
            AgentRuntimeOutcome.Completed(
                kind = "worker-result",
                payloadDigest = "sha256:worker-result",
                provenanceReferences = context.workspace.inputReferences,
                usage = AgentRuntimeUsage(10, 10, 10, 0, 1)
            )
        }

    private fun workerFactory(adapter: AgentRuntimeAdapter): AgentWorkerFactory {
        val delegate = coreFactory(adapter)
        return AgentWorkerFactory(
            AgentWorkerAdmissionPolicy(profileSet),
            delegate
        )
    }

    private fun workerCoordinator(
        aggregate: AgentAggregateBudget = defaultAggregate(),
        adapter: AgentRuntimeAdapter
    ): AgentCoordinator {
        val delegate = coreFactory(adapter)
        return AgentCoordinator(
            factory = delegate,
            aggregateBudget = aggregate,
            workerFactory = AgentWorkerFactory(
                AgentWorkerAdmissionPolicy(profileSet),
                delegate
            )
        )
    }

    private fun coreFactory(adapter: AgentRuntimeAdapter): AgentFactory =
        AgentFactory(
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

    private fun defaultAggregate() = AgentAggregateBudget(
        maxWallClockMillis = 240_000,
        maxInferenceUnits = 200_000,
        maxContextBytes = 2_000_000,
        maxRetrievalItems = 256,
        maxArtifacts = 64,
        maxAgents = 8
    )
}
