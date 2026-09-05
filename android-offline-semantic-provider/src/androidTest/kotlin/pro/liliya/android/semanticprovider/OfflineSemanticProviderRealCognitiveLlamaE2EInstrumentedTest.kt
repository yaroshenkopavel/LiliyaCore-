package pro.liliya.android.semanticprovider

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.llamacppengine.AndroidLlamaCppCognitiveModelAssembly
import pro.liliya.android.llamacppengine.LlamaCppEnginePolicy
import pro.liliya.android.protectedmodel.staging.AndroidProtectedModelStagingPolicy
import pro.liliya.core.cognitive.CognitiveArtifactIdKind
import pro.liliya.core.cognitive.CognitiveArtifactIdSource
import pro.liliya.core.cognitive.CognitiveCompiledModelRequest
import pro.liliya.core.cognitive.CognitiveContextAssemblyResult
import pro.liliya.core.cognitive.CognitiveGenerationResult
import pro.liliya.core.cognitive.CognitiveInput
import pro.liliya.core.cognitive.CognitiveMaterializationCandidate
import pro.liliya.core.cognitive.CognitiveMaterializationPort
import pro.liliya.core.cognitive.CognitiveMaterializationResult
import pro.liliya.core.cognitive.CognitiveModelActivationResult
import pro.liliya.core.cognitive.CognitiveModelQuiesceResult
import pro.liliya.core.cognitive.CognitiveModelRequestCompilerPort
import pro.liliya.core.cognitive.CognitiveModelRequestCompilerResult
import pro.liliya.core.cognitive.CognitiveModelRetirementResult
import pro.liliya.core.cognitive.CognitiveModelRuntimeSessionIdSource
import pro.liliya.core.cognitive.CognitiveRuntimeComposition
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.CognitiveRuntimeScopeId
import pro.liliya.core.cognitive.CognitiveTimestampSource
import pro.liliya.core.cognitive.CognitiveTurnId
import pro.liliya.core.cognitive.CognitiveTurnRegistrationResult
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeComposition
import pro.liliya.core.knowledge.KnowledgeCreateResult
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryComposition
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemoryRememberResult
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.modelengine.ModelEngineLoadFailure
import pro.liliya.core.modelengine.ModelEngineLoadResult
import pro.liliya.core.modelengine.ModelEngineLoaderPort
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.planning.PlanningComposition
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
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.runtime.hardening.RuntimeModelSessionId

/**
 * Full real-engine wiring proof for Post-ONNX Production Integration v0.1.
 *
 * This test intentionally uses the real ONNX tokenizer/encoder and real llama.cpp native engine.
 * No fake embedding session participates in semantic discovery. Semantic candidates are resolved
 * by Core against authoritative Memory/Knowledge before the resulting Cognitive Context reaches
 * the production llama compiler/inference path.
 */
@RunWith(AndroidJUnit4::class)
class OfflineSemanticProviderRealCognitiveLlamaE2EInstrumentedTest {

