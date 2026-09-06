package pro.liliya.android.semanticprovider

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.llamacppengine.AndroidLlamaCppCognitiveModelAssembly
import pro.liliya.android.llamacppengine.LlamaCppEnginePolicy
import pro.liliya.android.protectedmodel.staging.AndroidProtectedModelStagingPolicy
import pro.liliya.core.cognitive.CognitiveCompiledModelRequest
import pro.liliya.core.cognitive.CognitiveContextSnapshot
import pro.liliya.core.cognitive.CognitiveInferenceRequest
import pro.liliya.core.cognitive.CognitiveInferenceResult
import pro.liliya.core.cognitive.CognitiveInput
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
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemoryRecordSnapshot
import pro.liliya.core.memory.MemorySourceId
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

/**
 * Measures simultaneous residency of the real ONNX semantic runtime/index and the real llama.cpp
 * generation runtime in one Android process.
 *
 * The controlled stories15M GGUF is wiring/resource evidence only. It is deliberately not presented
 * as target production-LLM memory evidence.
 */
@RunWith(AndroidJUnit4::class)
class OfflineSemanticProviderCombinedEngineResourceInstrumentedTest {

    @Test
    fun records_real_onnx_20k_index_plus_active_llama_residency_and_inference() =
        withCleanRoots { targetContext, testContext ->
            val evidence = linkedMapOf<String, String>(
                "primaryAbi" to primaryRuntimeAbi(),
                "abi" to Build.SUPPORTED_ABIS.joinToString(","),
                "semanticEntryCount" to SEMANTIC_ENTRY_COUNT.toString(),
                "generationFixtureBytes" to STORIES_15M_BYTES.toString(),
                "generationFixtureClass" to "controlled-small-wiring-fixture",
                "targetProductionLlmEvidence" to "false"
            )

            val semanticRoot = File(targetContext.filesDir, SEMANTIC_ROOT).apply {
                deleteRecursively()
                check(mkdirs())
            }
            copyAsset(testContext, SemanticModelProfileV01.ONNX_FILE_NAME, semanticRoot)
            copyAsset(testContext, SemanticModelProfileV01.TOKENIZER_ONNX_FILE_NAME, semanticRoot)

            val semantic = AndroidOfflineSemanticProviderAssembly.create()
            assertEquals(
                AndroidOfflineSemanticProviderLoadResult.Loaded,
                semantic.load(
                    appPrivateRoot = semanticRoot,
                    encoderFile = File(semanticRoot, SemanticModelProfileV01.ONNX_FILE_NAME)
                )
            )
            val semanticMemory = memorySnapshots(SEMANTIC_MEMORY_ENTRY_COUNT)
            val semanticKnowledge = knowledgeSnapshots(SEMANTIC_KNOWLEDGE_ENTRY_COUNT)
            evidence["semanticMemoryEntryCount"] = semanticMemory.size.toString()
            evidence["semanticKnowledgeEntryCount"] = semanticKnowledge.size.toString()
            assertEquals(
                AndroidOfflineSemanticProviderRebuildResult.Ready(SEMANTIC_ENTRY_COUNT),
                semantic.rebuild(semanticMemory, semanticKnowledge)
            )

            forceGc()
            val semanticReadyPssBytes = processPssBytes()
            val semanticReadyNativeBytes = Debug.getNativeHeapAllocatedSize()
            evidence["semanticReadyPssBytes"] = semanticReadyPssBytes.toString()
            evidence["semanticReadyNativeHeapBytes"] = semanticReadyNativeBytes.toString()

            val foundation = foundation()
            val model = ProtectedModelReference(
                packageId = ProtectedModelPackageId("post-onnx-combined-resource-stories15m"),
                generation = ProtectedModelGeneration(1)
            )
            val ownership = ProtectedModelRuntimeOwnership().also { it.replaceTarget(model) }
            val llama = llamaAssembly(targetContext, foundation, ownership)
            val staged = testContext.assets.open(STORIES_15M_ASSET).use { input ->
                publishSegmented(llama.stagingCoordinator, input, model)
            }
            val activated = assertIs<CognitiveModelActivationResult.Activated>(
                llama.stagedActivation.activate(staged)
            )

            forceGc()
            val combinedActivePssBytes = processPssBytes()
            val combinedActiveNativeBytes = Debug.getNativeHeapAllocatedSize()
            evidence["combinedActivePssBytes"] = combinedActivePssBytes.toString()
            evidence["combinedActiveNativeHeapBytes"] = combinedActiveNativeBytes.toString()
            evidence["llamaActivationPssDeltaBytes"] =
                (combinedActivePssBytes - semanticReadyPssBytes).toString()
            evidence["llamaActivationNativeHeapDeltaBytes"] =
                (combinedActiveNativeBytes - semanticReadyNativeBytes).toString()

            val inference = assertIs<CognitiveInferenceResult.Succeeded>(
                llama.inferencePort.infer(inferenceRequest())
            )
            assertTrue(inference.output.isNotBlank())

            forceGc()
            val combinedAfterInferencePssBytes = processPssBytes()
            evidence["combinedAfterInferencePssBytes"] = combinedAfterInferencePssBytes.toString()
            evidence["combinedInferencePssDeltaBytes"] =
                (combinedAfterInferencePssBytes - combinedActivePssBytes).toString()

            if (primaryRuntimeAbi() == "arm64-v8a") {
                assertTrue(combinedActivePssBytes > 0L)
                assertTrue(combinedAfterInferencePssBytes > 0L)
            }

            assertIs<CognitiveModelQuiesceResult.Quiescing>(
                llama.cognitiveRuntime.beginQuiescing(activated.session)
            )
            assertIs<CognitiveModelRetirementResult.Retired>(
                llama.cognitiveRuntime.retireIfDrained(activated.session)
            )
            assertIs<LargeProtectedModelStagingRetireResult.Retired>(staged.retire())
            assertEquals(AndroidOfflineSemanticProviderCloseResult.Closed, semantic.close())

            forceGc()
            evidence["afterBothClosedPssBytes"] = processPssBytes().toString()
            writeEvidenceFile(evidence)
            recordEvidence(evidence)
        }

