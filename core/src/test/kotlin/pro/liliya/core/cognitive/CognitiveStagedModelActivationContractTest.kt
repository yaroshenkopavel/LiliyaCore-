package pro.liliya.core.cognitive

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.modelengine.ModelEngineBackendId
import pro.liliya.core.modelengine.ModelEngineCloseFailure
import pro.liliya.core.modelengine.ModelEngineCloseResult
import pro.liliya.core.modelengine.ModelEngineHandleId
import pro.liliya.core.modelengine.ModelEngineInferenceRequest
import pro.liliya.core.modelengine.ModelEngineInferenceResult
import pro.liliya.core.modelengine.ModelEngineLoadFailure
import pro.liliya.core.modelengine.ModelEngineLoadResult
import pro.liliya.core.modelengine.ModelEngineLoaderPort
import pro.liliya.core.modelengine.ModelEngineSessionOwnership
import pro.liliya.core.modelengine.StagedModelEngineLoadCoordinator
import pro.liliya.core.modelengine.StagedModelEngineLoaderPort
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.protectedmodel.LargeProtectedModelOpaqueArtifactId
import pro.liliya.core.protectedmodel.LargeProtectedModelPayloadProfile
import pro.liliya.core.protectedmodel.LargeProtectedModelSealedArtifactCandidate
import pro.liliya.core.protectedmodel.LargeProtectedModelStagedSourceOwnership
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAppendBackendResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAppendResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAttemptReference
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBackend
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBackendId
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCoordinator
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingDeleteResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingDurabilityLevel
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingFailure
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingPrepareResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingPublishResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingRequest
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingRetireResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingSealResult
import pro.liliya.core.protectedmodel.LargeProtectedModelWorkingArtifactHandle
import pro.liliya.core.protectedmodel.ProtectedModelAccessCoordinator
import pro.liliya.core.protectedmodel.ProtectedModelAccessPolicy
import pro.liliya.core.protectedmodel.ProtectedModelDekResolver
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelPackageVerifier
import pro.liliya.core.protectedmodel.ProtectedModelPayloadLoader
import pro.liliya.core.protectedmodel.ProtectedModelPolicyDecision
import pro.liliya.core.protectedmodel.ProtectedModelPolicyFailure
import pro.liliya.core.protectedmodel.ProtectedModelReference
import pro.liliya.core.protectedmodel.ProtectedModelRuntimeOwnership
import pro.liliya.core.protectedmodel.ProtectedModelSignerResolver
import pro.liliya.core.runtime.hardening.RuntimeModelSessionId
import pro.liliya.core.runtime.hardening.RuntimeModelSessionLifecycle

class CognitiveStagedModelActivationContractTest {
    @Test
    fun exact_staged_ownership_activates_existing_runtime_binding_and_releases_lease_on_retirement() {
        val engine = FakeEngine()
        val fixture = fixture(engine = engine)

        val activated = assertIs<CognitiveModelActivationResult.Activated>(
            fixture.composition.activateStagedModel(fixture.ownership)
        )
        assertEquals(activated.session, fixture.composition.currentSession())
        assertEquals(RuntimeModelSessionLifecycle.ACTIVE, fixture.composition.currentLifecycle())
        assertEquals(1, fixture.policyCalls.get())
        assertEquals(1, fixture.loaderCalls.get())

        val blocked = assertIs<LargeProtectedModelStagingRetireResult.Rejected>(
            fixture.ownership.retire()
        )
        assertEquals(LargeProtectedModelStagingFailure.RETIRE_IN_USE, blocked.reason)

        assertIs<CognitiveInferenceResult.Succeeded>(
            fixture.composition.inferencePort.infer(inferenceRequest())
        )
        assertEquals(1, engine.inferCalls)

        assertIs<CognitiveModelQuiesceResult.Quiescing>(
            fixture.composition.beginQuiescing(activated.session)
        )
        assertIs<CognitiveModelRetirementResult.Retired>(
            fixture.composition.retireIfDrained(activated.session)
        )
        assertEquals(1, engine.closeCalls)
        assertIs<LargeProtectedModelStagingRetireResult.Retired>(fixture.ownership.retire())
    }

    @Test
    fun protected_policy_rejection_precedes_staged_engine_load() {
        val fixture = fixture(
            policy = ProtectedModelAccessPolicy {
                ProtectedModelPolicyDecision.Rejected(
                    ProtectedModelPolicyFailure.ENTITLEMENT_REJECTED
                )
            }
        )

        val rejected = assertIs<CognitiveModelActivationResult.Rejected>(
            fixture.composition.activateStagedModel(fixture.ownership)
        )
        assertEquals(CognitiveModelActivationFailure.PROTECTED_ACCESS_REJECTED, rejected.reason)
        assertEquals(1, fixture.policyCalls.get())
        assertEquals(0, fixture.loaderCalls.get())
        assertNull(fixture.composition.currentSession())
        assertIs<LargeProtectedModelStagingRetireResult.Retired>(fixture.ownership.retire())
    }