    @Test
    fun real_onnx_context_reaches_real_llama_in_one_cognitive_turn() = withCleanRoots {
        targetContext, testContext ->
        val foundation = foundation()
        val memory = MemoryComposition(foundation)
        val knowledge = KnowledgeComposition(foundation)

        assertIs<MemoryRememberResult.Remembered>(
            memory.remember(
                memoryRecord(
                    id = "memory-keys",
                    content = RELEVANT_MEMORY,
                    seconds = 1
                )
            )
        )
        assertIs<MemoryRememberResult.Remembered>(
            memory.remember(
                memoryRecord(
                    id = "memory-whales",
                    content = "Whales migrate through the ocean every year.",
                    seconds = 2
                )
            )
        )
        assertIs<KnowledgeCreateResult.Created>(
            knowledge.create(
                knowledgeItem(
                    id = "knowledge-bus",
                    content = RELEVANT_KNOWLEDGE,
                    seconds = 3
                )
            )
        )
        assertIs<KnowledgeCreateResult.Created>(
            knowledge.create(
                knowledgeItem(
                    id = "knowledge-weather",
                    content = "Coastal weather is often windy in autumn.",
                    seconds = 4
                )
            )
        )

        val semantic = AndroidOfflineSemanticProviderAssembly.create()
        val semanticRoot = File(targetContext.filesDir, SEMANTIC_ROOT).apply {
            deleteRecursively()
            check(mkdirs())
        }
        copyAsset(testContext, SemanticModelProfileV01.ONNX_FILE_NAME, semanticRoot)
        copyAsset(testContext, SemanticModelProfileV01.TOKENIZER_ONNX_FILE_NAME, semanticRoot)

        assertEquals(
            AndroidOfflineSemanticProviderLoadResult.Loaded,
            semantic.load(
                appPrivateRoot = semanticRoot,
                encoderFile = File(semanticRoot, SemanticModelProfileV01.ONNX_FILE_NAME)
            )
        )
        assertEquals(
            AndroidOfflineSemanticProviderRebuildResult.Ready(4),
            semantic.rebuild(memory.snapshotEntries(), knowledge.snapshotEntries())
        )

        val retrieval = AndroidOfflineSemanticCognitiveRetrievalAssembly.create(
            semantic = semantic,
            memory = memory,
            knowledge = knowledge,
            maxCandidatesPerSource = 4
        )

        val model = ProtectedModelReference(
            packageId = ProtectedModelPackageId("post-onnx-real-e2e-stories15m"),
            generation = ProtectedModelGeneration(1)
        )
        val protectedOwnership = ProtectedModelRuntimeOwnership().also {
            it.replaceTarget(model)
        }
        val llama = llamaAssembly(
            context = targetContext,
            foundation = foundation,
            protectedOwnership = protectedOwnership
        )
        val staged = testContext.assets.open(STORIES_15M_ASSET).use { input ->
            publishSegmented(llama.stagingCoordinator, input, model)
        }
        val activated = assertIs<CognitiveModelActivationResult.Activated>(
            llama.stagedActivation.activate(staged)
        )

        val planning = PlanningComposition(foundation)
        val reasoning = ReasoningComposition(foundation)
        val decision = DecisionComposition(foundation)
        val idSequence = AtomicInteger(0)
        val runtime = CognitiveRuntimeComposition(
            foundation = foundation,
            scope = CognitiveRuntimeScopeId("post-onnx-real-e2e"),
            memoryRetrieval = retrieval.memoryRetrieval,
            knowledgeRetrieval = retrieval.knowledgeRetrieval,
            selfSnapshots = { null },
            personalitySnapshots = { emptyList() },
            inference = llama.inferencePort,
            limits = cognitiveLimits(),
            materialization = CognitiveMaterializationPort {
                CognitiveMaterializationResult.Succeeded(materializationCandidate())
            },
            planning = planning,
            reasoning = reasoning,
            decision = decision,
            artifactIds = CognitiveArtifactIdSource { kind ->
                "${kind.name.lowercase()}-${idSequence.incrementAndGet()}"
            },
            timestamps = CognitiveTimestampSource { BASE.plusSeconds(10) }
        )

        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            runtime.beginTurn(
                CognitiveTurnId("real-onnx-real-llama-turn"),
                CognitiveInput("Where are my keys and which bus should I take to the station?")
            )
        ).turn
        assertIs<CognitiveContextAssemblyResult.Published>(
            runtime.assembleContext(turn.reference)
        )
        assertIs<CognitiveGenerationResult.Succeeded>(
            runtime.generateCognition(turn.reference)
        )

        assertTrue(contextCompilerObservedRelevantMemory)
        assertTrue(contextCompilerObservedRelevantKnowledge)

