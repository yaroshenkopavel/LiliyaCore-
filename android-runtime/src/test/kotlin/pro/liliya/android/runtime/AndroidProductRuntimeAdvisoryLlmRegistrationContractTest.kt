package pro.liliya.android.runtime

import java.time.Instant
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.asf.AgentBlueprintId
import pro.liliya.core.asf.AgentBlueprintReference
import pro.liliya.core.asf.AgentBlueprintVersion
import pro.liliya.core.asf.AgentCognitiveRuntimeExecutionRequest
import pro.liliya.core.asf.AgentCognitiveRuntimeExecutionResult
import pro.liliya.core.asf.AgentCognitiveScope
import pro.liliya.core.asf.AgentInstanceGeneration
import pro.liliya.core.asf.AgentInstanceId
import pro.liliya.core.asf.AgentRuntimeContext
import pro.liliya.core.asf.AgentRuntimeUsage
import pro.liliya.core.asf.AgentWorkBudget
import pro.liliya.core.asf.AgentWorkerRuntimeDescriptor
import pro.liliya.core.asf.AgentWorkerRuntimeKind
import pro.liliya.core.asf.AgentWorkspace
import pro.liliya.core.cognitive.CognitiveContextSnapshot
import pro.liliya.core.cognitive.CognitiveInferenceRequest
import pro.liliya.core.cognitive.CognitiveInferenceResult
import pro.liliya.core.cognitive.CognitiveInput
import pro.liliya.core.cognitive.CognitiveTurnGeneration
import pro.liliya.core.cognitive.CognitiveTurnId
import pro.liliya.core.cognitive.CognitiveTurnReference

class AndroidProductRuntimeAdvisoryLlmRegistrationContractTest {
    private val descriptor = AgentWorkerRuntimeDescriptor(
        runtimeId = "android-product-llm-v1",
        kind = AgentWorkerRuntimeKind.LLM,
        modelId = "qwen3-1.7b-q4-k-m"
    )
    private val turn = CognitiveTurnReference(
        CognitiveTurnId("agent-turn"),
        CognitiveTurnGeneration(1)
    )

    @Test
    fun exact_model_registration_runs_existing_cognitive_inference_port() {
        var inferenceCalls = 0
        val registration = AndroidProductRuntimeAdvisoryLlmRegistration.create(
            descriptor = descriptor,
            available = { true },
            contextCompiler = AndroidProductRuntimeAdvisoryLlmContextCompiler {
                AndroidProductRuntimeAdvisoryLlmCompiledRequest(
                    inference = CognitiveInferenceRequest(
                        turn = turn,
                        input = CognitiveInput("bounded advisory request"),
                        context = CognitiveContextSnapshot(turn, emptyList()),
                        maxOutputChars = 64
                    ),
                    sourceReferences = listOf("evidence:agent-input")
                )
            },
            inference = {
                inferenceCalls += 1
                CognitiveInferenceResult.Succeeded(turn, "advisory result")
            },
            usageMeter = AndroidProductRuntimeAdvisoryLlmUsageMeter { request, _, _, elapsed ->
                AgentRuntimeUsage(
                    wallClockMillis = elapsed,
                    inferenceUnits = 7,
                    contextBytes = request.context.workspace.contextBytes,
                    retrievalItems = 0,
                    artifactCount = 1
                )
            },
            nanoTime = sequenceNanoTime(10_000_000L, 13_000_000L)
        )

        val result = registration.adapter.run(executionRequest(descriptor))

        val completed = assertIs<AgentCognitiveRuntimeExecutionResult.Completed>(result)
        assertEquals("llm-advisory", completed.artifactKind)
        assertTrue(completed.payloadDigest.startsWith("sha256:"))
        assertEquals(listOf("evidence:agent-input"), completed.sourceReferences)
        assertEquals(3, completed.usage.wallClockMillis)
        assertEquals(7, completed.usage.inferenceUnits)
        assertEquals(1, inferenceCalls)
    }

    @Test
    fun successful_provider_result_fails_closed_when_usage_meter_does_not_count_artifact() {
        val registration = AndroidProductRuntimeAdvisoryLlmRegistration.create(
            descriptor = descriptor,
            available = { true },
            contextCompiler = {
                AndroidProductRuntimeAdvisoryLlmCompiledRequest(
                    inference = CognitiveInferenceRequest(
                        turn = turn,
                        input = CognitiveInput("bounded advisory request"),
                        context = CognitiveContextSnapshot(turn, emptyList())
                    ),
                    sourceReferences = listOf("evidence:agent-input")
                )
            },
            inference = {
                CognitiveInferenceResult.Succeeded(turn, "advisory result")
            },
            usageMeter = AndroidProductRuntimeAdvisoryLlmUsageMeter { request, _, _, elapsed ->
                AgentRuntimeUsage(
                    wallClockMillis = elapsed,
                    inferenceUnits = 1,
                    contextBytes = request.context.workspace.contextBytes,
                    retrievalItems = 0,
                    artifactCount = 0
                )
            },
            nanoTime = sequenceNanoTime(30_000_000L, 31_000_000L)
        )

        val failed = assertIs<AgentCognitiveRuntimeExecutionResult.Failed>(
            registration.adapter.run(executionRequest(descriptor))
        )

        assertEquals("advisory LLM usage metering contract violated", failed.reason)
        assertEquals(0, failed.usage.artifactCount)
        assertEquals(1, failed.usage.inferenceUnits)
    }

