package pro.liliya.android.llamacppengine

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.protectedmodel.staging.AndroidProtectedModelStagingPolicy
import pro.liliya.core.cognitive.CognitiveCompiledModelRequest
import pro.liliya.core.cognitive.CognitiveContextSnapshot
import pro.liliya.core.cognitive.CognitiveInferenceRequest
import pro.liliya.core.cognitive.CognitiveInferenceResult
import pro.liliya.core.cognitive.CognitiveInput
import pro.liliya.core.cognitive.CognitiveModelActivationFailure
import pro.liliya.core.cognitive.CognitiveModelActivationResult
import pro.liliya.core.cognitive.CognitiveModelQuiesceResult
import pro.liliya.core.cognitive.CognitiveModelRequestCompilerPort
import pro.liliya.core.cognitive.CognitiveModelRequestCompilerResult
import pro.liliya.core.cognitive.CognitiveModelRetirementResult
import pro.liliya.core.cognitive.CognitiveModelRuntimeSessionIdSource
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.CognitiveTurnGeneration
import pro.liliya.core.cognitive.CognitiveTurnId
import pro.liliya.core.cognitive.CognitiveTurnReference
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.modelengine.ModelEngineLoadFailure
import pro.liliya.core.modelengine.ModelEngineLoadResult
import pro.liliya.core.modelengine.ModelEngineLoaderPort
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.protectedmodel.LargeProtectedModelPayloadProfile
import pro.liliya.core.protectedmodel.LargeProtectedModelStagedSourceOwnership
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAppendResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCoordinator
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingPublishResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingRequest
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingRetireResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingStartResult
import pro.liliya.core.protectedmodel.ProtectedModelAccessCoordinator
import pro.liliya.core.protectedmodel.ProtectedModelAccessPolicy
import pro.liliya.core.protectedmodel.ProtectedModelDekResolver
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelPackageVerifier
import pro.liliya.core.protectedmodel.ProtectedModelPayloadLoader
import pro.liliya.core.protectedmodel.ProtectedModelPolicyDecision
import pro.liliya.core.protectedmodel.ProtectedModelReference
import pro.liliya.core.protectedmodel.ProtectedModelRuntimeOwnership
import pro.liliya.core.protectedmodel.ProtectedModelSignerResolver
import pro.liliya.core.runtime.hardening.RuntimeModelSessionId

@RunWith(AndroidJUnit4::class)
class AndroidLlamaCppCognitiveAssemblyInstrumentedTest {

    @Test
    fun verified_fixture_runs_through_production_staging_cognitive_runtime_and_retires_exactly() =
        withCleanRoot { targetContext, testContext ->
            val model = ProtectedModelReference(
                packageId = ProtectedModelPackageId("production-assembly-stories15m"),
                generation = ProtectedModelGeneration(1)
            )
            val protectedOwnership = ProtectedModelRuntimeOwnership().also {
                it.replaceTarget(model)
            }
            val assembly = assembly(targetContext, protectedOwnership)
            val staged = testContext.assets.open(STORIES_15M_ASSET).use { input ->
                publishSegmented(
                    coordinator = assembly.stagingCoordinator,
                    input = input,
                    model = model
                )
            }

            val activated = assertIs<CognitiveModelActivationResult.Activated>(
                assembly.stagedActivation.activate(staged)
            )
            assertEquals(activated.session, assembly.cognitiveRuntime.currentSession())

            val inference = assertIs<CognitiveInferenceResult.Succeeded>(
                assembly.inferencePort.infer(inferenceRequest())
            )
            assertTrue(inference.output.isNotBlank())
            assertTrue(inference.output.length <= MAX_OUTPUT_CHARS)

            assertIs<CognitiveModelQuiesceResult.Quiescing>(
                assembly.cognitiveRuntime.beginQuiescing(activated.session)
            )
            assertIs<CognitiveModelRetirementResult.Retired>(
                assembly.cognitiveRuntime.retireIfDrained(activated.session)
            )
            assertEquals(null, assembly.cognitiveRuntime.currentSession())
            assertIs<LargeProtectedModelStagingRetireResult.Retired>(staged.retire())
        }

    @Test
    fun retired_exact_staged_ownership_cannot_activate_a_runtime_session() =
        withCleanRoot { targetContext, testContext ->
            val model = ProtectedModelReference(
                packageId = ProtectedModelPackageId("production-assembly-retired-source"),
                generation = ProtectedModelGeneration(1)
            )
            val protectedOwnership = ProtectedModelRuntimeOwnership().also {
                it.replaceTarget(model)
            }
            val assembly = assembly(targetContext, protectedOwnership)
            val staged = testContext.assets.open(STORIES_15M_ASSET).use { input ->
                publishSegmented(
                    coordinator = assembly.stagingCoordinator,
                    input = input,
                    model = model
                )
            }

            assertIs<LargeProtectedModelStagingRetireResult.Retired>(staged.retire())
            val rejected = assertIs<CognitiveModelActivationResult.Rejected>(
                assembly.stagedActivation.activate(staged)
            )
            assertEquals(CognitiveModelActivationFailure.ENGINE_LOAD_REJECTED, rejected.reason)
            assertEquals(null, assembly.cognitiveRuntime.currentSession())
        }

