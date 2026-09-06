package pro.liliya.android.semanticprovider.host

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.cognitivestorage.AndroidCognitiveStorageAssembly
import pro.liliya.android.cognitivestorage.AndroidCognitiveStorageOpenResult
import pro.liliya.android.cognitivestorage.AndroidEncryptedKnowledgeOpenResult
import pro.liliya.android.cognitivestorage.AndroidEncryptedMemoryOpenResult
import pro.liliya.android.llamacppengine.AndroidLlamaCppCognitiveModelAssembly
import pro.liliya.android.llamacppengine.LlamaCppEnginePolicy
import pro.liliya.android.protectedmodel.staging.AndroidProtectedModelStagingPolicy
import pro.liliya.android.runtime.AndroidHeartCognitiveRuntimeFactory
import pro.liliya.android.runtime.AndroidHeartRuntimeAssembly
import pro.liliya.android.runtime.HeartRuntimeCloseResult
import pro.liliya.android.runtime.HeartRuntimeStartResult
import pro.liliya.android.runtime.HeartRuntimeState
import pro.liliya.core.cognitive.CognitiveArtifactIdSource
import pro.liliya.core.cognitive.CognitiveCompiledModelRequest
import pro.liliya.core.cognitive.CognitiveContextAssemblyResult
import pro.liliya.core.cognitive.CognitiveGenerationResult
import pro.liliya.core.cognitive.CognitiveInput
import pro.liliya.core.cognitive.CognitiveMaterializationCandidate
import pro.liliya.core.cognitive.CognitiveMaterializationPort
import pro.liliya.core.cognitive.CognitiveMaterializationResult
import pro.liliya.core.cognitive.CognitiveModelRequestCompilerPort
import pro.liliya.core.cognitive.CognitiveModelRequestCompilerResult
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
import pro.liliya.core.encryption.CognitiveDekId
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitiveKeyProtectorCreationRequest
import pro.liliya.core.encryption.CognitiveKeyProtectorDescriptor
import pro.liliya.core.encryption.CognitiveKeyProtectorGeneration
import pro.liliya.core.encryption.CognitiveKeyProtectorId
import pro.liliya.core.encryption.CognitiveKeyProtectorSecurityLevel
import pro.liliya.core.encryption.PersistentCognitiveDekRegistrationResult
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.knowledge.PersistentKnowledgeCreateResult
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.memory.PersistentMemoryRememberResult
import pro.liliya.core.modelengine.ModelEngineLoadFailure
import pro.liliya.core.modelengine.ModelEngineLoadResult
import pro.liliya.core.modelengine.ModelEngineLoaderPort
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.PersistentStoreId
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

@RunWith(AndroidJUnit4::class)
class AndroidHeartRuntimeColdStartInstrumentedTest {

    @Test
    fun durable_reopen_to_heart_ready_real_turn_and_shutdown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val foundation = foundation()

        File(targetContext.filesDir, STORAGE_DIRECTORY).deleteRecursively()
        File(targetContext.filesDir, SEMANTIC_ROOT).deleteRecursively()

        val first = assertIs<AndroidCognitiveStorageOpenResult.Ready>(
            AndroidCognitiveStorageAssembly.open(
                context = targetContext,
                foundation = foundation,
                directoryName = STORAGE_DIRECTORY
            )
        ).assembly

        val descriptor = assertIs<CognitiveEncryptionResult.Success<CognitiveKeyProtectorDescriptor>>(
            first.keyProtector.create(
                CognitiveKeyProtectorCreationRequest(
                    id = CognitiveKeyProtectorId("heart-h3-" + System.nanoTime()),
                    generation = CognitiveKeyProtectorGeneration(1),
                    requestedSecurityLevel = CognitiveKeyProtectorSecurityLevel.SOFTWARE
                )
            )
        ).value
        val dek = assertIs<PersistentCognitiveDekRegistrationResult.Registered>(
            first.dekStore.register(CognitiveDekId("heart-h3-dek"), descriptor)
        ).ownership.reference

        val memoryStoreId = PersistentStoreId("heart-memory")
        val knowledgeStoreId = PersistentStoreId("heart-knowledge")

