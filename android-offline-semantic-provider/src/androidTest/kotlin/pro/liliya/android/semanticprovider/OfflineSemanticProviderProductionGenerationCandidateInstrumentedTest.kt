package pro.liliya.android.semanticprovider

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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
import pro.liliya.core.cognitive.MemoryRelevanceDiscoveryRequest
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
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
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAbortResult
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
class OfflineSemanticProviderProductionGenerationCandidateInstrumentedTest {

    @Test
    fun records_onnx_20k_plus_exact_qwen3_1_7b_q4km_residency_and_inference() =
        withCleanRoots { targetContext, testContext ->
            assertEquals("arm64-v8a", Build.SUPPORTED_ABIS.firstOrNull())

            val evidence = linkedMapOf(
                "evidenceClass" to "post-onnx-g3-production-generation-candidate",
                "primaryAbi" to Build.SUPPORTED_ABIS.first(),
                "semanticEntryCount" to SEMANTIC_ENTRY_COUNT.toString(),
                "generationModel" to QWEN_FILE_NAME,
                "generationModelBytes" to QWEN_BYTES.toString(),
                "generationModelSha256" to QWEN_SHA256,
                "generationModelRevision" to QWEN_REVISION,
                "generationQuantization" to "Q4_K_M",
                "targetProductionLlmEvidence" to "candidate-physical"
            )

            val semanticRoot = File(targetContext.filesDir, SEMANTIC_ROOT).apply {
                deleteRecursively()
                check(mkdirs())
            }
            copyAsset(testContext, SemanticModelProfileV01.ONNX_FILE_NAME, semanticRoot)
            copyAsset(testContext, SemanticModelProfileV01.TOKENIZER_ONNX_FILE_NAME, semanticRoot)

            val semantic = AndroidOfflineSemanticProviderAssembly.create()
            val semanticLoadStarted = SystemClock.elapsedRealtimeNanos()
            assertEquals(
                AndroidOfflineSemanticProviderLoadResult.Loaded,
                semantic.load(
                    appPrivateRoot = semanticRoot,
                    encoderFile = File(semanticRoot, SemanticModelProfileV01.ONNX_FILE_NAME)
                )
            )
            evidence["semanticLoadMs"] = elapsedMillis(semanticLoadStarted).toString()

            val semanticRebuildStarted = SystemClock.elapsedRealtimeNanos()
            assertEquals(
                AndroidOfflineSemanticProviderRebuildResult.Ready(SEMANTIC_ENTRY_COUNT),
                semantic.rebuild(
                    memorySnapshots(SEMANTIC_MEMORY_ENTRY_COUNT),
                    knowledgeSnapshots(SEMANTIC_KNOWLEDGE_ENTRY_COUNT)
                )
            )
            evidence["semanticRebuild20kMs"] = elapsedMillis(semanticRebuildStarted).toString()

            forceGc()
            val semanticReadyPss = processPssBytes()
            evidence["semanticReadyPssBytes"] = semanticReadyPss.toString()

            val foundation = foundation()
            val model = ProtectedModelReference(
                packageId = ProtectedModelPackageId("qwen3-1.7b-q4km-production-candidate"),
                generation = ProtectedModelGeneration(1)
            )
            val runtimeOwnership = ProtectedModelRuntimeOwnership().also { it.replaceTarget(model) }
            val llama = llamaAssembly(targetContext, foundation, runtimeOwnership)

            val stagingStarted = SystemClock.elapsedRealtimeNanos()
            val staged = provisionExactCandidate(llama.stagingCoordinator, model)
            evidence["candidateProvisionAndStageMs"] = elapsedMillis(stagingStarted).toString()

            forceGc()
            evidence["afterStagingPssBytes"] = processPssBytes().toString()

            val activationStarted = SystemClock.elapsedRealtimeNanos()
            val activated = assertIs<CognitiveModelActivationResult.Activated>(
                llama.stagedActivation.activate(staged)
            )
            evidence["llamaActivationMs"] = elapsedMillis(activationStarted).toString()

            forceGc()
            val combinedActivePss = processPssBytes()
            evidence["combinedActivePssBytes"] = combinedActivePss.toString()
            evidence["generationActivationPssDeltaBytes"] =
                (combinedActivePss - semanticReadyPss).toString()

            val turn = CognitiveTurnReference(
                id = CognitiveTurnId("g3-semantic-query-turn"),
                generation = CognitiveTurnGeneration(1)
            )
            val semanticQueryStarted = SystemClock.elapsedRealtimeNanos()
            val candidates = semantic.memoryRelevanceDiscovery.discover(
                MemoryRelevanceDiscoveryRequest(
                    turn = turn,
                    input = CognitiveInput("Where are the household keys and travel documents?"),
                    maxCandidates = 5
                )
            )
            assertTrue(candidates.candidates.isNotEmpty())
            evidence["semanticQueryWhileLlamaActiveMs"] =
                elapsedMillis(semanticQueryStarted).toString()
            evidence["semanticCandidatesWhileLlamaActive"] =
                candidates.candidates.size.toString()

            val inferenceStarted = SystemClock.elapsedRealtimeNanos()
            val inference = assertIs<CognitiveInferenceResult.Succeeded>(
                llama.inferencePort.infer(inferenceRequest())
            )
            evidence["generationInferenceMs"] = elapsedMillis(inferenceStarted).toString()
            assertTrue(inference.output.isNotBlank())
            evidence["generationOutputChars"] = inference.output.length.toString()

            forceGc()
            val combinedAfterInferencePss = processPssBytes()
            evidence["combinedAfterInferencePssBytes"] = combinedAfterInferencePss.toString()
            evidence["inferencePssDeltaBytes"] =
                (combinedAfterInferencePss - combinedActivePss).toString()

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

            val json = JSONObject()
            evidence.forEach { (key, value) -> json.put(key, value) }
            println("POST_ONNX_G3_EVIDENCE=" + json.toString())
            recordEvidence(evidence)
        }

