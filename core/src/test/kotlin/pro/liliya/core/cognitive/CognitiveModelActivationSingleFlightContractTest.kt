package pro.liliya.core.cognitive

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.modelengine.ModelEngineBackendId
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
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingPrepareResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingPublishResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingRequest
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingSealResult
import pro.liliya.core.protectedmodel.LargeProtectedModelWorkingArtifactHandle
import pro.liliya.core.protectedmodel.ModelDekGeneration
import pro.liliya.core.protectedmodel.ModelDekId
import pro.liliya.core.protectedmodel.ModelDekReference
import pro.liliya.core.protectedmodel.ProtectedModelAccessCoordinator
import pro.liliya.core.protectedmodel.ProtectedModelAccessPolicy
import pro.liliya.core.protectedmodel.ProtectedModelDekResolver
import pro.liliya.core.protectedmodel.ProtectedModelEncryptionProfile
import pro.liliya.core.protectedmodel.ProtectedModelFormatVersion
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageEnvelope
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelPackageVerifier
import pro.liliya.core.protectedmodel.ProtectedModelPayloadLoader
import pro.liliya.core.protectedmodel.ProtectedModelPolicyDecision
import pro.liliya.core.protectedmodel.ProtectedModelProfileId
import pro.liliya.core.protectedmodel.ProtectedModelReference
import pro.liliya.core.protectedmodel.ProtectedModelRuntimeOwnership
import pro.liliya.core.protectedmodel.ProtectedModelSignatureAlgorithm
import pro.liliya.core.protectedmodel.ProtectedModelSignerId
import pro.liliya.core.protectedmodel.ProtectedModelSignerResolver
import pro.liliya.core.protectedmodel.ProtectedModelManifest
import pro.liliya.core.runtime.hardening.RuntimeModelSessionId

class CognitiveModelActivationSingleFlightContractTest {
    @Test
    fun staged_activation_blocks_concurrent_staged_and_legacy_activation_before_second_path_work() {
        val stagedEntered = CountDownLatch(1)
        val releaseStaged = CountDownLatch(1)
        val fixture = fixture(
            stagedLoad = {
                stagedEntered.countDown()
                assertTrue(releaseStaged.await(5, TimeUnit.SECONDS))
                ModelEngineLoadResult.Loaded(FakeEngine("staged-first"))
            }
        )
        val firstResult = AtomicReference<CognitiveModelActivationResult>()
        val first = thread(start = true) {
            firstResult.set(fixture.composition.activateStagedModel(fixture.ownership))
        }
        assertTrue(stagedEntered.await(5, TimeUnit.SECONDS))

        val stagedBusy = assertIs<CognitiveModelActivationResult.Rejected>(
            fixture.composition.activateStagedModel(fixture.ownership)
        )
        assertEquals(CognitiveModelActivationFailure.BUSY, stagedBusy.reason)

        val legacyBusy = assertIs<CognitiveModelActivationResult.Rejected>(
            fixture.composition.activateModel(dummyEnvelope(fixture.model), ByteArray(0))
        )
        assertEquals(CognitiveModelActivationFailure.BUSY, legacyBusy.reason)
        assertEquals(1, fixture.policyCalls.get())
        assertEquals(1, fixture.stagedLoadCalls.get())
        assertEquals(0, fixture.legacyLoadCalls.get())

        releaseStaged.countDown()
        first.join(5_000)
        assertIs<CognitiveModelActivationResult.Activated>(firstResult.get())
    }

    @Test
    fun legacy_activation_blocks_concurrent_staged_activation_before_staged_policy_or_load() {
        val legacyPolicyEntered = CountDownLatch(1)
        val releaseLegacyPolicy = CountDownLatch(1)
        val policyCalls = AtomicInteger(0)
        val fixture = fixture(
            policy = ProtectedModelAccessPolicy {
                if (policyCalls.incrementAndGet() == 1) {
                    legacyPolicyEntered.countDown()
                    assertTrue(releaseLegacyPolicy.await(5, TimeUnit.SECONDS))
                }
                ProtectedModelPolicyDecision.Allowed
            },
            externalPolicyCounter = policyCalls
        )
        val firstResult = AtomicReference<CognitiveModelActivationResult>()
        val first = thread(start = true) {
            firstResult.set(
                fixture.composition.activateModel(
                    dummyEnvelope(fixture.model),
                    ByteArray(0)
                )
            )
        }
        assertTrue(legacyPolicyEntered.await(5, TimeUnit.SECONDS))

        val stagedBusy = assertIs<CognitiveModelActivationResult.Rejected>(
            fixture.composition.activateStagedModel(fixture.ownership)
        )
        assertEquals(CognitiveModelActivationFailure.BUSY, stagedBusy.reason)
        assertEquals(1, policyCalls.get())
        assertEquals(0, fixture.stagedLoadCalls.get())

        releaseLegacyPolicy.countDown()
        first.join(5_000)
        assertIs<CognitiveModelActivationResult.Rejected>(firstResult.get())
    }