        val memory = assertIs<AndroidEncryptedMemoryOpenResult.Opened>(
            first.openEncryptedMemory(memoryStoreId, dek)
        ).composition
        val knowledge = assertIs<AndroidEncryptedKnowledgeOpenResult.Opened>(
            first.openEncryptedKnowledge(knowledgeStoreId, dek)
        ).composition

        assertIs<PersistentMemoryRememberResult.Remembered>(
            memory.remember(
                MemoryRecord(
                    id = MemoryRecordId("memory-keys"),
                    provenance = MemoryProvenance(MemorySourceId("heart-h3")),
                    content = RELEVANT_MEMORY,
                    createdAt = BASE
                )
            )
        )
        assertIs<PersistentKnowledgeCreateResult.Created>(
            knowledge.create(
                KnowledgeItem(
                    id = KnowledgeItemId("knowledge-bus"),
                    origin = KnowledgeOrigin.Declared(KnowledgeSourceId("heart-h3")),
                    content = RELEVANT_KNOWLEDGE,
                    createdAt = BASE.plusSeconds(1)
                )
            )
        )

        val reconstructed = assertIs<AndroidCognitiveStorageOpenResult.Ready>(
            AndroidCognitiveStorageAssembly.open(
                context = targetContext,
                foundation = foundation,
                directoryName = STORAGE_DIRECTORY
            )
        ).assembly

        val semanticRoot = File(targetContext.filesDir, SEMANTIC_ROOT).apply {
            deleteRecursively()
            check(mkdirs())
        }
        copyAsset(testContext, ENCODER_ASSET, semanticRoot)
        copyAsset(testContext, TOKENIZER_ASSET, semanticRoot)

        val model = ProtectedModelReference(
            packageId = ProtectedModelPackageId("heart-h3-stories15m"),
            generation = ProtectedModelGeneration(1)
        )
        val protectedOwnership = ProtectedModelRuntimeOwnership().also {
            it.replaceTarget(model)
        }
        val llama = llamaAssembly(targetContext, foundation, protectedOwnership)
        val staged = testContext.assets.open(STORIES_ASSET).use { input ->
            publishSegmented(llama.stagingCoordinator, input, model)
        }

        val ids = AtomicInteger(0)
        val heart = AndroidHeartRuntimeAssembly.create(
            cognitiveStorage = reconstructed,
            memoryStoreId = memoryStoreId,
            knowledgeStoreId = knowledgeStoreId,
            activeDek = dek,
            semanticRoot = semanticRoot,
            semanticEncoderFile = File(semanticRoot, ENCODER_ASSET),
            llamaAssembly = llama,
            stagedModel = staged,
            maxCandidatesPerSource = 4,
            cognitiveRuntimeFactory = AndroidHeartCognitiveRuntimeFactory {
                    memoryRetrieval,
                    knowledgeRetrieval,
                    inference,
                    streamingInference ->
                CognitiveRuntimeComposition(
                    foundation = foundation,
                    scope = CognitiveRuntimeScopeId("heart-h3-runtime"),
                    memoryRetrieval = memoryRetrieval,
                    knowledgeRetrieval = knowledgeRetrieval,
                    selfSnapshots = { null },
                    personalitySnapshots = { emptyList() },
                    inference = inference,
                    streamingInference = streamingInference,
                    limits = cognitiveLimits(),
                    materialization = CognitiveMaterializationPort {
                        CognitiveMaterializationResult.Succeeded(materializationCandidate())
                    },
                    planning = PlanningComposition(foundation),
                    reasoning = ReasoningComposition(foundation),
                    decision = DecisionComposition(foundation),
                    artifactIds = CognitiveArtifactIdSource { kind ->
                        "heart-" + kind.name.lowercase() + "-" + ids.incrementAndGet()
                    },
                    timestamps = CognitiveTimestampSource { BASE.plusSeconds(2) }
                )
            }
        )

        assertEquals(HeartRuntimeStartResult.Ready, heart.start())
        assertEquals(HeartRuntimeState.READY, heart.state())