    private fun provisionExactCandidate(
        coordinator: LargeProtectedModelStagingCoordinator,
        model: ProtectedModelReference
    ): LargeProtectedModelStagedSourceOwnership {
        val expectedSegments = segmentCount(QWEN_BYTES)
        val started = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(
                LargeProtectedModelStagingRequest(
                    model = model,
                    profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
                    expectedPlaintextBytes = QWEN_BYTES,
                    expectedSegmentCount = expectedSegments
                )
            )
        )

        val session = started.session
        var published = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val connection = (URL(QWEN_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                connection.connect()
                assertEquals(HttpURLConnection.HTTP_OK, connection.responseCode)
                if (connection.contentLengthLong > 0L) {
                    assertEquals(QWEN_BYTES, connection.contentLengthLong)
                }
                connection.inputStream.use { input ->
                    var segmentIndex = 0
                    while (total < QWEN_BYTES) {
                        val wanted = minOf(SEGMENT_BYTES.toLong(), QWEN_BYTES - total).toInt()
                        val segment = readExactSegment(input, wanted)
                        assertEquals(wanted, segment.size)
                        digest.update(segment)
                        assertIs<LargeProtectedModelStagingAppendResult.Appended>(
                            session.append(segmentIndex, segment)
                        )
                        total += segment.size.toLong()
                        segment.fill(0)
                        segmentIndex += 1
                    }
                    assertEquals(-1, input.read())
                }
            } finally {
                connection.disconnect()
            }

            assertEquals(QWEN_BYTES, total)
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSha != QWEN_SHA256) {
                assertIs<LargeProtectedModelStagingAbortResult.Aborted>(session.abort())
                error("candidate GGUF SHA-256 mismatch")
            }

