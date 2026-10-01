package pro.liliya.core.asf

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentHeterogeneousRuntimeFabricContractTest {
    private val now = Instant.parse("2026-10-01T12:30:00Z")
    private val expires = Instant.parse("2026-10-01T12:40:00Z")
    private val rootTask = AgentRootTaskId("root-asf-k")

    private val broadScope =
        AgentCognitiveScope.create(listOf("analysis", "retrieval", "verification"))
    private val retrievalScope =
        AgentCognitiveScope.create(listOf("retrieval", "verification"))
    private val verifyScope =
        AgentCognitiveScope.create(listOf("verification"))

    private val nanoBlueprint = blueprint("nano-rule", verifyScope)
    private val microBlueprint = blueprint("micro-graph", retrievalScope)
    private val fullBlueprint = blueprint("full-onnx", broadScope)

    private val nanoBudget = AgentWorkBudget(1_000, 100, 4_000, 1, 1, 0)
    private val microBudget = AgentWorkBudget(4_000, 500, 16_000, 8, 2, 0)
    private val fullBudget = AgentWorkBudget(10_000, 2_000, 64_000, 16, 4, 2)

    private val aggregate = AgentAggregateBudget(
        maxWallClockMillis = 20_000,
        maxInferenceUnits = 5_000,
        maxContextBytes = 128_000,
        maxRetrievalItems = 32,
        maxArtifacts = 8,
        maxAgents = 4
    )

    private val deterministicRuntime = AgentWorkerRuntimeDescriptor(
        runtimeId = "rule-v1",
        kind = AgentWorkerRuntimeKind.DETERMINISTIC
    )
    private val graphRuntime = AgentWorkerRuntimeDescriptor(
        runtimeId = "graph-v1",
        kind = AgentWorkerRuntimeKind.GRAPH_QUERY
    )
    private val onnxRuntime = AgentWorkerRuntimeDescriptor(
        runtimeId = "onnx-v1",
        kind = AgentWorkerRuntimeKind.ONNX,
        modelId = "bounded-onnx-model-v1"
    )
    private val embeddingRuntime = AgentWorkerRuntimeDescriptor(
        runtimeId = "reranker-v1",
        kind = AgentWorkerRuntimeKind.EMBEDDING_RERANKER,
        modelId = "reranker-model-v1"
    )

    private val profiles = AgentWorkerProfileSet(
        listOf(
            AgentWorkerProfile(AgentWorkerClass.NANO, nanoBudget, 0, false),
            AgentWorkerProfile(
                AgentWorkerClass.MICRO,
                microBudget.copy(maxDescendants = 1),
                1,
                false
            ),
            AgentWorkerProfile(
                AgentWorkerClass.FULL,
                fullBudget.copy(maxDescendants = 4),
                1,
                true
            )
        )
    )

    @Test
    fun mixed_runtime_team_executes_without_any_llm_under_common_governance() {
        val calls = mutableListOf<AgentCognitiveRuntimeKind>()
        val fabric = AgentHeterogeneousRuntimeFabric(
            listOf(
                registration(
                    deterministicRuntime,
                    AgentCognitiveRuntimeAdapters.deterministicRule {
                        calls += AgentCognitiveRuntimeKind.DETERMINISTIC_RULE
                        completed(it, "rule-result", AgentRuntimeUsage(10, 0, 8, 0, 1))
                    }
                ),
                registration(
                    graphRuntime,
                    AgentCognitiveRuntimeAdapters.retrievalGraph {
                        calls += AgentCognitiveRuntimeKind.RETRIEVAL_GRAPH
                        completed(it, "graph-result", AgentRuntimeUsage(20, 0, 16, 2, 1))
                    }
                ),
                registration(
                    onnxRuntime,
                    AgentCognitiveRuntimeAdapters.onnx {
                        calls += AgentCognitiveRuntimeKind.ONNX
                        completed(it, "onnx-result", AgentRuntimeUsage(30, 100, 24, 0, 1))
                    }
                )
            )
        )

        val composition = assertIs<AgentTeamCompositionDecision.Composed>(
            AgentTeamComposer.compose(
                AgentTeamCompositionRequest.create(
                    rootTaskId = rootTask,
                    policyVersion = AgentTeamCompositionPolicyVersion("asf-k-policy-v1"),
                    taskShape = AgentTeamTaskShape.workers(
                        listOf(
                            AgentWorkerRequirement.ATOMIC_VALIDATION,
                            AgentWorkerRequirement.NARROW_MULTI_STEP,
                            AgentWorkerRequirement.BROAD_SPECIALIST
                        )
                    ),
                    aggregateBudget = aggregate,
                    inputReferences = listOf("evidence:root"),
                    candidates = listOf(
                        candidate(
                            AgentWorkerClass.NANO,
                            nanoBlueprint,
                            verifyScope,
                            nanoBudget,
                            deterministicRuntime
                        ),
                        candidate(
                            AgentWorkerClass.MICRO,
                            microBlueprint,
                            retrievalScope,
                            microBudget,
                            graphRuntime
                        ),
                        candidate(
                            AgentWorkerClass.FULL,
                            fullBlueprint,
                            broadScope,
                            fullBudget,
                            onnxRuntime
                        )
                    )
                )
            )
        ).plan

        val result = coordinator(fabric).runSequential(
            composition.coordinatorPlan,
            AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.COMPLETED, result.state)
        assertEquals(3, result.completedSteps)
        assertEquals(
            setOf(
                AgentCognitiveRuntimeKind.DETERMINISTIC_RULE,
                AgentCognitiveRuntimeKind.RETRIEVAL_GRAPH,
                AgentCognitiveRuntimeKind.ONNX
            ),
            calls.toSet()
        )
        assertTrue(calls.none { it == AgentCognitiveRuntimeKind.LLM })
        assertEquals(3, result.artifacts.size)
        assertTrue(
            result.artifacts.flatMap { it.provenanceReferences }
                .any { it == "asf-runtime-kind:deterministic_rule" }
        )
        assertTrue(
            result.artifacts.flatMap { it.provenanceReferences }
                .any { it == "asf-runtime-kind:retrieval_graph" }
        )
        assertTrue(
            result.artifacts.flatMap { it.provenanceReferences }
                .any { it == "asf-runtime-kind:onnx" }
        )
        assertEquals(result.aggregateUsage, result.workerAggregateUsage.total())
    }

    @Test
    fun embedding_reranker_executes_through_the_same_worker_lifecycle() {
        var calls = 0
        val fabric = AgentHeterogeneousRuntimeFabric(
            listOf(
                registration(
                    embeddingRuntime,
                    AgentCognitiveRuntimeAdapters.embeddingReranker {
                        calls++
                        completed(
                            it,
                            "rerank-result",
                            AgentRuntimeUsage(10, 50, 12, 1, 1)
                        )
                    }
                )
            )
        )

        val nanoCandidate = candidate(
            AgentWorkerClass.NANO,
            nanoBlueprint,
            verifyScope,
            nanoBudget,
            embeddingRuntime
        )
        val plan = assertIs<AgentTeamCompositionDecision.Composed>(
            AgentTeamComposer.compose(
                AgentTeamCompositionRequest.create(
                    rootTaskId = rootTask,
                    policyVersion = AgentTeamCompositionPolicyVersion("asf-k-rerank-v1"),
                    taskShape = AgentTeamTaskShape.workers(
                        listOf(AgentWorkerRequirement.ATOMIC_VALIDATION)
                    ),
                    aggregateBudget = aggregate,
                    inputReferences = listOf("evidence:rerank"),
                    candidates = listOf(nanoCandidate)
                )
            )
        ).plan

        val result = coordinator(fabric).runSequential(
            plan.coordinatorPlan,
            AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.COMPLETED, result.state)
        assertEquals(1, calls)
        assertTrue(
            result.artifacts.single().provenanceReferences
                .contains("asf-runtime-kind:embedding_reranker")
        )
    }

    @Test
    fun unavailable_runtime_fails_closed_without_hidden_fallback() {
        var unavailableCalls = 0
        var alternativeCalls = 0

        val unavailable = AgentCognitiveRuntimeRegistration(
            descriptor = graphRuntime,
            available = { false },
            adapter = AgentCognitiveRuntimeAdapters.retrievalGraph {
                unavailableCalls++
                completed(it, "must-not-run", AgentRuntimeUsage(1, 0, 1, 1, 1))
            }
        )
        val alternativeDescriptor = graphRuntime.copy(runtimeId = "graph-alternative-v1")
        val alternative = AgentCognitiveRuntimeRegistration(
            descriptor = alternativeDescriptor,
            available = { true },
            adapter = AgentCognitiveRuntimeAdapters.retrievalGraph {
                alternativeCalls++
                completed(it, "alternative", AgentRuntimeUsage(1, 0, 1, 1, 1))
            }
        )
        val fabric = AgentHeterogeneousRuntimeFabric(listOf(unavailable, alternative))

        val result = runSingleMicro(graphRuntime, fabric)

        assertEquals(AgentCoordinatorTerminalState.FAILED, result.state)
        assertEquals(0, unavailableCalls)
        assertEquals(0, alternativeCalls)
        assertEquals(0, result.completedSteps)
    }

    @Test
    fun exact_runtime_and_model_identity_are_required_for_registration_match() {
        var calls = 0
        val registered = onnxRuntime
        val requested = onnxRuntime.copy(modelId = "different-model")
        val fabric = AgentHeterogeneousRuntimeFabric(
            listOf(
                registration(
                    registered,
                    AgentCognitiveRuntimeAdapters.onnx {
                        calls++
                        completed(it, "onnx", AgentRuntimeUsage(1, 1, 1, 0, 1))
                    }
                )
            )
        )

        val result = runSingleFull(requested, fabric)

        assertEquals(AgentCoordinatorTerminalState.FAILED, result.state)
        assertEquals(0, calls)
    }

    @Test
    fun runtime_usage_is_still_enforced_by_existing_core_budget_accounting() {
        val fabric = AgentHeterogeneousRuntimeFabric(
            listOf(
                registration(
                    graphRuntime,
                    AgentCognitiveRuntimeAdapters.retrievalGraph {
                        completed(
                            it,
                            "graph-over-budget",
                            AgentRuntimeUsage(
                                wallClockMillis = microBudget.maxWallClockMillis + 1,
                                inferenceUnits = 0,
                                contextBytes = 1,
                                retrievalItems = 1,
                                artifactCount = 1
                            )
                        )
                    }
                )
            )
        )

        val result = runSingleMicro(graphRuntime, fabric)

        assertEquals(AgentCoordinatorTerminalState.BUDGET_EXHAUSTED, result.state)
        assertEquals(0, result.completedSteps)
    }

    @Test
    fun runtime_provenance_preserves_source_runtime_and_model_identity() {
        val fabric = AgentHeterogeneousRuntimeFabric(
            listOf(
                registration(
                    onnxRuntime,
                    AgentCognitiveRuntimeAdapters.onnx {
                        AgentCognitiveRuntimeExecutionResult.Completed.create(
                            artifactKind = "onnx-result",
                            payloadDigest = "sha256:onnx",
                            sourceReferences = listOf(
                                "evidence:model-input",
                                "evidence:retrieved-source"
                            ),
                            usage = AgentRuntimeUsage(1, 10, 2, 0, 1)
                        )
                    }
                )
            )
        )

        val result = runSingleFull(onnxRuntime, fabric)
        val artifact = result.artifacts.single()

        assertEquals(AgentCoordinatorTerminalState.COMPLETED, result.state)
        assertTrue("evidence:model-input" in artifact.provenanceReferences)
        assertTrue("evidence:retrieved-source" in artifact.provenanceReferences)
        assertTrue("asf-runtime-kind:onnx" in artifact.provenanceReferences)
        assertTrue(
            artifact.provenanceReferences.any {
                it.startsWith("asf-runtime-id-sha256:")
            }
        )
        assertTrue(
            artifact.provenanceReferences.any {
                it.startsWith("asf-model-id-sha256:")
            }
        )
    }

    @Test
    fun fabric_without_worker_runtime_descriptor_fails_closed() {
        var calls = 0
        val fabric = AgentHeterogeneousRuntimeFabric(
            listOf(
                registration(
                    deterministicRuntime,
                    AgentCognitiveRuntimeAdapters.deterministicRule {
                        calls++
                        completed(it, "rule", AgentRuntimeUsage(1, 0, 1, 0, 1))
                    }
                )
            )
        )

        val directFactory = AgentFactory(
            registry = AgentBlueprintRegistry(listOf(nanoBlueprint)),
            admissionPolicy = AgentAdmissionPolicy(
                AgentFactoryBounds.PROTOTYPE,
                broadScope,
                fullBudget.copy(maxDescendants = 12)
            ),
            runtimeAdapter = fabric,
            auditLedger = AgentAuditLedger { },
            timeSource = { now }
        )
        val request = AgentSpawnRequest.create(
            blueprint = AgentBlueprintReference(nanoBlueprint.id, nanoBlueprint.version),
            provenance = AgentSpawnProvenance(rootTask, null, null, 0),
            cognitiveScope = verifyScope,
            budget = nanoBudget
        )
        val terminal = assertIs<AgentFactoryResult.Terminal>(
            directFactory.runSingle(
                request = request,
                population = AgentPopulationSnapshot(0, 0, 0),
                generation = AgentInstanceGeneration(1),
                admittedAt = now,
                expiresAt = expires,
                inputReferences = listOf("evidence:direct")
            )
        )

        assertEquals(AgentLifecycleState.FAILED, terminal.instance.lifecycle)
        assertEquals(0, calls)
    }

    @Test
    fun heterogeneous_runtime_contracts_do_not_carry_authority_permission_or_secrets() {
        val forbidden = listOf(
            "authority", "permission", "capability", "executiongrant",
            "credential", "secret", "token", "license", "principal"
        )
        listOf(
            AgentCognitiveRuntimeExecutionRequest::class.java,
            AgentCognitiveRuntimeRegistration::class.java,
            AgentWorkerRuntimeDescriptor::class.java
        ).forEach { type ->
            val names = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(names.none { word in it })
            }
        }
    }

    private fun runSingleMicro(
        runtime: AgentWorkerRuntimeDescriptor,
        fabric: AgentHeterogeneousRuntimeFabric
    ): AgentCoordinatorResult {
        val candidate = candidate(
            AgentWorkerClass.MICRO,
            microBlueprint,
            retrievalScope,
            microBudget,
            runtime
        )
        return runSingle(candidate, AgentWorkerRequirement.NARROW_MULTI_STEP, fabric)
    }

    private fun runSingleFull(
        runtime: AgentWorkerRuntimeDescriptor,
        fabric: AgentHeterogeneousRuntimeFabric
    ): AgentCoordinatorResult {
        val candidate = candidate(
            AgentWorkerClass.FULL,
            fullBlueprint,
            broadScope,
            fullBudget,
            runtime
        )
        return runSingle(candidate, AgentWorkerRequirement.BROAD_SPECIALIST, fabric)
    }

    private fun runSingle(
        candidate: AgentTeamWorkerCandidate,
        requirement: AgentWorkerRequirement,
        fabric: AgentHeterogeneousRuntimeFabric
    ): AgentCoordinatorResult {
        val plan = assertIs<AgentTeamCompositionDecision.Composed>(
            AgentTeamComposer.compose(
                AgentTeamCompositionRequest.create(
                    rootTaskId = rootTask,
                    policyVersion = AgentTeamCompositionPolicyVersion("asf-k-single-v1"),
                    taskShape = AgentTeamTaskShape.workers(listOf(requirement)),
                    aggregateBudget = aggregate,
                    inputReferences = listOf("evidence:single"),
                    candidates = listOf(candidate)
                )
            )
        ).plan

        return coordinator(fabric).runSequential(
            plan.coordinatorPlan,
            AgentCoordinatorRunWindow(now, expires)
        )
    }

    private fun coordinator(
        fabric: AgentHeterogeneousRuntimeFabric
    ): AgentCoordinator {
        val delegate = AgentFactory(
            registry = AgentBlueprintRegistry(
                listOf(nanoBlueprint, microBlueprint, fullBlueprint)
            ),
            admissionPolicy = AgentAdmissionPolicy(
                AgentFactoryBounds.PROTOTYPE,
                broadScope,
                fullBudget.copy(maxDescendants = 12)
            ),
            runtimeAdapter = fabric,
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

    private fun registration(
        descriptor: AgentWorkerRuntimeDescriptor,
        adapter: AgentCognitiveRuntimeExecutionAdapter
    ) = AgentCognitiveRuntimeRegistration(
        descriptor = descriptor,
        available = { true },
        adapter = adapter
    )

    private fun completed(
        request: AgentCognitiveRuntimeExecutionRequest,
        kind: String,
        usage: AgentRuntimeUsage
    ) = AgentCognitiveRuntimeExecutionResult.Completed.create(
        artifactKind = kind,
        payloadDigest = "sha256:$kind",
        sourceReferences = request.context.workspace.inputReferences,
        usage = usage
    )

    private fun blueprint(
        role: String,
        scope: AgentCognitiveScope
    ) = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        role,
        "heterogeneous-cognition",
        scope
    )

    private fun candidate(
        workerClass: AgentWorkerClass,
        blueprint: AgentBlueprint,
        scope: AgentCognitiveScope,
        budget: AgentWorkBudget,
        runtime: AgentWorkerRuntimeDescriptor
    ) = AgentTeamWorkerCandidate(
        workerClass = workerClass,
        blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
        cognitiveScope = scope,
        budget = budget,
        runtime = runtime
    )
}