        val runtime = assertNotNull(heart.runtime())
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            runtime.beginTurn(
                CognitiveTurnId("heart-h3-turn"),
                CognitiveInput("Where are my keys and which bus goes to the station?")
            )
        ).turn
        assertIs<CognitiveContextAssemblyResult.Published>(
            runtime.assembleContext(turn.reference)
        )
        assertIs<CognitiveGenerationResult.Succeeded>(
            runtime.generateCognition(turn.reference)
        )

        assertTrue(compilerSawMemory)
        assertTrue(compilerSawKnowledge)

        assertEquals(HeartRuntimeCloseResult.Closed, heart.close())
        assertEquals(HeartRuntimeState.CLOSED, heart.state())
        assertIs<LargeProtectedModelStagingRetireResult.Retired>(staged.retire())
        assertIs<CognitiveEncryptionResult.Success<Unit>>(
            first.keyProtector.retire(descriptor)
        )

        println(
            "HEART_H3_EVIDENCE=" +
                "{\"heartReady\":true,\"durableReopen\":true," +
                "\"semanticContext\":true,\"realLlamaInference\":true," +
                "\"shutdownClosed\":true}"
        )
    }

    @Volatile
    private var compilerSawMemory = false

    @Volatile
    private var compilerSawKnowledge = false

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
            compilerSawMemory = contents.contains(RELEVANT_MEMORY)
            compilerSawKnowledge = contents.contains(RELEVANT_KNOWLEDGE)
            check(compilerSawMemory)
            check(compilerSawKnowledge)
            CognitiveModelRequestCompilerResult.Compiled(
                CognitiveCompiledModelRequest(
                    ("Context: " + contents.joinToString(" | ") + ". Answer briefly.")
                        .take(MAX_MODEL_PROMPT_CHARS)
                )
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
                RuntimeModelSessionId("heart-h3-" + sessionIds.incrementAndGet())
            },
            limits = cognitiveLimits()
        )
    }

    private fun foundation(): FoundationComposition {
        val writer = InMemoryLogWriter()
        val correlations = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, writer) },
            correlationIds = CorrelationIdGenerator {
                "heart-h3-" + correlations.incrementAndGet()
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
        reasoningPremises = listOf("retrieved state is exact-generation revalidated"),
        reasoningAnalysis = "heart runtime completed real inference",
        reasoningConclusion = "produce bounded response",
        decisionOptions = listOf("respond", "do not respond"),
        selectedDecisionOptionIndex = 0,
        decisionRationale = "heart path succeeded"
    )

    private fun copyAsset(context: Context, name: String, root: File) {
        context.assets.open(name).use { input ->
            File(root, name).outputStream().use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
            }
        }
    }

    private fun stagingBudgets() = LargeProtectedModelStagingBudgets(
        maxTotalPlaintextBytes = STORIES_BYTES,
        maxSegmentPlaintextBytes = SEGMENT_BYTES.toLong(),
        maxSegmentCount = segmentCount(STORIES_BYTES),
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
        val expectedSegments = segmentCount(STORIES_BYTES)
        val started = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(
                LargeProtectedModelStagingRequest(
                    model = model,
                    profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
                    expectedPlaintextBytes = STORIES_BYTES,
                    expectedSegmentCount = expectedSegments
                )
            )
        )
        var segmentIndex = 0
        var appendedBytes = 0L
        while (segmentIndex < expectedSegments) {
            val remaining = STORIES_BYTES - appendedBytes
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
        assertEquals(STORIES_BYTES, appendedBytes)
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
            check(read > 0)
            offset += read
        }
        return buffer
    }

    private fun segmentCount(totalBytes: Long): Int =
        ((totalBytes + SEGMENT_BYTES - 1L) / SEGMENT_BYTES).toInt()

    private companion object {
        const val STORAGE_DIRECTORY = "heart-h3-storage"
        const val SEMANTIC_ROOT = "heart-h3-semantic"
        const val ENCODER_ASSET = "multilingual-e5-small-liliya-v0.1.onnx"
        const val TOKENIZER_ASSET = "multilingual-e5-small-tokenizer-v0.1.onnx"
        const val STORIES_ASSET = "stories15M-q4_0.gguf"
        const val STORIES_BYTES = 19_077_344L
        const val SEGMENT_BYTES = 4 * 1024 * 1024
        const val MAX_MODEL_PROMPT_CHARS = 384
        const val MAX_OUTPUT_CHARS = 64

        const val RELEVANT_MEMORY = "The keys are on the kitchen table."
        const val RELEVANT_KNOWLEDGE = "Bus twelve goes to the railway station."
        val BASE: Instant = Instant.parse("2026-09-07T00:00:00Z")
    }
}
