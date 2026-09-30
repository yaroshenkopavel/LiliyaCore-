package pro.liliya.app

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.asf.AgentAdmissionPolicy
import pro.liliya.core.asf.AgentAggregateBudget
import pro.liliya.core.asf.AgentAggregateUsage
import pro.liliya.core.asf.AgentArtifact
import pro.liliya.core.asf.AgentAuditLedger
import pro.liliya.core.asf.AgentBlueprint
import pro.liliya.core.asf.AgentBlueprintReference
import pro.liliya.core.asf.AgentBlueprintRegistry
import pro.liliya.core.asf.AgentBlueprintVersion
import pro.liliya.core.asf.AgentCognitiveScope
import pro.liliya.core.asf.AgentCoordinator
import pro.liliya.core.asf.AgentCoordinatorPlan
import pro.liliya.core.asf.AgentCoordinatorResult
import pro.liliya.core.asf.AgentCoordinatorRunWindow
import pro.liliya.core.asf.AgentCoordinatorStep
import pro.liliya.core.asf.AgentCoordinatorStepId
import pro.liliya.core.asf.AgentCoordinatorTerminalState
import pro.liliya.core.asf.AgentFactory
import pro.liliya.core.asf.AgentFactoryBounds
import pro.liliya.core.asf.AgentRootTaskId
import pro.liliya.core.asf.AgentRuntimeAdapter
import pro.liliya.core.asf.AgentRuntimeOutcome
import pro.liliya.core.asf.AgentRuntimeUsage
import pro.liliya.core.asf.AgentWorkBudget
import pro.liliya.core.asf.AgentWorkerAdmissionPolicy
import pro.liliya.core.asf.AgentWorkerClass
import pro.liliya.core.asf.AgentWorkerFactory
import pro.liliya.core.asf.AgentWorkerProfile
import pro.liliya.core.asf.AgentWorkerProfileSet
import pro.liliya.core.asf.AgentWorkerRuntimeDescriptor
import pro.liliya.core.asf.AgentWorkerRuntimeKind
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AsfHierarchicalPhysicalResourceInstrumentedTest {
    private val now = Instant.parse("2026-09-30T13:45:00Z")
    private val expires = Instant.parse("2026-09-30T14:45:00Z")
    private val rootTask = AgentRootTaskId("root-asf-h-physical")
    private val scope = AgentCognitiveScope.create(listOf("verification"))
    private val blueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "asf-h-physical-worker",
        "bounded-physical-comparison",
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
        "full-physical-synthetic-v1",
        AgentWorkerRuntimeKind.LLM,
        "synthetic-bounded-workload"
    )
    private val microRuntime = AgentWorkerRuntimeDescriptor(
        "micro-physical-synthetic-v1",
        AgentWorkerRuntimeKind.LLM,
        "synthetic-bounded-workload"
    )
    private val nanoRuntime = AgentWorkerRuntimeDescriptor(
        "nano-physical-synthetic-v1",
        AgentWorkerRuntimeKind.DETERMINISTIC
    )

    @Test
    fun physical_resource_profile_preserves_quality_and_emits_summary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext.applicationContext
        val mode = Mode.valueOf(
            requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_MODE)) {
                "missing fixed ASF-H physical mode"
            }
        )

        val coordinator = coordinator()
        val plan = plan(mode)

        repeat(WARMUP_ROUNDS) {
            val result = coordinator.runSequential(plan, AgentCoordinatorRunWindow(now, expires))
            assertCompleted(result)
        }

        System.gc()
        Thread.sleep(100)

        var totalUsage = AgentAggregateUsage()
        var finalResult: AgentCoordinatorResult? = null
        val startedNanos = SystemClock.elapsedRealtimeNanos()

        repeat(MEASURED_ROUNDS) {
            val result = coordinator.runSequential(plan, AgentCoordinatorRunWindow(now, expires))
            assertCompleted(result)
            totalUsage = add(totalUsage, result.aggregateUsage)
            finalResult = result
        }

        val elapsedMillis = (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000L
        val result = requireNotNull(finalResult)
        val fingerprint = qualityFingerprint(result.artifacts)

        assertEquals(EXPECTED_COMPLETED_STEPS, result.completedSteps)
        assertEquals(EXPECTED_ARTIFACTS, result.artifacts.size)
        assertTrue(fingerprint.isNotBlank())

        val summary = JSONObject()
            .put("evidenceClass", "asf-h-physical-synthetic-runtime-summary-v1")
            .put("mode", mode.name)
            .put("warmupRounds", WARMUP_ROUNDS)
            .put("measuredRounds", MEASURED_ROUNDS)
            .put("actualElapsedMillis", elapsedMillis)
            .put("completedStepsPerRound", result.completedSteps)
            .put("artifactCountPerRound", result.artifacts.size)
            .put("qualityFingerprint", fingerprint)
            .put("modeledWallClockMillis", totalUsage.wallClockMillis)
            .put("modeledInferenceUnits", totalUsage.inferenceUnits)
            .put("modeledContextBytes", totalUsage.contextBytes)
            .put("modeledRetrievalItems", totalUsage.retrievalItems)
            .put("modeledArtifactCount", totalUsage.artifactCount)
            .put("agentsStarted", totalUsage.agentsStarted)
            .put("physicalWorkSink", physicalWorkSink)

        File(targetContext.filesDir, summaryFile(mode)).writeText(
            summary.toString(2) + "\n",
            Charsets.UTF_8
        )

        println("ASF_H_PHYSICAL_SUMMARY=" + summary.toString())
    }

    private fun coordinator(): AgentCoordinator {
        val delegate = AgentFactory(
            registry = AgentBlueprintRegistry(listOf(blueprint)),
            admissionPolicy = AgentAdmissionPolicy(
                AgentFactoryBounds.PROTOTYPE,
                scope,
                fullBudget.copy(maxDescendants = 12)
            ),
            runtimeAdapter = physicalAdapter(),
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

    private fun plan(mode: Mode): AgentCoordinatorPlan {
        val verifier = if (mode == Mode.FULL_ONLY) {
            Triple(AgentWorkerClass.FULL, fullRuntime, fullLeafBudget)
        } else {
            Triple(AgentWorkerClass.MICRO, microRuntime, microBudget)
        }
        val atomic = if (mode == Mode.FULL_ONLY) {
            Triple(AgentWorkerClass.FULL, fullRuntime, fullLeafBudget)
        } else {
            Triple(AgentWorkerClass.NANO, nanoRuntime, nanoBudget)
        }
        return AgentCoordinatorPlan(
            rootTask,
            listOf(
                step(
                    "broad-root",
                    null,
                    AgentWorkerClass.FULL,
                    fullRuntime,
                    fullBudget,
                    "evidence:broad"
                ),
                step(
                    "narrow-verify",
                    "broad-root",
                    verifier.first,
                    verifier.second,
                    verifier.third,
                    "evidence:narrow"
                ),
                step(
                    "atomic-check",
                    "broad-root",
                    atomic.first,
                    atomic.second,
                    atomic.third,
                    "evidence:atomic"
                )
            )
        )
    }

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

    private fun physicalAdapter() = AgentRuntimeAdapter { context ->
        val input = context.workspace.inputReferences.single()
        val profile = when (context.budget.maxInferenceUnits) {
            fullBudget.maxInferenceUnits -> WorkProfile(
                loops = 120_000,
                usage = AgentRuntimeUsage(120, 1_000, 4_000, 4, 1)
            )
            microBudget.maxInferenceUnits -> WorkProfile(
                loops = 30_000,
                usage = AgentRuntimeUsage(40, 250, 1_000, 2, 1)
            )
            nanoBudget.maxInferenceUnits -> WorkProfile(
                loops = 3_000,
                usage = AgentRuntimeUsage(8, 25, 128, 0, 1)
            )
            else -> error("unexpected physical comparison budget")
        }
        burn(profile.loops)
        AgentRuntimeOutcome.Completed(
            kind = "comparison-result",
            payloadDigest = "sha256:$input",
            provenanceReferences = context.workspace.inputReferences,
            usage = profile.usage
        )
    }

    private fun burn(loops: Int) {
        var value = physicalWorkSink
        repeat(loops) { index ->
            value = (value xor (index.toLong() * 1_103_515_245L)) + 12_345L
            value = value xor (value ushr 17)
        }
        physicalWorkSink = value
    }

    private fun assertCompleted(result: AgentCoordinatorResult) {
        assertEquals(AgentCoordinatorTerminalState.COMPLETED, result.state)
        assertEquals(EXPECTED_COMPLETED_STEPS, result.completedSteps)
        assertEquals(EXPECTED_ARTIFACTS, result.artifacts.size)
    }

    private fun add(
        left: AgentAggregateUsage,
        right: AgentAggregateUsage
    ) = AgentAggregateUsage(
        wallClockMillis = Math.addExact(left.wallClockMillis, right.wallClockMillis),
        inferenceUnits = Math.addExact(left.inferenceUnits, right.inferenceUnits),
        contextBytes = Math.addExact(left.contextBytes, right.contextBytes),
        retrievalItems = Math.addExact(left.retrievalItems, right.retrievalItems),
        artifactCount = Math.addExact(left.artifactCount, right.artifactCount),
        agentsStarted = Math.addExact(left.agentsStarted, right.agentsStarted)
    )

    private fun qualityFingerprint(artifacts: List<AgentArtifact>): String =
        artifacts.joinToString("|") { artifact ->
            listOf(
                artifact.kind,
                artifact.payloadDigest,
                artifact.provenanceReferences.joinToString("+"),
                artifact.rootTaskId.value
            ).joinToString(":")
        }

    private fun summaryFile(mode: Mode): String =
        when (mode) {
            Mode.FULL_ONLY -> "asf-h-physical-full-only.json"
            Mode.HIERARCHICAL -> "asf-h-physical-hierarchical.json"
        }

    private data class WorkProfile(
        val loops: Int,
        val usage: AgentRuntimeUsage
    )

    private enum class Mode {
        FULL_ONLY,
        HIERARCHICAL
    }

    private companion object {
        const val ARG_MODE = "asf_h_mode"
        const val WARMUP_ROUNDS = 20
        const val MEASURED_ROUNDS = 160
        const val EXPECTED_COMPLETED_STEPS = 3
        const val EXPECTED_ARTIFACTS = 3

        @Volatile
        var physicalWorkSink: Long = 0L
    }
}