    private fun assembly(
        context: Context,
        protectedOwnership: ProtectedModelRuntimeOwnership
    ): AndroidLlamaCppCognitiveModelAssembly {
        val logs = InMemoryLogWriter()
        val correlations = AtomicInteger(0)
        val sessionIds = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { loggerContext -> StructuredLogger(loggerContext, logs) },
            correlationIds = CorrelationIdGenerator {
                "android-production-assembly-${correlations.incrementAndGet()}"
            }
        )
        val protectedAccess = ProtectedModelAccessCoordinator(
            policy = ProtectedModelAccessPolicy { ProtectedModelPolicyDecision.Allowed },
            ownership = protectedOwnership,
            loader = ProtectedModelPayloadLoader(
                verifier = ProtectedModelPackageVerifier(
                    ProtectedModelSignerResolver { _, _ -> null }
                ),
                dekResolver = ProtectedModelDekResolver { _, _ -> null },
                maxPlaintextSizeBytes = 1L
            )
        )
        val legacyLoader = ModelEngineLoaderPort { _, _ ->
            ModelEngineLoadResult.Rejected(ModelEngineLoadFailure.LOAD_REJECTED)
        }
        val compiler = CognitiveModelRequestCompilerPort {
            CognitiveModelRequestCompilerResult.Compiled(
                CognitiveCompiledModelRequest("Once upon a time")
            )
        }

        return AndroidLlamaCppCognitiveModelAssembly.create(
            context = context,
            stagingPolicy = AndroidProtectedModelStagingPolicy(freeSpaceReserveBytes = 0L),
            stagingBudgets = stagingBudgets(),
            llamaPolicy = enginePolicy(),
            foundation = foundation,
            protectedAccess = protectedAccess,
            legacyEngineLoader = legacyLoader,
            compiler = compiler,
            sessionIds = CognitiveModelRuntimeSessionIdSource {
                RuntimeModelSessionId("android-llama-runtime-${sessionIds.incrementAndGet()}")
            },
            limits = CognitiveRuntimeLimits(
                maxInferenceOutputChars = MAX_OUTPUT_CHARS,
                maxModelPromptChars = 128
            )
        )
    }

    private fun inferenceRequest(): CognitiveInferenceRequest {
        val turn = CognitiveTurnReference(
            id = CognitiveTurnId("android-production-real-model-turn"),
            generation = CognitiveTurnGeneration(1)
        )
        return CognitiveInferenceRequest(
            turn = turn,
            input = CognitiveInput("real model production assembly probe"),
            context = CognitiveContextSnapshot(turn, emptyList()),
            maxOutputChars = MAX_OUTPUT_CHARS
        )
    }

    private fun stagingBudgets() = LargeProtectedModelStagingBudgets(
        maxTotalPlaintextBytes = STORIES_15M_BYTES,
        maxSegmentPlaintextBytes = SEGMENT_BYTES.toLong(),
        maxSegmentCount = segmentCount(STORIES_15M_BYTES),
        maxActiveAttempts = 1,
        maxOpaqueIdentifierChars = 64
    )

    private fun enginePolicy() = LlamaCppEnginePolicy(
        contextTokens = 128,
        maxPromptTokens = 32,
        maxGeneratedTokens = 8,
        batchTokens = 32,
        microBatchTokens = 16,
        threadCount = 1,
        maxPromptChars = 128,
        maxPromptUtf8Bytes = 256,
        maxOutputChars = MAX_OUTPUT_CHARS,
        maxOutputUtf8Bytes = 256,
        useMmap = true
    )

    private fun publishSegmented(
        coordinator: LargeProtectedModelStagingCoordinator,
        input: InputStream,
        model: ProtectedModelReference
    ): LargeProtectedModelStagedSourceOwnership {
        val expectedSegments = segmentCount(STORIES_15M_BYTES)
        val started = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(
                LargeProtectedModelStagingRequest(
                    model = model,
                    profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
                    expectedPlaintextBytes = STORIES_15M_BYTES,
                    expectedSegmentCount = expectedSegments
                )
            )
        )

        var segmentIndex = 0
        var appendedBytes = 0L
        while (segmentIndex < expectedSegments) {
            val remaining = STORIES_15M_BYTES - appendedBytes
            val wanted = minOf(SEGMENT_BYTES.toLong(), remaining).toInt()
            val segment = readExactSegment(input, wanted)
            assertEquals(wanted, segment.size)
            assertIs<LargeProtectedModelStagingAppendResult.Appended>(
                started.session.append(segmentIndex, segment)
            )
            appendedBytes += segment.size.toLong()
            segment.fill(0)
            segmentIndex += 1
        }

        assertEquals(STORIES_15M_BYTES, appendedBytes)
        assertEquals(-1, input.read())
        return assertIs<LargeProtectedModelStagingPublishResult.Published>(
            started.session.sealAndPublish()
        ).ownership
    }

    private fun readExactSegment(input: InputStream, wanted: Int): ByteArray {
        val buffer = ByteArray(wanted)
        var offset = 0
        while (offset < wanted) {
            val read = input.read(buffer, offset, wanted - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == buffer.size) buffer else buffer.copyOf(offset)
    }

    private fun segmentCount(totalBytes: Long): Int =
        ((totalBytes + SEGMENT_BYTES - 1L) / SEGMENT_BYTES).toInt()

    private inline fun withCleanRoot(block: (Context, Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext.applicationContext
        val testContext = instrumentation.context
        val root = File(targetContext.filesDir, "large-protected-model-staging-v1")
        root.deleteRecursively()
        try {
            block(targetContext, testContext)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val STORIES_15M_ASSET = "stories15M-q4_0.gguf"
        const val STORIES_15M_BYTES = 19_077_344L
        const val SEGMENT_BYTES = 256 * 1024
        const val MAX_OUTPUT_CHARS = 64
    }
}