    @Test
    fun target_replacement_after_staged_load_fails_final_publication_and_compensates_exact_engine() {
        val engine = FakeEngine()
        lateinit var fixture: Fixture
        fixture = fixture(
            engine = engine,
            onLoad = {
                fixture.protectedOwnership.replaceTarget(reference(2))
            }
        )

        val rejected = assertIs<CognitiveModelActivationResult.Rejected>(
            fixture.composition.activateStagedModel(fixture.ownership)
        )
        assertEquals(CognitiveModelActivationFailure.PROTECTED_ACCESS_REJECTED, rejected.reason)
        assertEquals(1, engine.closeCalls)
        assertNull(fixture.composition.currentSession())
        assertIs<LargeProtectedModelStagingRetireResult.Retired>(fixture.ownership.retire())
    }

    @Test
    fun failed_stale_compensation_retains_lease_and_pending_cleanup_blocks_new_staged_activation() {
        val engine = FakeEngine(
            closeResults = ArrayDeque<ModelEngineCloseResult>().apply {
                add(ModelEngineCloseResult.Failed(ModelEngineCloseFailure.CLOSE_FAILED))
                add(ModelEngineCloseResult.Closed)
            }
        )
        lateinit var fixture: Fixture
        fixture = fixture(
            engine = engine,
            onLoad = {
                fixture.protectedOwnership.replaceTarget(reference(2))
            }
        )

        val failed = assertIs<CognitiveModelActivationResult.Failed>(
            fixture.composition.activateStagedModel(fixture.ownership)
        )
        assertEquals(CognitiveModelActivationFailure.CLEANUP_FAILED, failed.reason)
        assertEquals(1, engine.closeCalls)

        val blockedRetire = assertIs<LargeProtectedModelStagingRetireResult.Rejected>(
            fixture.ownership.retire()
        )
        assertEquals(LargeProtectedModelStagingFailure.RETIRE_IN_USE, blockedRetire.reason)

        val busy = assertIs<CognitiveModelActivationResult.Rejected>(
            fixture.composition.activateStagedModel(fixture.ownership)
        )
        assertEquals(CognitiveModelActivationFailure.BUSY, busy.reason)
        assertEquals(1, fixture.loaderCalls.get())

        assertIs<CognitiveModelCleanupResult.Cleaned>(
            fixture.composition.recoverPendingActivationCleanup()
        )
        assertEquals(2, engine.closeCalls)
        assertIs<LargeProtectedModelStagingRetireResult.Retired>(fixture.ownership.retire())
    }

    private data class Fixture(
        val composition: CognitiveModelRuntimeComposition,
        val protectedOwnership: ProtectedModelRuntimeOwnership,
        val ownership: LargeProtectedModelStagedSourceOwnership,
        val policyCalls: AtomicInteger,
        val loaderCalls: AtomicInteger
    )

    private fun fixture(
        engine: FakeEngine = FakeEngine(),
        policy: ProtectedModelAccessPolicy = ProtectedModelAccessPolicy {
            ProtectedModelPolicyDecision.Allowed
        },
        onLoad: () -> Unit = {}
    ): Fixture {
        val model = reference(1)
        val protectedOwnership = ProtectedModelRuntimeOwnership().also { it.replaceTarget(model) }
        val policyCalls = AtomicInteger(0)
        val protectedAccess = ProtectedModelAccessCoordinator(
            policy = ProtectedModelAccessPolicy { reference ->
                policyCalls.incrementAndGet()
                policy.decide(reference)
            },
            ownership = protectedOwnership,
            loader = ProtectedModelPayloadLoader(
                verifier = ProtectedModelPackageVerifier(
                    ProtectedModelSignerResolver { _, _ -> null }
                ),
                dekResolver = ProtectedModelDekResolver { _, _ -> null },
                maxPlaintextSizeBytes = 1L
            )
        )

        val staging = LargeProtectedModelStagingCoordinator(
            backend = FakeBackend(),
            budgets = LargeProtectedModelStagingBudgets(
                maxTotalPlaintextBytes = 100,
                maxSegmentPlaintextBytes = 16,
                maxSegmentCount = 8,
                maxActiveAttempts = 1,
                maxOpaqueIdentifierChars = 64
            )
        )
        val ownership = publish(staging, model)
        val loaderCalls = AtomicInteger(0)
        val stagedLoader = StagedModelEngineLoadCoordinator(
            stagingCoordinator = staging,
            loader = StagedModelEngineLoaderPort {
                loaderCalls.incrementAndGet()
                onLoad()
                ModelEngineLoadResult.Loaded(engine)
            }
        )

        val logs = InMemoryLogWriter()
        val correlations = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator {
                "staged-model-${correlations.incrementAndGet()}"
            }
        )
        val ids = AtomicInteger(0)
        val composition = CognitiveModelRuntimeComposition(
            foundation = foundation,
            protectedAccess = protectedAccess,
            engineLoader = ModelEngineLoaderPort { _, _ ->
                ModelEngineLoadResult.Rejected(ModelEngineLoadFailure.LOAD_REJECTED)
            },
            compiler = DeterministicCognitiveModelRequestCompiler(),
            sessionIds = CognitiveModelRuntimeSessionIdSource {
                RuntimeModelSessionId("staged-runtime-${ids.incrementAndGet()}")
            },
            stagedEngineLoader = stagedLoader
        )

