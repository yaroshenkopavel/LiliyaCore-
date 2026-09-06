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
import pro.liliya.android.cognitivestorage.AndroidEncryptedLearningMutationOpenResult
import pro.liliya.android.llamacppengine.AndroidLlamaCppCognitiveModelAssembly
import pro.liliya.android.llamacppengine.LlamaCppEnginePolicy
import pro.liliya.android.protectedmodel.staging.AndroidProtectedModelStagingPolicy
import pro.liliya.android.runtime.AndroidHeartCognitiveRuntimeFactory
import pro.liliya.android.runtime.AndroidHeartGovernedLearningResult
import pro.liliya.android.runtime.AndroidHeartRuntimeAssembly
import pro.liliya.android.runtime.AndroidHeartSemanticLearningSyncStatus
import pro.liliya.android.runtime.HeartRuntimeCloseResult
import pro.liliya.android.runtime.HeartRuntimeStartResult
import pro.liliya.android.runtime.HeartRuntimeState
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityOwnershipResult
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.authority.DirectAuthorityGrantOwnershipResult
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.cognitive.CognitiveArtifactIdKind
import pro.liliya.core.cognitive.CognitiveArtifactIdSource
import pro.liliya.core.cognitive.CognitiveCompiledModelRequest
import pro.liliya.core.cognitive.CognitiveContextAssemblyResult
import pro.liliya.core.cognitive.CognitiveFinalizationResult
import pro.liliya.core.cognitive.CognitiveGenerationResult
import pro.liliya.core.cognitive.CognitiveGovernedLearningComposition
import pro.liliya.core.cognitive.CognitiveGovernedLearningResult
import pro.liliya.core.cognitive.CognitiveInput
import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationPort
import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationResult
import pro.liliya.core.cognitive.CognitiveLearningGovernancePort
import pro.liliya.core.cognitive.CognitiveLearningGovernanceResult
import pro.liliya.core.cognitive.CognitiveMaterializationCandidate
import pro.liliya.core.cognitive.CognitiveMaterializationPort
import pro.liliya.core.cognitive.CognitiveMaterializationResult
import pro.liliya.core.cognitive.CognitiveModelRequestCompilerPort
import pro.liliya.core.cognitive.CognitiveModelRequestCompilerResult
import pro.liliya.core.cognitive.CognitiveModelRuntimeSessionIdSource
import pro.liliya.core.cognitive.CognitiveOutcomeCandidate
import pro.liliya.core.cognitive.CognitiveOutcomeMaterializationPort
import pro.liliya.core.cognitive.CognitiveOutcomeMaterializationResult
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
import pro.liliya.core.learning.LearningApplicationAuthorityContract
import pro.liliya.core.learning.LearningApplicationComposition
import pro.liliya.core.learning.LearningApplicationAuthorizer
import pro.liliya.core.learning.LearningApplicationMutationAuthorizationGate
import pro.liliya.core.learning.LearningApplicationTarget
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.learning.LearningDecisionComposition
import pro.liliya.core.learning.LearningPolicy
import pro.liliya.core.learning.LearningPolicyComposition
import pro.liliya.core.learning.LearningPolicyId
import pro.liliya.core.learning.LearningPolicyInstallResult
import pro.liliya.core.learning.LearningPolicyReference
import pro.liliya.core.learning.inspectionPort
import pro.liliya.core.learning.preparationPort
import pro.liliya.core.learning.LearningApplicationPreflightValidator
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
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
import pro.liliya.core.reflection.ReflectionComposition
import pro.liliya.core.runtime.hardening.RuntimeModelSessionId

@RunWith(AndroidJUnit4::class)
class AndroidHeartRuntimeTwoTurnLearningInstrumentedTest {

    @Volatile
    private var learnedContextObservations: Int = 0

    @Test
    fun turn_a_learning_is_seen_by_turn_b_and_survives_restart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val foundation = foundation()

        File(targetContext.filesDir, STORAGE_DIRECTORY).deleteRecursively()
        File(targetContext.filesDir, SEMANTIC_ROOT).deleteRecursively()

        val provisioning = assertIs<AndroidCognitiveStorageOpenResult.Ready>(
            AndroidCognitiveStorageAssembly.open(
                context = targetContext,
                foundation = foundation,
                directoryName = STORAGE_DIRECTORY
            )
        ).assembly

