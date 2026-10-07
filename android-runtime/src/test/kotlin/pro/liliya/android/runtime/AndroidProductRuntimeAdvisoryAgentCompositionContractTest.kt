package pro.liliya.android.runtime

import java.time.Instant
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.asf.AgentAggregateBudget
import pro.liliya.core.asf.AgentAuditLedger
import pro.liliya.core.asf.AgentBlueprint
import pro.liliya.core.asf.AgentBlueprintReference
import pro.liliya.core.asf.AgentBlueprintVersion
import pro.liliya.core.asf.AgentCognitiveRuntimeAdapters
import pro.liliya.core.asf.AgentCognitiveRuntimeExecutionResult
import pro.liliya.core.asf.AgentCognitiveRuntimeRegistration
import pro.liliya.core.asf.AgentCognitiveScope
import pro.liliya.core.asf.AgentCoordinatorPlan
import pro.liliya.core.asf.AgentCoordinatorRunWindow
import pro.liliya.core.asf.AgentCoordinatorStep
import pro.liliya.core.asf.AgentCoordinatorStepId
import pro.liliya.core.asf.AgentCoordinatorTerminalState
import pro.liliya.core.asf.AgentRootTaskId
import pro.liliya.core.asf.AgentRuntimeUsage
import pro.liliya.core.asf.AgentWorkBudget
import pro.liliya.core.asf.AgentWorkerClass
import pro.liliya.core.asf.AgentWorkerProfile
import pro.liliya.core.asf.AgentWorkerProfileSet
import pro.liliya.core.asf.AgentWorkerRuntimeDescriptor
import pro.liliya.core.asf.AgentWorkerRuntimeKind

class AndroidProductRuntimeAdvisoryAgentCompositionContractTest {
    private val now = Instant.parse("2026-10-07T13:00:00Z")
    private val expires = Instant.parse("2026-10-07T13:01:00Z")
    private val scope = AgentCognitiveScope.create(listOf("verification"))
    private val blueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        role = "nano-verifier",
        objectiveClass = "bounded-advisory-verification",
        cognitiveScope = scope
    )
    private val nanoBudget = AgentWorkBudget(
        maxWallClockMillis = 1_000,
        maxInferenceUnits = 100,
        maxContextBytes = 4_096,
        maxRetrievalItems = 1,
        maxArtifacts = 1,
        maxDescendants = 0
    )
    private val microBudget = AgentWorkBudget(
        maxWallClockMillis = 5_000,
        maxInferenceUnits = 1_000,
        maxContextBytes = 16_384,
        maxRetrievalItems = 8,
        maxArtifacts = 4,
        maxDescendants = 1
    )
    private val fullBudget = AgentWorkBudget(
        maxWallClockMillis = 15_000,
        maxInferenceUnits = 5_000,
        maxContextBytes = 64_000,
        maxRetrievalItems = 32,
        maxArtifacts = 8,
        maxDescendants = 4
    )
    private val runtime = AgentWorkerRuntimeDescriptor(
        runtimeId = "android-product-nano-rule-v1",
        kind = AgentWorkerRuntimeKind.DETERMINISTIC
    )
    private val profiles = AgentWorkerProfileSet(
        listOf(
            AgentWorkerProfile(AgentWorkerClass.NANO, nanoBudget, 0, false),
            AgentWorkerProfile(AgentWorkerClass.MICRO, microBudget, 1, false),
            AgentWorkerProfile(AgentWorkerClass.FULL, fullBudget, 1, true)
        )
    )
    private val aggregate = AgentAggregateBudget(
        maxWallClockMillis = 20_000,
        maxInferenceUnits = 10_000,
        maxContextBytes = 128_000,
        maxRetrievalItems = 64,
        maxArtifacts = 16,
        maxAgents = 4
    )

    @Test
    fun exact_registered_deterministic_worker_runs_through_product_composition() {
        var calls = 0
        val ready = assertIs<AndroidProductRuntimeAdvisoryAgentCompositionResult.Ready>(
            AndroidProductRuntimeAdvisoryAgentComposition.create(
                blueprints = listOf(blueprint),
                globalScope = scope,
                globalBudget = fullBudget,
                workerProfiles = profiles,
                aggregateBudget = aggregate,
                registrations = listOf(
                    AgentCognitiveRuntimeRegistration(
                        descriptor = runtime,
                        available = { true },
                        adapter = AgentCognitiveRuntimeAdapters.deterministicRule { request ->
                            calls += 1
                            AgentCognitiveRuntimeExecutionResult.Completed.create(
                                artifactKind = "verification",
                                payloadDigest = "sha256:product-advisory-ok",
                                sourceReferences = request.context.workspace.inputReferences,
                                usage = AgentRuntimeUsage(
                                    wallClockMillis = 1,
                                    inferenceUnits = 0,
                                    contextBytes = 16,
                                    retrievalItems = 0,
                                    artifactCount = 1
                                )
                            )
                        }
                    )
                ),
                auditLedger = AgentAuditLedger { },
                timeSource = { now }
            )
        )

        val result = ready.host.run(
            plan = plan(),
            window = AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.COMPLETED, result.state)
        assertEquals(1, result.completedSteps)
        assertEquals(1, result.artifacts.size)
        assertEquals(1, calls)
    }

    @Test
    fun unavailable_exact_runtime_fails_closed_without_hidden_fallback() {
        var calls = 0
        val ready = assertIs<AndroidProductRuntimeAdvisoryAgentCompositionResult.Ready>(
            AndroidProductRuntimeAdvisoryAgentComposition.create(
                blueprints = listOf(blueprint),
                globalScope = scope,
                globalBudget = fullBudget,
                workerProfiles = profiles,
                aggregateBudget = aggregate,
                registrations = listOf(
                    AgentCognitiveRuntimeRegistration(
                        descriptor = runtime,
                        available = { false },
                        adapter = AgentCognitiveRuntimeAdapters.deterministicRule {
                            calls += 1
                            error("unavailable runtime must not execute")
                        }
                    )
                ),
                auditLedger = AgentAuditLedger { },
                timeSource = { now }
            )
        )

        val result = ready.host.run(
            plan = plan(),
            window = AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.FAILED, result.state)
        assertEquals(0, result.completedSteps)
        assertEquals(0, calls)
    }

    @Test
    fun empty_runtime_registry_is_rejected_before_product_host_exists() {
        val rejected = assertIs<AndroidProductRuntimeAdvisoryAgentCompositionResult.Rejected>(
            AndroidProductRuntimeAdvisoryAgentComposition.create(
                blueprints = listOf(blueprint),
                globalScope = scope,
                globalBudget = fullBudget,
                workerProfiles = profiles,
                aggregateBudget = aggregate,
                registrations = emptyList(),
                auditLedger = AgentAuditLedger { },
                timeSource = { now }
            )
        )

        assertEquals(
            AndroidProductRuntimeAdvisoryAgentCompositionFailure.EMPTY_RUNTIME_REGISTRATIONS,
            rejected.reason
        )
    }

    private fun plan(): AgentCoordinatorPlan =
        AgentCoordinatorPlan(
            rootTaskId = AgentRootTaskId("android-product-advisory-root"),
            steps = listOf(
                AgentCoordinatorStep.create(
                    id = AgentCoordinatorStepId("root"),
                    parentStepId = null,
                    blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
                    cognitiveScope = scope,
                    budget = nanoBudget,
                    inputReferences = listOf("evidence:product-advisory"),
                    workerClass = AgentWorkerClass.NANO,
                    runtime = runtime
                )
            )
        )
}