        return Fixture(
            composition = composition,
            protectedOwnership = protectedOwnership,
            ownership = ownership,
            policyCalls = policyCalls,
            loaderCalls = loaderCalls
        )
    }

    private fun publish(
        staging: LargeProtectedModelStagingCoordinator,
        model: ProtectedModelReference
    ): LargeProtectedModelStagedSourceOwnership {
        val started = assertIs<pro.liliya.core.protectedmodel.LargeProtectedModelStagingStartResult.Started>(
            staging.start(
                LargeProtectedModelStagingRequest(
                    model = model,
                    profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
                    expectedPlaintextBytes = 5,
                    expectedSegmentCount = 1
                )
            )
        )
        assertIs<LargeProtectedModelStagingAppendResult.Appended>(
            started.session.append(0, "alpha".encodeToByteArray())
        )
        return assertIs<LargeProtectedModelStagingPublishResult.Published>(
            started.session.sealAndPublish()
        ).ownership
    }

    private fun inferenceRequest(): CognitiveInferenceRequest {
        val turn = CognitiveTurnReference(
            CognitiveTurnId("staged-turn"),
            CognitiveTurnGeneration(1)
        )
        return CognitiveInferenceRequest(
            turn = turn,
            input = CognitiveInput("private staged input"),
            context = CognitiveContextSnapshot(turn, emptyList()),
            maxOutputChars = 1024
        )
    }

    private fun reference(generation: Long): ProtectedModelReference =
        ProtectedModelReference(
            ProtectedModelPackageId("staged-model"),
            ProtectedModelGeneration(generation)
        )

    private class FakeBackend : LargeProtectedModelStagingBackend {
        override val backendId = LargeProtectedModelStagingBackendId("staged-test-backend")
        private var nextArtifact = 0
        private val bytes = mutableMapOf<LargeProtectedModelOpaqueArtifactId, Long>()

        override fun prepare(
            attempt: LargeProtectedModelStagingAttemptReference,
            expectedPlaintextBytes: Long
        ): LargeProtectedModelStagingPrepareResult {
            nextArtifact += 1
            val artifactId = LargeProtectedModelOpaqueArtifactId("artifact-$nextArtifact")
            bytes[artifactId] = 0L
            return LargeProtectedModelStagingPrepareResult.Prepared(
                LargeProtectedModelWorkingArtifactHandle(backendId, attempt, artifactId)
            )
        }

        override fun append(
            handle: LargeProtectedModelWorkingArtifactHandle,
            segmentIndex: Int,
            plaintext: ByteArray
        ): LargeProtectedModelStagingAppendBackendResult {
            bytes[handle.artifactId] = (bytes[handle.artifactId] ?: 0L) + plaintext.size
            return LargeProtectedModelStagingAppendBackendResult.Appended
        }

        override fun seal(
            handle: LargeProtectedModelWorkingArtifactHandle
        ): LargeProtectedModelStagingSealResult = LargeProtectedModelStagingSealResult.Sealed(
            LargeProtectedModelSealedArtifactCandidate(
                backendId = backendId,
                attempt = handle.attempt,
                sourceId = handle.artifactId,
                plaintextBytes = bytes[handle.artifactId] ?: 0L,
                durabilityLevel = LargeProtectedModelStagingDurabilityLevel.FILE_DATA_SYNCED
            )
        )

        override fun delete(
            artifactId: LargeProtectedModelOpaqueArtifactId
        ): LargeProtectedModelStagingDeleteResult {
            bytes.remove(artifactId)
            return LargeProtectedModelStagingDeleteResult.Deleted
        }
    }

    private class FakeEngine(
        private val closeResults: ArrayDeque<ModelEngineCloseResult> = ArrayDeque()
    ) : ModelEngineSessionOwnership {
        override val backendId = ModelEngineBackendId("staged-fake-engine")
        override val handleId = ModelEngineHandleId("private-staged-engine-handle")
        var inferCalls = 0
            private set
        var closeCalls = 0
            private set

        override fun infer(request: ModelEngineInferenceRequest): ModelEngineInferenceResult {
            inferCalls += 1
            return ModelEngineInferenceResult.Succeeded("staged-answer")
        }

        override fun close(): ModelEngineCloseResult {
            closeCalls += 1
            return if (closeResults.isEmpty()) {
                ModelEngineCloseResult.Closed
            } else {
                closeResults.removeFirst()
            }
        }
    }
}