    private data class Fixture(
        val composition: CognitiveModelRuntimeComposition,
        val ownership: LargeProtectedModelStagedSourceOwnership,
        val model: ProtectedModelReference,
        val policyCalls: AtomicInteger,
        val stagedLoadCalls: AtomicInteger,
        val legacyLoadCalls: AtomicInteger
    )

    private fun fixture(
        policy: ProtectedModelAccessPolicy = ProtectedModelAccessPolicy {
            ProtectedModelPolicyDecision.Allowed
        },
        externalPolicyCounter: AtomicInteger? = null,
        stagedLoad: () -> ModelEngineLoadResult = {
            ModelEngineLoadResult.Loaded(FakeEngine("staged-default"))
        }
    ): Fixture {
        val model = ProtectedModelReference(
            ProtectedModelPackageId("single-flight-model"),
            ProtectedModelGeneration(1)
        )
        val protectedOwnership = ProtectedModelRuntimeOwnership().also { it.replaceTarget(model) }
        val policyCalls = externalPolicyCounter ?: AtomicInteger(0)
        val protectedAccess = ProtectedModelAccessCoordinator(
            policy = if (externalPolicyCounter == null) {
                ProtectedModelAccessPolicy { reference ->
                    policyCalls.incrementAndGet()
                    policy.decide(reference)
                }
            } else {
                policy
            },
            ownership = protectedOwnership,
            loader = ProtectedModelPayloadLoader(
                verifier = ProtectedModelPackageVerifier(ProtectedModelSignerResolver { _, _ -> null }),
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
        val stagedLoadCalls = AtomicInteger(0)
        val stagedCoordinator = StagedModelEngineLoadCoordinator(
            stagingCoordinator = staging,
            loader = StagedModelEngineLoaderPort {
                stagedLoadCalls.incrementAndGet()
                stagedLoad()
            }
        )
        val legacyLoadCalls = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
            correlationIds = CorrelationIdGenerator { "single-flight-correlation" }
        )
        val ids = AtomicInteger(0)
        val composition = CognitiveModelRuntimeComposition(
            foundation = foundation,
            protectedAccess = protectedAccess,
            engineLoader = ModelEngineLoaderPort { _, _ ->
                legacyLoadCalls.incrementAndGet()
                ModelEngineLoadResult.Rejected(ModelEngineLoadFailure.LOAD_REJECTED)
            },
            compiler = DeterministicCognitiveModelRequestCompiler(),
            sessionIds = CognitiveModelRuntimeSessionIdSource {
                RuntimeModelSessionId("single-flight-runtime-${ids.incrementAndGet()}")
            },
            stagedEngineLoader = stagedCoordinator
        )
        return Fixture(
            composition = composition,
            ownership = ownership,
            model = model,
            policyCalls = policyCalls,
            stagedLoadCalls = stagedLoadCalls,
            legacyLoadCalls = legacyLoadCalls
        )
    }

    private fun dummyEnvelope(model: ProtectedModelReference): ProtectedModelPackageEnvelope =
        ProtectedModelPackageEnvelope(
            manifest = ProtectedModelManifest(
                formatVersion = ProtectedModelFormatVersion(1),
                model = model,
                profileId = ProtectedModelProfileId("single-flight-profile"),
                plaintextSizeBytes = 1,
                ciphertextSizeBytes = 1,
                modelDek = ModelDekReference(ModelDekId("single-flight-dek"), ModelDekGeneration(1)),
                encryptionProfile = ProtectedModelEncryptionProfile.AES_256_GCM,
                signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
                signerId = ProtectedModelSignerId("single-flight-signer")
            ),
            payloadDigest = byteArrayOf(1),
            nonce = ByteArray(12),
            authenticationTag = ByteArray(16),
            signature = byteArrayOf(1)
        )

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

    private class FakeBackend : LargeProtectedModelStagingBackend {
        override val backendId = LargeProtectedModelStagingBackendId("single-flight-staging")
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

        override fun delete(artifactId: LargeProtectedModelOpaqueArtifactId): LargeProtectedModelStagingDeleteResult {
            bytes.remove(artifactId)
            return LargeProtectedModelStagingDeleteResult.Deleted
        }
    }

    private class FakeEngine(id: String) : ModelEngineSessionOwnership {
        override val backendId = ModelEngineBackendId("single-flight-engine")
        override val handleId = ModelEngineHandleId(id)

        override fun infer(request: ModelEngineInferenceRequest): ModelEngineInferenceResult =
            ModelEngineInferenceResult.Succeeded("ok")

        override fun close(): ModelEngineCloseResult = ModelEngineCloseResult.Closed
    }
}
