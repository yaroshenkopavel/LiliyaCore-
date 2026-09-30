package pro.liliya.core.asf

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentTeamCompositionExecutionContractTest {
    private val now = Instant.parse("2026-09-30T20:20:00Z")
    private val expires = Instant.parse("2026-09-30T20:30:00Z")
    private val rootTask = AgentRootTaskId("root-asf-i-exec")
    private val policyVersion = AgentTeamCompositionPolicyVersion("asf-i-policy-v1")
    private val broadScope = AgentCognitiveScope.create(listOf("analysis", "verification"))
    private val verifyScope = AgentCognitiveScope.create(listOf("verification"))

    private val nanoBlueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "nano-verifier",
        "atomic-validation",
        verifyScope
    )
    private val microBlueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "micro-analyst",
        "narrow-analysis",
        broadScope
    )
    private val fullBlueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "full-specialist",
        "broad-analysis",
        broadScope
    )

    private val nanoBudget = AgentWorkBudget(1_000, 1_000, 4_000, 2, 1, 0)
    private val microBudget = AgentWorkBudget(4_000, 4_000, 16_000, 8, 2, 0)
    private val fullBudget = AgentWorkBudget(10_000, 10_000, 64_000, 16, 4, 2)

    private val aggregate = AgentAggregateBudget(
        maxWallClockMillis = 20_000,
        maxInferenceUnits = 20_000,
        maxContextBytes = 128_000,
        maxRetrievalItems = 32,
        maxArtifacts = 8,
        maxAgents = 4
    )

    private val nanoRuntime = AgentWorkerRuntimeDescriptor(
        "nano-deterministic-v1",
        AgentWorkerRuntimeKind.DETERMINISTIC
    )
    private val microRuntime = AgentWorkerRuntimeDescriptor(
        "micro-onnx-v1",
        AgentWorkerRuntimeKind.ONNX,
        "micro-model"
    )
    private val fullRuntime = AgentWorkerRuntimeDescriptor(
        "full-llm-v1",
        AgentWorkerRuntimeKind.LLM,
        "qwen3-1.7b"
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
    fun composed_full_micro_nano_plan_executes_through_existing_worker_admission() {
        var runtimeCalls = 0
        val composition = assertIs<AgentTeamCompositionDecision.Composed>(
            AgentTeamComposer.compose(
                compositionRequest(
                    AgentTeamTaskShape.workers(
                        listOf(
                            AgentWorkerRequirement.ATOMIC_VALIDATION,
                            AgentWorkerRequirement.NARROW_MULTI_STEP,
                            AgentWorkerRequirement.BROAD_SPECIALIST
                        )
                    )
                )
            )
        ).plan

        val result = coordinator(
            adapter = AgentRuntimeAdapter { context ->
                runtimeCalls++
                AgentRuntimeOutcome.Completed(
                    kind = "team-result",
                    payloadDigest = "sha256:team-" + runtimeCalls,
                    provenanceReferences = context.workspace.inputReferences,
                    usage = AgentRuntimeUsage(10, 10, 10, 0, 1)
                )
            }
        ).runSequential(
            composition.coordinatorPlan,
            AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.COMPLETED, result.state)
        assertEquals(3, runtimeCalls)
        assertEquals(3, result.completedSteps)
        assertEquals(AgentTeamTemplateKind.ROOT_REVIEW_FAN_OUT, composition.templateKind)
        assertEquals(3, result.aggregateUsage.agentsStarted)
        assertEquals(1, result.workerAggregateUsage.full.agentsStarted)
        assertEquals(1, result.workerAggregateUsage.micro.agentsStarted)
        assertEquals(1, result.workerAggregateUsage.nano.agentsStarted)
        assertEquals(result.aggregateUsage, result.workerAggregateUsage.total())
        assertTrue(result.artifacts.all { it.rootTaskId == rootTask })
        val rootArtifact = result.artifacts.first()
        val rootArtifactReference = "asf-artifact:" + rootArtifact.id.value
        assertTrue(
            result.artifacts.drop(1).all {
                rootArtifactReference in it.provenanceReferences
            }
        )
        assertTrue(
            result.terminalInstances.drop(1).all {
                it.provenance.parentAgentId == result.terminalInstances.first().id
            }
        )
    }

    @Test
    fun composed_worker_plan_without_worker_factory_fails_closed_before_runtime() {
        var runtimeCalls = 0
        val composition = assertIs<AgentTeamCompositionDecision.Composed>(
            AgentTeamComposer.compose(
                compositionRequest(
                    AgentTeamTaskShape.workers(
                        listOf(AgentWorkerRequirement.ATOMIC_VALIDATION)
                    )
                )
            )
        ).plan

        val delegate = coreFactory(
            AgentRuntimeAdapter {
                runtimeCalls++
                error("worker composition must not bypass AgentWorkerFactory")
            }
        )
        val result = AgentCoordinator(
            factory = delegate,
            aggregateBudget = aggregate,
            workerFactory = null
        ).runSequential(
            composition.coordinatorPlan,
            AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentTeamTemplateKind.SINGLE_WORKER, composition.templateKind)
        assertEquals(AgentCoordinatorTerminalState.FAILED, result.state)
        assertEquals(0, runtimeCalls)
        assertEquals(0, result.completedSteps)
        assertEquals(0, result.aggregateUsage.agentsStarted)
    }

    @Test
    fun worker_profile_rejection_stops_composed_plan_before_runtime() {
        var runtimeCalls = 0
        val tooLargeNano = nanoCandidate(
            budget = nanoBudget.copy(maxInferenceUnits = 1_001)
        )
        val composition = assertIs<AgentTeamCompositionDecision.Composed>(
            AgentTeamComposer.compose(
                compositionRequest(
                    shape = AgentTeamTaskShape.workers(
                        listOf(AgentWorkerRequirement.ATOMIC_VALIDATION)
                    ),
                    candidates = listOf(tooLargeNano)
                )
            )
        ).plan

        val result = coordinator(
            adapter = AgentRuntimeAdapter {
                runtimeCalls++
                error("rejected worker must not reach runtime")
            }
        ).runSequential(
            composition.coordinatorPlan,
            AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.FAILED, result.state)
        assertEquals(0, runtimeCalls)
        assertEquals(0, result.completedSteps)
        assertEquals(0, result.aggregateUsage.agentsStarted)
    }

    @Test
    fun deterministic_fallback_never_constructs_or_executes_agent_plan() {
        val decision = AgentTeamComposer.compose(
            compositionRequest(
                shape = AgentTeamTaskShape.deterministic(),
                candidates = emptyList()
            )
        )

        assertIs<AgentTeamCompositionDecision.DeterministicFallback>(decision)
    }

    private fun compositionRequest(
        shape: AgentTeamTaskShape,
        candidates: List<AgentTeamWorkerCandidate> = allCandidates()
    ) = AgentTeamCompositionRequest.create(
        rootTaskId = rootTask,
        policyVersion = policyVersion,
        taskShape = shape,
        aggregateBudget = aggregate,
        inputReferences = listOf("evidence:root"),
        candidates = candidates
    )

    private fun allCandidates() =
        listOf(nanoCandidate(), microCandidate(), fullCandidate())

    private fun nanoCandidate(
        budget: AgentWorkBudget = nanoBudget
    ) = AgentTeamWorkerCandidate(
        workerClass = AgentWorkerClass.NANO,
        blueprint = AgentBlueprintReference(nanoBlueprint.id, nanoBlueprint.version),
        cognitiveScope = verifyScope,
        budget = budget,
        runtime = nanoRuntime
    )

    private fun microCandidate() = AgentTeamWorkerCandidate(
        workerClass = AgentWorkerClass.MICRO,
        blueprint = AgentBlueprintReference(microBlueprint.id, microBlueprint.version),
        cognitiveScope = broadScope,
        budget = microBudget,
        runtime = microRuntime
    )

    private fun fullCandidate() = AgentTeamWorkerCandidate(
        workerClass = AgentWorkerClass.FULL,
        blueprint = AgentBlueprintReference(fullBlueprint.id, fullBlueprint.version),
        cognitiveScope = broadScope,
        budget = fullBudget,
        runtime = fullRuntime
    )

    private fun coordinator(adapter: AgentRuntimeAdapter): AgentCoordinator {
        val delegate = coreFactory(adapter)
        return AgentCoordinator(
            factory = delegate,
            aggregateBudget = aggregate,
            workerFactory = AgentWorkerFactory(
                AgentWorkerAdmissionPolicy(profiles),
                delegate
            )
        )
    }

    private fun coreFactory(adapter: AgentRuntimeAdapter) = AgentFactory(
        registry = AgentBlueprintRegistry(
            listOf(nanoBlueprint, microBlueprint, fullBlueprint)
        ),
        admissionPolicy = AgentAdmissionPolicy(
            AgentFactoryBounds.PROTOTYPE,
            broadScope,
            fullBudget.copy(maxDescendants = 12)
        ),
        runtimeAdapter = adapter,
        auditLedger = AgentAuditLedger { },
        timeSource = { now }
    )
}