        val descriptor = assertIs<CognitiveEncryptionResult.Success<CognitiveKeyProtectorDescriptor>>(
            provisioning.keyProtector.create(
                CognitiveKeyProtectorCreationRequest(
                    id = CognitiveKeyProtectorId("heart-h4d-" + System.nanoTime()),
                    generation = CognitiveKeyProtectorGeneration(1),
                    requestedSecurityLevel = CognitiveKeyProtectorSecurityLevel.SOFTWARE
                )
            )
        ).value
        val dek = assertIs<PersistentCognitiveDekRegistrationResult.Registered>(
            provisioning.dekStore.register(CognitiveDekId("heart-h4d-dek"), descriptor)
        ).ownership.reference

        val memoryStoreId = PersistentStoreId("heart-h4d-memory")
        val knowledgeStoreId = PersistentStoreId("heart-h4d-knowledge")
        val mutationStoreId = PersistentStoreId("heart-h4d-learning-mutations")

        val semanticRoot = File(targetContext.filesDir, SEMANTIC_ROOT).apply {
            deleteRecursively()
            check(mkdirs())
        }
        copyAsset(testContext, ENCODER_ASSET, semanticRoot)
        copyAsset(testContext, TOKENIZER_ASSET, semanticRoot)

        val model = ProtectedModelReference(
            packageId = ProtectedModelPackageId("heart-h4d-stories15m"),
            generation = ProtectedModelGeneration(1)
        )
        val protectedOwnership = ProtectedModelRuntimeOwnership().also {
            it.replaceTarget(model)
        }
        val llama = llamaAssembly(targetContext, foundation, protectedOwnership)
        val staged = testContext.assets.open(STORIES_ASSET).use { input ->
            publishSegmented(llama.stagingCoordinator, input, model)
        }

        val firstStorage = assertIs<AndroidCognitiveStorageOpenResult.Ready>(
            AndroidCognitiveStorageAssembly.open(
                context = targetContext,
                foundation = foundation,
                directoryName = STORAGE_DIRECTORY
            )
        ).assembly
        val encryptedMutations = assertIs<AndroidEncryptedLearningMutationOpenResult.Opened>(
            firstStorage.openEncryptedLearningMutations(mutationStoreId, dek)
        ).composition

        val firstHarness = runtimeHarness(
            foundation = foundation,
            scope = "heart-h4d-runtime-1",
            idPrefix = "heart-h4d-a"
        )

        val heart1 = AndroidHeartRuntimeAssembly.create(
            cognitiveStorage = firstStorage,
            memoryStoreId = memoryStoreId,
            knowledgeStoreId = knowledgeStoreId,
            activeDek = dek,
            semanticRoot = semanticRoot,
            semanticEncoderFile = File(semanticRoot, ENCODER_ASSET),
            llamaAssembly = llama,
            stagedModel = staged,
            maxCandidatesPerSource = 4,
            cognitiveRuntimeFactory = firstHarness.factory
        )

        assertEquals(HeartRuntimeStartResult.Ready, heart1.start())
        assertEquals(HeartRuntimeState.READY, heart1.state())

        val runtime1 = assertNotNull(heart1.runtime())
        val turnA = assertIs<CognitiveTurnRegistrationResult.Registered>(
            runtime1.beginTurn(
                CognitiveTurnId("heart-h4d-turn-a"),
                CognitiveInput(TURN_A_INPUT)
            )
        ).turn
        assertIs<CognitiveContextAssemblyResult.Published>(
            runtime1.assembleContext(turnA.reference)
        )
        assertIs<CognitiveGenerationResult.Succeeded>(
            runtime1.generateCognition(turnA.reference)
        )
        val finalizedA = assertIs<CognitiveFinalizationResult.Completed>(
            runtime1.finalizeCognition(turnA.reference)
        )

        val policies = LearningPolicyComposition(foundation)
        val learningDecisions = LearningDecisionComposition(foundation)
        val applications = LearningApplicationComposition(foundation)
        val authority = CapabilityAuthorityComposition(foundation)
        val principal = AuthorityPrincipal("heart-h4d-learning-system")

        val policy = assertIs<LearningPolicyInstallResult.Installed>(
            policies.install(
                LearningPolicy(
                    id = LearningPolicyId("heart-h4d-policy"),
                    rule = "allow governed durable memory learning for physical heart loop",
                    createdAt = BASE.plusSeconds(20)
                )
            )
        ).ownership