        assertIs<CognitiveModelQuiesceResult.Quiescing>(
            llama.cognitiveRuntime.beginQuiescing(activated.session)
        )
        assertIs<CognitiveModelRetirementResult.Retired>(
            llama.cognitiveRuntime.retireIfDrained(activated.session)
        )
        assertIs<LargeProtectedModelStagingRetireResult.Retired>(staged.retire())
        assertEquals(AndroidOfflineSemanticProviderCloseResult.Closed, semantic.close())
    }

    @Volatile
    private var contextCompilerObservedRelevantMemory: Boolean = false

    @Volatile
    private var contextCompilerObservedRelevantKnowledge: Boolean = false

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
        val compiler = CognitiveModelRequestCompilerPort { request ->
            val contents = request.inference.context.items.map { it.content }
            contextCompilerObservedRelevantMemory = contents.contains(RELEVANT_MEMORY)
            contextCompilerObservedRelevantKnowledge = contents.contains(RELEVANT_KNOWLEDGE)
            check(contextCompilerObservedRelevantMemory) {
                "real semantic Memory context did not reach generation compiler"
            }
            check(contextCompilerObservedRelevantKnowledge) {
                "real semantic Knowledge context did not reach generation compiler"
            }
            val prompt = buildString {
                append("Context: ")
                append(contents.joinToString(" | "))
                append(". Answer briefly.")
            }.take(MAX_MODEL_PROMPT_CHARS)
            CognitiveModelRequestCompilerResult.Compiled(
                CognitiveCompiledModelRequest(prompt)
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
                RuntimeModelSessionId("post-onnx-e2e-llama-${sessionIds.incrementAndGet()}")
            },
            limits = cognitiveLimits()
        )
    }

    private fun foundation(): FoundationComposition {
        val logs = InMemoryLogWriter()
        val correlations = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator {
                "post-onnx-real-e2e-${correlations.incrementAndGet()}"
            }
        )
    }

    private fun cognitiveLimits() = CognitiveRuntimeLimits(
        maxRuntimeScopeIdChars = 64,
        maxTurnIdChars = 128,
        maxInputChars = 256,
        maxContextItems = 8,
        maxContextItemChars = 256,
        maxRetrievalResults = 2,
        maxInferenceOutputChars = MAX_OUTPUT_CHARS,
        maxModelPromptChars = MAX_MODEL_PROMPT_CHARS,
        maxPlanningGoalChars = 64,
        maxPlanningSteps = 4,
        maxPlanningStepChars = 64,
        maxReasoningPremises = 4,
        maxReasoningPremiseChars = 64,
        maxReasoningAnalysisChars = 64,
        maxReasoningConclusionChars = 64,
        maxDecisionOptions = 4,
        maxDecisionOptionChars = 64,
        maxDecisionRationaleChars = 64
    )

    private fun materializationCandidate() = CognitiveMaterializationCandidate(
        planningGoal = "respond to the user",
        planningSteps = listOf("use retrieved context"),
        reasoningPremises = listOf("retrieved context is advisory then revalidated"),
        reasoningAnalysis = "real engine path completed",
        reasoningConclusion = "produce bounded answer",
        decisionOptions = listOf("respond", "do not respond"),
        selectedDecisionOptionIndex = 0,
        decisionRationale = "real path succeeded"
    )

    private fun memoryRecord(id: String, content: String, seconds: Long) = MemoryRecord(
        id = MemoryRecordId(id),
        provenance = MemoryProvenance(MemorySourceId("post-onnx-e2e")),
        content = content,
        createdAt = BASE.plusSeconds(seconds)
    )

    private fun knowledgeItem(id: String, content: String, seconds: Long) = KnowledgeItem(
        id = KnowledgeItemId(id),
        origin = KnowledgeOrigin.Declared(KnowledgeSourceId("post-onnx-e2e")),
        content = content,
        createdAt = BASE.plusSeconds(seconds)
    )

    private fun copyAsset(testContext: Context, name: String, root: File) {
        testContext.assets.open(name).use { input ->
            File(root, name).outputStream().use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
            }
        }
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
        maxPromptTokens = 64,
        maxGeneratedTokens = 8,
        batchTokens = 64,
        microBatchTokens = 16,
        threadCount = 1,
        maxPromptChars = MAX_MODEL_PROMPT_CHARS,
        maxPromptUtf8Bytes = 512,
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

    private inline fun withCleanRoots(block: (Context, Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext.applicationContext
        val testContext = instrumentation.context
        val stagingRoot = File(targetContext.filesDir, "large-protected-model-staging-v1")
        val semanticRoot = File(targetContext.filesDir, SEMANTIC_ROOT)
        stagingRoot.deleteRecursively()
        semanticRoot.deleteRecursively()
        contextCompilerObservedRelevantMemory = false
        contextCompilerObservedRelevantKnowledge = false
        try {
            block(targetContext, testContext)
        } finally {
            stagingRoot.deleteRecursively()
            semanticRoot.deleteRecursively()
        }
    }

    private companion object {
        const val STORIES_15M_ASSET = "stories15M-q4_0.gguf"
        const val STORIES_15M_BYTES = 19_077_344L
        const val SEGMENT_BYTES = 256 * 1024
        const val MAX_OUTPUT_CHARS = 64
        const val MAX_MODEL_PROMPT_CHARS = 256
        const val SEMANTIC_ROOT = "post-onnx-real-e2e-semantic"
        const val RELEVANT_MEMORY = "I left the apartment keys on the kitchen table."
        const val RELEVANT_KNOWLEDGE = "Bus twelve goes to the railway station in the morning."
        val BASE: Instant = Instant.parse("2026-09-05T14:00:00Z")
    }
}