            val ownership = assertIs<LargeProtectedModelStagingPublishResult.Published>(
                session.sealAndPublish()
            ).ownership
            published = true
            return ownership
        } finally {
            if (!published && coordinator.currentAttempt() == session.attempt) {
                session.abort()
            }
        }
    }

    private fun llamaAssembly(
        context: Context,
        foundation: FoundationComposition,
        protectedOwnership: ProtectedModelRuntimeOwnership
    ): AndroidLlamaCppCognitiveModelAssembly {
        val ids = AtomicInteger(0)
        val protectedAccess = ProtectedModelAccessCoordinator(
            policy = ProtectedModelAccessPolicy { ProtectedModelPolicyDecision.Allowed },
            ownership = protectedOwnership,
            loader = ProtectedModelPayloadLoader(
                verifier = ProtectedModelPackageVerifier(ProtectedModelSignerResolver { _, _ -> null }),
                dekResolver = ProtectedModelDekResolver { _, _ -> null },
                maxPlaintextSizeBytes = 1L
            )
        )
        val legacyLoader = ModelEngineLoaderPort { _, _ ->
            ModelEngineLoadResult.Rejected(ModelEngineLoadFailure.LOAD_REJECTED)
        }
        val compiler = CognitiveModelRequestCompilerPort {
            CognitiveModelRequestCompilerResult.Compiled(
                CognitiveCompiledModelRequest(QWEN_NON_THINKING_PROMPT)
            )
        }
        return AndroidLlamaCppCognitiveModelAssembly.create(
            context = context,
            stagingPolicy = AndroidProtectedModelStagingPolicy(freeSpaceReserveBytes = 0L),
            stagingBudgets = LargeProtectedModelStagingBudgets(
                maxTotalPlaintextBytes = QWEN_BYTES,
                maxSegmentPlaintextBytes = SEGMENT_BYTES.toLong(),
                maxSegmentCount = segmentCount(QWEN_BYTES),
                maxActiveAttempts = 1,
                maxOpaqueIdentifierChars = 64
            ),
            llamaPolicy = LlamaCppEnginePolicy(
                contextTokens = 2048,
                maxPromptTokens = 1536,
                maxGeneratedTokens = 64,
                batchTokens = 256,
                microBatchTokens = 64,
                threadCount = 4,
                maxPromptChars = 8192,
                maxPromptUtf8Bytes = 32768,
                maxOutputChars = 512,
                maxOutputUtf8Bytes = 2048,
                useMmap = true
            ),
            foundation = foundation,
            protectedAccess = protectedAccess,
            legacyEngineLoader = legacyLoader,
            compiler = compiler,
            sessionIds = CognitiveModelRuntimeSessionIdSource {
                RuntimeModelSessionId("g3-qwen-" + ids.incrementAndGet())
            },
            limits = CognitiveRuntimeLimits(
                maxInferenceOutputChars = 512,
                maxModelPromptChars = 8192
            )
        )
    }

    private fun inferenceRequest(): CognitiveInferenceRequest {
        val turn = CognitiveTurnReference(
            id = CognitiveTurnId("g3-qwen-inference-turn"),
            generation = CognitiveTurnGeneration(1)
        )
        return CognitiveInferenceRequest(
            turn = turn,
            input = CognitiveInput("What is two plus two?"),
            context = CognitiveContextSnapshot(turn, emptyList()),
            maxOutputChars = 512
        )
    }

    private fun memorySnapshots(count: Int): List<MemoryRecordSnapshot> =
        List(count) { index ->
            val ordinal = index + 1
            MemoryRecordSnapshot(
                record = MemoryRecord(
                    id = MemoryRecordId("g3-memory-" + ordinal),
                    provenance = MemoryProvenance(MemorySourceId("g3-resource")),
                    content = "Household memory " + ordinal + " about keys, travel documents, schedules and reminders.",
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
                    id = KnowledgeItemId("g3-knowledge-" + ordinal),
                    origin = KnowledgeOrigin.Declared(KnowledgeSourceId("g3-resource")),
                    content = "Reference knowledge " + ordinal + " about keys, travel documents, schedules and reminders.",
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
            correlationIds = CorrelationIdGenerator { "g3-" + correlations.incrementAndGet() }
        )
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
            File(root, name).outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
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

    private fun elapsedMillis(startedNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000L

    private fun recordEvidence(values: Map<String, String>) {
        val bundle = Bundle()
        values.forEach { (key, value) -> bundle.putString("postOnnxG3." + key, value) }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, bundle)
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
        const val SEMANTIC_ENTRY_COUNT = 20_000

        const val QWEN_FILE_NAME = "Qwen3-1.7B-Q4_K_M.gguf"
        const val QWEN_BYTES = 1_282_439_264L
        const val QWEN_SHA256 = "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"
        const val QWEN_REVISION = "daeb8e2d528a760970442092f6bf1e55c3b659eb"
        const val QWEN_URL =
            "https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/" +
                QWEN_REVISION + "/" + QWEN_FILE_NAME + "?download=true"

        const val QWEN_NON_THINKING_PROMPT =
            "<|im_start|>system\n" +
                "You are Liliya, a concise local assistant. Answer directly and briefly.<|im_end|>\n" +
                "<|im_start|>user\nWhat is two plus two? /no_think<|im_end|>\n" +
                "<|im_start|>assistant\n<think>\n\n</think>\n\n"

        const val SEGMENT_BYTES = 4 * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 120_000
        const val SEMANTIC_ROOT = "post-onnx-g3-semantic"
        val BASE: Instant = Instant.parse("2026-09-06T18:30:00Z")
    }
}