    private fun llamaAssembly(
        context: Context,
        foundation: FoundationComposition,
        protectedOwnership: ProtectedModelRuntimeOwnership
    ): AndroidLlamaCppCognitiveModelAssembly {
        val sessionIds = AtomicInteger(0)
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
                CognitiveCompiledModelRequest("Use the already loaded local model and answer briefly.")
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
                RuntimeModelSessionId("post-onnx-combined-${sessionIds.incrementAndGet()}")
            },
            limits = cognitiveLimits()
        )
    }

    private fun inferenceRequest(): CognitiveInferenceRequest {
        val turn = CognitiveTurnReference(
            id = CognitiveTurnId("post-onnx-combined-resource-turn"),
            generation = CognitiveTurnGeneration(1)
        )
        return CognitiveInferenceRequest(
            turn = turn,
            input = CognitiveInput("combined runtime resource probe"),
            context = CognitiveContextSnapshot(turn, emptyList()),
            maxOutputChars = MAX_OUTPUT_CHARS
        )
    }

    private fun memorySnapshots(count: Int): List<MemoryRecordSnapshot> =
        List(count) { index ->
            val ordinal = index + 1
            MemoryRecordSnapshot(
                record = MemoryRecord(
                    id = MemoryRecordId("combined-resource-memory-$ordinal"),
                    provenance = MemoryProvenance(MemorySourceId("combined-resource")),
                    content = "Local semantic memory $ordinal for combined engine residency evidence.",
                    createdAt = BASE.plusSeconds(ordinal.toLong())
                ),
                generation = MemoryGeneration(ordinal.toLong())
            )
        }

    private fun knowledgeSnapshots(count: Int): List<KnowledgeItemSnapshot> =
        List(count) { index ->
            val ordinal = index + 1
            KnowledgeItemSnapshot(
                item = KnowledgeItem(
                    id = KnowledgeItemId("combined-resource-knowledge-$ordinal"),
                    origin = KnowledgeOrigin.Declared(
                        sourceId = KnowledgeSourceId("combined-resource")
                    ),
                    content = "Local semantic knowledge $ordinal for combined engine residency evidence.",
                    createdAt = BASE.plusSeconds((SEMANTIC_MEMORY_ENTRY_COUNT + ordinal).toLong())
                ),
                generation = KnowledgeGeneration(ordinal.toLong())
            )
        }

    private fun foundation(): FoundationComposition {
        val logs = InMemoryLogWriter()
        val correlations = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator {
                "post-onnx-combined-resource-${correlations.incrementAndGet()}"
            }
        )
    }

    private fun cognitiveLimits() = CognitiveRuntimeLimits(
        maxInferenceOutputChars = MAX_OUTPUT_CHARS,
        maxModelPromptChars = MAX_MODEL_PROMPT_CHARS
    )

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
        maxPromptChars = MAX_MODEL_PROMPT_CHARS,
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

    private fun copyAsset(context: Context, name: String, root: File) {
        context.assets.open(name).use { input ->
            File(root, name).outputStream().use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
            }
        }
    }

    private fun processPssBytes(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss.toLong() * 1024L
    }

    private fun forceGc() {
        repeat(2) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            Thread.sleep(50)
        }
    }

    private fun primaryRuntimeAbi(): String =
        Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    private fun recordEvidence(values: Map<String, String>) {
        val bundle = Bundle()
        values.forEach { (key, value) -> bundle.putString("combinedResource.$key", value) }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, bundle)
    }

    private fun writeEvidenceFile(values: Map<String, String>) {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val target = File(targetContext.filesDir, RESOURCE_EVIDENCE_FILE_NAME)
        val json = JSONObject()
        values.forEach { (key, value) -> json.put(key, value) }
        target.writeText(json.toString(2) + "\n", Charsets.UTF_8)
    }

    private inline fun withCleanRoots(block: (Context, Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext.applicationContext
        val testContext = instrumentation.context
        val stagingRoot = File(targetContext.filesDir, "large-protected-model-staging-v1")
        val semanticRoot = File(targetContext.filesDir, SEMANTIC_ROOT)
        stagingRoot.deleteRecursively()
        semanticRoot.deleteRecursively()
        try {
            block(targetContext, testContext)
        } finally {
            stagingRoot.deleteRecursively()
            semanticRoot.deleteRecursively()
        }
    }

    private companion object {
        const val SEMANTIC_MEMORY_ENTRY_COUNT = 10_000
        const val SEMANTIC_KNOWLEDGE_ENTRY_COUNT = 10_000
        const val SEMANTIC_ENTRY_COUNT = SEMANTIC_MEMORY_ENTRY_COUNT + SEMANTIC_KNOWLEDGE_ENTRY_COUNT
        const val STORIES_15M_ASSET = "stories15M-q4_0.gguf"
        const val STORIES_15M_BYTES = 19_077_344L
        const val SEGMENT_BYTES = 256 * 1024
        const val MAX_OUTPUT_CHARS = 64
        const val MAX_MODEL_PROMPT_CHARS = 128
        const val SEMANTIC_ROOT = "post-onnx-combined-resource-semantic"
        const val RESOURCE_EVIDENCE_FILE_NAME = "post-onnx-combined-engine-resource-evidence.json"
        val BASE: Instant = Instant.parse("2026-09-05T15:30:00Z")
    }
}