    @Test
    fun llm_registration_requires_exact_model_identity() {
        val withoutModel = descriptor.copy(modelId = null)

        assertFailsWith<IllegalArgumentException> {
            AndroidProductRuntimeAdvisoryLlmRegistration.create(
                descriptor = withoutModel,
                available = { true },
                contextCompiler = { error("must not compile") },
                inference = { error("must not infer") },
                usageMeter = AndroidProductRuntimeAdvisoryLlmUsageMeter { _, _, _, _ ->
                    error("must not meter")
                }
            )
        }
    }

    @Test
    fun provider_exception_fails_closed_without_output_or_provenance_fabrication() {
        val registration = AndroidProductRuntimeAdvisoryLlmRegistration.create(
            descriptor = descriptor,
            available = { true },
            contextCompiler = {
                AndroidProductRuntimeAdvisoryLlmCompiledRequest(
                    inference = CognitiveInferenceRequest(
                        turn = turn,
                        input = CognitiveInput("bounded advisory request"),
                        context = CognitiveContextSnapshot(turn, emptyList())
                    ),
                    sourceReferences = listOf("evidence:agent-input")
                )
            },
            inference = { throw IllegalStateException("sensitive provider detail") },
            usageMeter = AndroidProductRuntimeAdvisoryLlmUsageMeter { request, _, result, elapsed ->
                assertEquals(null, result)
                AgentRuntimeUsage(
                    wallClockMillis = elapsed,
                    inferenceUnits = 5,
                    contextBytes = request.context.workspace.contextBytes,
                    retrievalItems = 0,
                    artifactCount = 0
                )
            },
            nanoTime = sequenceNanoTime(20_000_000L, 24_000_000L)
        )

        val failed = assertIs<AgentCognitiveRuntimeExecutionResult.Failed>(
            registration.adapter.run(executionRequest(descriptor))
        )

        assertEquals("advisory LLM inference provider failed", failed.reason)
        assertEquals(4, failed.usage.wallClockMillis)
        assertEquals(5, failed.usage.inferenceUnits)
        assertEquals(0, failed.usage.artifactCount)
    }

    @Test
    fun public_llm_registration_api_contains_no_authority_or_action_execution_types() {
        val forbidden = listOf(
            "AuthorityPrincipal",
            "CapabilityAuthority",
            "ControlledAutonomyExecution",
            "GovernedClosedLoopActionGateway",
            "License"
        )

        val signatures = AndroidProductRuntimeAdvisoryLlmRegistration::class.java.methods
            .filter {
                it.declaringClass == AndroidProductRuntimeAdvisoryLlmRegistration::class.java
            }
            .flatMap { method ->
                listOf(method.returnType.name) + method.parameterTypes.map { it.name }
            }

        forbidden.forEach { marker ->
            kotlin.test.assertFalse(
                signatures.any { marker in it },
                "advisory LLM registration must not expose $marker: $signatures"
            )
        }
    }

    private fun executionRequest(
        runtime: AgentWorkerRuntimeDescriptor
    ): AgentCognitiveRuntimeExecutionRequest {
        val instanceId = AgentInstanceId("agent-instance")
        val workspace = AgentWorkspace.create(
            instanceId = instanceId,
            inputReferences = listOf("evidence:agent-input"),
            maxContextBytes = 1024
        )
        return AgentCognitiveRuntimeExecutionRequest(
            descriptor = runtime,
            context = AgentRuntimeContext(
                instanceId = instanceId,
                generation = AgentInstanceGeneration(1),
                blueprint = AgentBlueprintReference(
                    AgentBlueprintId("advisory-blueprint"),
                    AgentBlueprintVersion(1)
                ),
                scope = AgentCognitiveScope.create(listOf("assistant")),
                budget = AgentWorkBudget(
                    maxWallClockMillis = 10_000,
                    maxInferenceUnits = 100,
                    maxContextBytes = 1024,
                    maxRetrievalItems = 8,
                    maxArtifacts = 2,
                    maxDescendants = 0
                ),
                workspace = workspace,
                startedAt = Instant.parse("2026-10-07T15:00:00Z"),
                expiresAt = Instant.parse("2026-10-07T15:01:00Z"),
                workerRuntime = runtime
            )
        )
    }

    private fun sequenceNanoTime(
        first: Long,
        second: Long
    ): () -> Long {
        val values = ArrayDeque(listOf(first, second))
        return { values.removeFirst() }
    }
}