        assertIs<CapabilityOwnershipResult.Registered>(
            authority.registerCapability(
                CapabilityDescriptor(
                    id = LearningApplicationAuthorityContract.capability,
                    providerId = CapabilityProviderId("heart-h4d-learning")
                )
            )
        )
        assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            authority.registerDirectGrant(
                DirectAuthorityGrant(
                    principal = principal,
                    capability = LearningApplicationAuthorityContract.capability,
                    scope = LearningApplicationAuthorityContract.scopeFor(
                        LearningApplicationTarget.MEMORY
                    )
                )
            )
        )

        val preflight = LearningApplicationPreflightValidator(
            applications = applications,
            decisions = learningDecisions,
            candidates = firstHarness.learning,
            policies = policies
        )
        val authorizer = LearningApplicationAuthorizer(preflight, authority)
        val mutationGate = LearningApplicationMutationAuthorizationGate(
            encryptedMutations.inspectionPort(),
            authorizer
        )
        val mutationApplication = assertNotNull(
            heart1.learningMutationApplicationPort(
                foundation = foundation,
                mutations = encryptedMutations,
                authorizationGate = mutationGate
            )
        )

        val governedCore = CognitiveGovernedLearningComposition(
            foundation = foundation,
            scope = CognitiveRuntimeScopeId("heart-h4d-runtime-1"),
            learning = firstHarness.learning,
            policies = policies,
            policyReference = LearningPolicyReference(policy.policy.id, policy.generation),
            governance = CognitiveLearningGovernancePort {
                CognitiveLearningGovernanceResult.Approved(
                    target = LearningApplicationTarget.MEMORY,
                    rationale = "trusted physical heart-loop approval"
                )
            },
            decisions = learningDecisions,
            materialization = CognitiveLearningApplicationMaterializationPort {
                CognitiveLearningApplicationMaterializationResult.Succeeded(
                    LEARNED_CONTENT
                )
            },
            applications = applications,
            mutations = encryptedMutations.preparationPort(),
            mutationApplier = mutationApplication,
            principal = principal,
            allowedTargets = listOf(LearningApplicationTarget.MEMORY),
            artifactIds = firstHarness.ids,
            timestamps = firstHarness.timestamps,
            limits = firstHarness.limits
        )
        val governedHeart = assertNotNull(heart1.governedLearning(governedCore))
        val learnedResult: AndroidHeartGovernedLearningResult =
            governedHeart.process(finalizedA.learning)

        assertIs<CognitiveGovernedLearningResult.Applied>(learnedResult.governed)
        assertEquals(
            AndroidHeartSemanticLearningSyncStatus.SYNCHRONIZED,
            learnedResult.semanticSync
        )

        val turnB = assertIs<CognitiveTurnRegistrationResult.Registered>(
            runtime1.beginTurn(
                CognitiveTurnId("heart-h4d-turn-b"),
                CognitiveInput(LEARNED_CONTENT)
            )
        ).turn
        val contextB = assertIs<CognitiveContextAssemblyResult.Published>(
            runtime1.assembleContext(turnB.reference)
        )
        assertTrue(contextB.itemCount >= 1)
        assertIs<CognitiveGenerationResult.Succeeded>(
            runtime1.generateCognition(turnB.reference)
        )
        assertTrue(learnedContextObservations >= 1)

        assertEquals(HeartRuntimeCloseResult.Closed, heart1.close())
        assertEquals(HeartRuntimeState.CLOSED, heart1.state())

        val secondStorage = assertIs<AndroidCognitiveStorageOpenResult.Ready>(
            AndroidCognitiveStorageAssembly.open(
                context = targetContext,
                foundation = foundation(),
                directoryName = STORAGE_DIRECTORY
            )
        ).assembly

        val secondHarness = runtimeHarness(
            foundation = foundation,
            scope = "heart-h4d-runtime-2",
            idPrefix = "heart-h4d-c"
        )
        val heart2 = AndroidHeartRuntimeAssembly.create(
            cognitiveStorage = secondStorage,
            memoryStoreId = memoryStoreId,
            knowledgeStoreId = knowledgeStoreId,
            activeDek = dek,
            semanticRoot = semanticRoot,
            semanticEncoderFile = File(semanticRoot, ENCODER_ASSET),
            llamaAssembly = llama,
            stagedModel = staged,
            maxCandidatesPerSource = 4,
            cognitiveRuntimeFactory = secondHarness.factory
        )

        assertEquals(HeartRuntimeStartResult.Ready, heart2.start())
        val runtime2 = assertNotNull(heart2.runtime())
        val turnC = assertIs<CognitiveTurnRegistrationResult.Registered>(
            runtime2.beginTurn(
                CognitiveTurnId("heart-h4d-turn-c"),
                CognitiveInput(LEARNED_CONTENT)
            )
        ).turn
        val contextC = assertIs<CognitiveContextAssemblyResult.Published>(
            runtime2.assembleContext(turnC.reference)
        )
        assertTrue(contextC.itemCount >= 1)
        assertIs<CognitiveGenerationResult.Succeeded>(
            runtime2.generateCognition(turnC.reference)
        )
        assertTrue(learnedContextObservations >= 2)

        assertEquals(HeartRuntimeCloseResult.Closed, heart2.close())
        assertIs<LargeProtectedModelStagingRetireResult.Retired>(staged.retire())
        assertIs<CognitiveEncryptionResult.Success<Unit>>(
            provisioning.keyProtector.retire(descriptor)
        )

        println(
            "HEART_H4D_EVIDENCE=" +
                "{\"heartReady\":true,\"turnAFinalized\":true," +
                "\"governedDurableLearning\":true,\"semanticSync\":true," +
                "\"turnBObservedLearnedState\":true,\"restartObservedLearnedState\":true," +
                "\"shutdownClosed\":true}"
        )
    }

    private data class RuntimeHarness(
        val factory: AndroidHeartCognitiveRuntimeFactory,
        val learning: LearningComposition,
        val ids: CognitiveArtifactIdSource,
        val timestamps: CognitiveTimestampSource,
        val limits: CognitiveRuntimeLimits
    )

    private fun runtimeHarness(
        foundation: FoundationComposition,
        scope: String,
        idPrefix: String
    ): RuntimeHarness {
        val planning = PlanningComposition(foundation)
        val reasoning = ReasoningComposition(foundation)
        val decision = DecisionComposition(foundation)
        val reflection = ReflectionComposition(foundation)
        val learning = LearningComposition(foundation)
        val perKind = mutableMapOf<CognitiveArtifactIdKind, Int>()
        val ids = CognitiveArtifactIdSource { kind ->
            val next = (perKind[kind] ?: 0) + 1
            perKind[kind] = next
            idPrefix + "-" + kind.name.lowercase().replace('_', '-') + "-" + next
        }
        val clock = AtomicInteger(0)
        val timestamps = CognitiveTimestampSource {
            BASE.plusSeconds(100 + clock.incrementAndGet().toLong())
        }
        val limits = cognitiveLimits()

        val factory = AndroidHeartCognitiveRuntimeFactory {
                memoryRetrieval,
                knowledgeRetrieval,
                inference,
                streamingInference ->
            CognitiveRuntimeComposition(
                foundation = foundation,
                scope = CognitiveRuntimeScopeId(scope),
                memoryRetrieval = memoryRetrieval,
                knowledgeRetrieval = knowledgeRetrieval,
                selfSnapshots = { null },
                personalitySnapshots = { emptyList() },
                inference = inference,
                streamingInference = streamingInference,
                limits = limits,
                materialization = CognitiveMaterializationPort {
                    CognitiveMaterializationResult.Succeeded(materializationCandidate())
                },
                planning = planning,
                reasoning = reasoning,
                decision = decision,
                artifactIds = ids,
                timestamps = timestamps,
                outcomeMaterialization = CognitiveOutcomeMaterializationPort {
                    CognitiveOutcomeMaterializationResult.Succeeded(
                        CognitiveOutcomeCandidate(
                            resultContent = "turn result",
                            reflectionContent = "turn reflection",
                            learningProposal = "learn one durable memory"
                        )
                    )
                },
                reflection = reflection,
                learning = learning
            )
        }
        return RuntimeHarness(factory, learning, ids, timestamps, limits)
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
        val compiler = CognitiveModelRequestCompilerPort { request ->
            val contents = request.inference.context.items.map { it.content }
            if (contents.contains(LEARNED_CONTENT)) {
                learnedContextObservations += 1
            }
            val prompt = buildString {
                append("User: ")
                append(request.inference.input.text)
                append(". Context: ")
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
                RuntimeModelSessionId("heart-h4d-" + sessionIds.incrementAndGet())
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
                "heart-h4d-" + correlations.incrementAndGet()
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
        const val STORAGE_DIRECTORY = "heart-h4d-storage"
        const val SEMANTIC_ROOT = "heart-h4d-semantic"
        const val ENCODER_ASSET = "multilingual-e5-small-liliya-v0.1.onnx"
        const val TOKENIZER_ASSET = "multilingual-e5-small-tokenizer-v0.1.onnx"
        const val STORIES_ASSET = "stories15M-q4_0.gguf"
        const val STORIES_BYTES = 19_077_344L
        const val SEGMENT_BYTES = 4 * 1024 * 1024
        const val MAX_MODEL_PROMPT_CHARS = 384
        const val MAX_OUTPUT_CHARS = 64
        const val TURN_A_INPUT = "Please remember something useful from this turn."
        const val LEARNED_CONTENT = "The durable heart phrase is blue comet."
        val BASE: Instant = Instant.parse("2026-09-07T01:30:00Z")
    }
}
