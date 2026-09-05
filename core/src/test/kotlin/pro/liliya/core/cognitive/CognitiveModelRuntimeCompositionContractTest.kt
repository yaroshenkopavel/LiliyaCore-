package pro.liliya.core.cognitive

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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
import pro.liliya.core.modelengine.ModelEngineInferenceFailure
import pro.liliya.core.modelengine.ModelEngineInferenceRequest
import pro.liliya.core.modelengine.ModelEngineInferenceResult
import pro.liliya.core.modelengine.ModelEngineLoadResult
import pro.liliya.core.modelengine.ModelEngineLoaderPort
import pro.liliya.core.modelengine.ModelEngineSessionOwnership
import pro.liliya.core.modelengine.ModelEngineStreamChunk
import pro.liliya.core.modelengine.ModelEngineStreamControl
import pro.liliya.core.modelengine.ModelEngineStreamingSessionOwnership
import pro.liliya.core.modelengine.ModelEngineStreamingSink
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.protectedmodel.ModelDekGeneration
import pro.liliya.core.protectedmodel.ModelDekId
import pro.liliya.core.protectedmodel.ModelDekReference
import pro.liliya.core.protectedmodel.ProtectedModelAccessCoordinator
import pro.liliya.core.protectedmodel.ProtectedModelAccessPolicy
import pro.liliya.core.protectedmodel.ProtectedModelDekResolver
import pro.liliya.core.protectedmodel.ProtectedModelEncryptionProfile
import pro.liliya.core.protectedmodel.ProtectedModelFormatVersion
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelManifest
import pro.liliya.core.protectedmodel.ProtectedModelManifestCanonicalCodec
import pro.liliya.core.protectedmodel.ProtectedModelPackageEnvelope
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelPackageVerifier
import pro.liliya.core.protectedmodel.ProtectedModelPolicyDecision
import pro.liliya.core.protectedmodel.ProtectedModelProfileId
import pro.liliya.core.protectedmodel.ProtectedModelReference
import pro.liliya.core.protectedmodel.ProtectedModelRuntimeOwnership
import pro.liliya.core.protectedmodel.ProtectedModelSignatureAlgorithm
import pro.liliya.core.protectedmodel.ProtectedModelSignerId
import pro.liliya.core.protectedmodel.ProtectedModelSignerResolver
import pro.liliya.core.runtime.hardening.RuntimeHardeningFailure
import pro.liliya.core.runtime.hardening.RuntimeModelSessionId
import pro.liliya.core.runtime.hardening.RuntimeModelSessionLifecycle
import pro.liliya.core.runtime.hardening.RuntimeModelSessionReference
import pro.liliya.core.runtime.hardening.RuntimeOperationAdmissionResult
import pro.liliya.core.runtime.hardening.RuntimeOperationTerminal
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CognitiveModelRuntimeCompositionContractTest {
    private class FakeEngine(
        private val inferBlock: (ModelEngineInferenceRequest) -> ModelEngineInferenceResult = {
            ModelEngineInferenceResult.Succeeded("model-output")
        },
        private val closeBlock: (Int) -> ModelEngineCloseResult = {
            ModelEngineCloseResult.Closed
        }
    ) : ModelEngineSessionOwnership {
        override val backendId = ModelEngineBackendId("fake-engine")
        override val handleId = ModelEngineHandleId("private-engine-handle")
        var inferCalls: Int = 0
            private set
        var closeCalls: Int = 0
            private set
        var lastRequest: ModelEngineInferenceRequest? = null
            private set

        override fun infer(request: ModelEngineInferenceRequest): ModelEngineInferenceResult {
            inferCalls += 1
            lastRequest = request
            return inferBlock(request)
        }

        override fun close(): ModelEngineCloseResult {
            closeCalls += 1
            return closeBlock(closeCalls)
        }
    }

    private class StreamingFakeEngine(
        private val chunks: List<String>,
        private val terminal: ModelEngineInferenceResult =
            ModelEngineInferenceResult.Succeeded(chunks.joinToString(""))
    ) : ModelEngineStreamingSessionOwnership {
        override val backendId = ModelEngineBackendId("streaming-fake-engine")
        override val handleId = ModelEngineHandleId("private-streaming-handle")
        var inferCalls = 0
            private set
        var streamCalls = 0
            private set

        override fun infer(request: ModelEngineInferenceRequest): ModelEngineInferenceResult {
            inferCalls += 1
            return ModelEngineInferenceResult.Succeeded("one-shot-should-not-run")
        }

        override fun stream(
            request: ModelEngineInferenceRequest,
            sink: ModelEngineStreamingSink
        ): ModelEngineInferenceResult {
            streamCalls += 1
            for ((index, text) in chunks.withIndex()) {
                val control = sink.onChunk(
                    ModelEngineStreamChunk(index.toLong() + 1L, text)
                )
                if (control == ModelEngineStreamControl.STOP) {
                    return ModelEngineInferenceResult.Rejected(
                        ModelEngineInferenceFailure.CANCELLED
                    )
                }
            }
            return terminal
        }

        override fun close(): ModelEngineCloseResult = ModelEngineCloseResult.Closed
    }

    private data class ProtectedFixture(
        val reference: ProtectedModelReference,
        val envelope: ProtectedModelPackageEnvelope,
        val ciphertext: ByteArray,
        val key: SecretKey,
        val verifier: ProtectedModelPackageVerifier
    )

    private data class RuntimeFixture(
        val composition: CognitiveModelRuntimeComposition,
        val protectedOwnership: ProtectedModelRuntimeOwnership,
        val protectedFixture: ProtectedFixture,
        val logs: InMemoryLogWriter
    )

    @Test
    fun activation_and_successful_inference_use_exact_runtime_binding_and_budget() {
        val engine = FakeEngine(
            inferBlock = { request ->
                ModelEngineInferenceResult.Succeeded("answer:${request.maxOutputChars}")
            }
        )
        val fixture = runtimeFixture(engineLoader = ModelEngineLoaderPort { _, plaintext ->
            assertTrue(plaintext.contentEquals("model-v1".encodeToByteArray()))
            ModelEngineLoadResult.Loaded(engine)
        })

        val activated = assertIs<CognitiveModelActivationResult.Activated>(
            fixture.composition.activateModel(
                fixture.protectedFixture.envelope,
                fixture.protectedFixture.ciphertext
            )
        )
        assertEquals(activated.session, fixture.composition.currentSession())
        assertEquals(RuntimeModelSessionLifecycle.ACTIVE, fixture.composition.currentLifecycle())

        val request = inferenceRequest(maxOutputChars = 1024)
        val result = assertIs<CognitiveInferenceResult.Succeeded>(
            fixture.composition.inferencePort.infer(request)
        )
        assertEquals(request.turn, result.turn)
        assertEquals("answer:1024", result.output)
        assertEquals(1, engine.inferCalls)
        assertEquals(1024, assertNotNull(engine.lastRequest).maxOutputChars)
        assertTrue(assertNotNull(engine.lastRequest).prompt.contains("private input"))
        assertFalse(assertNotNull(engine.lastRequest).toString().contains("private input"))
    }

    @Test
    fun explicit_streaming_rejects_non_streaming_engine_without_one_shot_fallback() {
        val engine = FakeEngine()
        val fixture = runtimeFixture(
            engineLoader = ModelEngineLoaderPort { _, _ -> ModelEngineLoadResult.Loaded(engine) }
        )
        val session = activate(fixture)
        val delivered = mutableListOf<CognitiveInferenceChunk>()

        val rejected = assertIs<CognitiveInferenceResult.Rejected>(
            fixture.composition.streamingInferencePort.infer(
                inferenceRequest(),
                CognitiveStreamingSink { chunk ->
                    delivered += chunk
                    CognitiveStreamControl.CONTINUE
                }
            )
        )

        assertEquals(CognitiveInferenceFailure.PROVIDER_REJECTED, rejected.reason)
        assertEquals(0, engine.inferCalls)
        assertTrue(delivered.isEmpty())
        assertEquals(0, fixture.composition.operationSupervisor.inFlightCount(session))
        assertEquals(RuntimeModelSessionLifecycle.ACTIVE, fixture.composition.currentLifecycle())
    }

    @Test
    fun streaming_success_preserves_sequence_concat_and_releases_exact_ticket() {
        val engine = StreamingFakeEngine(listOf("hel", "lo", " ", "world"))
        val fixture = runtimeFixture(
            engineLoader = ModelEngineLoaderPort { _, _ -> ModelEngineLoadResult.Loaded(engine) }
        )
        val session = activate(fixture)
        val delivered = mutableListOf<CognitiveInferenceChunk>()

        val result = assertIs<CognitiveInferenceResult.Succeeded>(
            fixture.composition.streamingInferencePort.infer(
                inferenceRequest(),
                CognitiveStreamingSink { chunk ->
                    delivered += chunk
                    CognitiveStreamControl.CONTINUE
                }
            )
        )

        assertEquals("hello world", result.output)
        assertEquals(listOf(1L, 2L, 3L, 4L), delivered.map { it.sequence })
        assertEquals("hello world", delivered.joinToString("") { it.text })
        assertEquals(1, engine.streamCalls)
        assertEquals(0, engine.inferCalls)
        assertEquals(0, fixture.composition.operationSupervisor.inFlightCount(session))
        assertEquals(RuntimeModelSessionLifecycle.ACTIVE, fixture.composition.currentLifecycle())
    }

    @Test
    fun streaming_stop_is_cancelled_and_releases_ticket_without_failing_session() {
        val engine = StreamingFakeEngine(listOf("first", "second", "third"))
        val fixture = runtimeFixture(
            engineLoader = ModelEngineLoaderPort { _, _ -> ModelEngineLoadResult.Loaded(engine) }
        )
        val session = activate(fixture)
        val delivered = mutableListOf<String>()

        val result = assertIs<CognitiveInferenceResult.Rejected>(
            fixture.composition.streamingInferencePort.infer(
                inferenceRequest(),
                CognitiveStreamingSink { chunk ->
                    delivered += chunk.text
                    if (chunk.sequence == 2L) {
                        CognitiveStreamControl.STOP
                    } else {
                        CognitiveStreamControl.CONTINUE
                    }
                }
            )
        )

        assertEquals(CognitiveInferenceFailure.CANCELLED, result.reason)
        assertEquals(listOf("first", "second"), delivered)
        assertEquals(0, fixture.composition.operationSupervisor.inFlightCount(session))
        assertEquals(RuntimeModelSessionLifecycle.ACTIVE, fixture.composition.currentLifecycle())
        assertEquals(null, fixture.composition.currentFailure())
    }

    @Test
    fun direct_output_budget_over_provider_limit_is_rejected_before_engine_admission() {
        val engine = FakeEngine()
        val limits = CognitiveRuntimeLimits(maxInferenceOutputChars = 8)
        val fixture = runtimeFixture(
            limits = limits,
            engineLoader = ModelEngineLoaderPort { _, _ -> ModelEngineLoadResult.Loaded(engine) }
        )
        activate(fixture)

        val result = assertIs<CognitiveInferenceResult.Rejected>(
            fixture.composition.inferencePort.infer(inferenceRequest(maxOutputChars = 9))
        )
        assertEquals(CognitiveInferenceFailure.RESOURCE_LIMIT_REJECTED, result.reason)
        assertEquals(0, engine.inferCalls)
        assertEquals(0, fixture.composition.operationSupervisor.inFlightCount())
    }

    @Test
    fun minimum_v1_envelope_budget_rejects_before_engine_admission_without_ticket_leak() {
        val engine = FakeEngine()
        val fixture = runtimeFixture(
            engineLoader = ModelEngineLoaderPort { _, _ -> ModelEngineLoadResult.Loaded(engine) }
        )
        val session = activate(fixture)

        val result = assertIs<CognitiveInferenceResult.Rejected>(
            fixture.composition.inferencePort.infer(
                inferenceRequest(
                    maxOutputChars = CognitiveStructuredResponseProtocol.minimumEnvelopeChars - 1
                )
            )
        )

        assertEquals(CognitiveInferenceFailure.RESOURCE_LIMIT_REJECTED, result.reason)
        assertEquals(0, engine.inferCalls)
        assertEquals(0, fixture.composition.operationSupervisor.inFlightCount(session))
        assertEquals(RuntimeModelSessionLifecycle.ACTIVE, fixture.composition.currentLifecycle())
        assertEquals(null, fixture.composition.currentFailure())
    }

    @Test
    fun malformed_over_bound_compiler_output_is_rejected_before_engine_call() {
        val engine = FakeEngine()
        val limits = CognitiveRuntimeLimits(maxModelPromptChars = 16)
        val fixture = runtimeFixture(
            limits = limits,
            compiler = CognitiveModelRequestCompilerPort {
                CognitiveModelRequestCompilerResult.Compiled(
                    CognitiveCompiledModelRequest("x".repeat(17))
                )
            },
            engineLoader = ModelEngineLoaderPort { _, _ -> ModelEngineLoadResult.Loaded(engine) }
        )
        activate(fixture)

        val result = assertIs<CognitiveInferenceResult.Rejected>(
            fixture.composition.inferencePort.infer(inferenceRequest())
        )
        assertEquals(CognitiveInferenceFailure.RESOURCE_LIMIT_REJECTED, result.reason)
        assertEquals(0, engine.inferCalls)
    }

    @Test
    fun ordinary_engine_operation_failure_does_not_poison_reusable_session() {
        val engine = FakeEngine(
            inferBlock = {
                ModelEngineInferenceResult.Rejected(ModelEngineInferenceFailure.OPERATION_FAILED)
            }
        )
        val fixture = runtimeFixture(
            engineLoader = ModelEngineLoaderPort { _, _ -> ModelEngineLoadResult.Loaded(engine) }
        )
        activate(fixture)

        val result = assertIs<CognitiveInferenceResult.Rejected>(
            fixture.composition.inferencePort.infer(inferenceRequest())
        )
        assertEquals(CognitiveInferenceFailure.PROVIDER_FAILED, result.reason)
        assertEquals(RuntimeModelSessionLifecycle.ACTIVE, fixture.composition.currentLifecycle())
        assertEquals(null, fixture.composition.currentFailure())
        assertEquals(0, fixture.composition.operationSupervisor.inFlightCount())
    }

    @Test
    fun fatal_engine_session_failure_fails_exact_session_and_cleanup_retires_after_release() {
        val engine = FakeEngine(
            inferBlock = {
                ModelEngineInferenceResult.Rejected(ModelEngineInferenceFailure.SESSION_FAILED)
            }
        )
        val fixture = runtimeFixture(
            engineLoader = ModelEngineLoaderPort { _, _ -> ModelEngineLoadResult.Loaded(engine) }
        )
        val session = activate(fixture)

        val result = assertIs<CognitiveInferenceResult.Rejected>(
            fixture.composition.inferencePort.infer(inferenceRequest())
        )
        assertEquals(CognitiveInferenceFailure.PROVIDER_FAILED, result.reason)
        assertEquals(RuntimeModelSessionLifecycle.FAILED, fixture.composition.currentLifecycle())
        assertEquals(RuntimeHardeningFailure.SESSION_FAILED, fixture.composition.currentFailure())
        assertEquals(0, fixture.composition.operationSupervisor.inFlightCount(session))

        assertIs<CognitiveModelCleanupResult.Cleaned>(fixture.composition.cleanupFailedSession(session))
        assertEquals(1, engine.closeCalls)
        assertEquals(null, fixture.composition.currentSession())
    }

    @Test
    fun quiescing_during_engine_call_discards_local_success_as_stale() {
        val entered = CountDownLatch(1)
        val releaseEngine = CountDownLatch(1)
        val engine = FakeEngine(
            inferBlock = {
                entered.countDown()
                assertTrue(releaseEngine.await(5, TimeUnit.SECONDS))
                ModelEngineInferenceResult.Succeeded("must-not-publish")
            }
        )
        val fixture = runtimeFixture(
            engineLoader = ModelEngineLoaderPort { _, _ -> ModelEngineLoadResult.Loaded(engine) }
        )
        val session = activate(fixture)
        val resultRef = AtomicReference<CognitiveInferenceResult>()
        val worker = thread(start = true) {
            resultRef.set(fixture.composition.inferencePort.infer(inferenceRequest()))
        }

        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertIs<CognitiveModelQuiesceResult.Quiescing>(fixture.composition.beginQuiescing(session))
        releaseEngine.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive)

        val result = assertIs<CognitiveInferenceResult.Rejected>(resultRef.get())
        assertEquals(CognitiveInferenceFailure.PROVIDER_REJECTED, result.reason)
        assertEquals(RuntimeModelSessionLifecycle.QUIESCING, fixture.composition.currentLifecycle())
        assertEquals(0, fixture.composition.operationSupervisor.inFlightCount(session))
    }

    @Test
    fun concurrent_direct_inference_respects_one_in_flight_bound() {
        val entered = CountDownLatch(1)
        val releaseEngine = CountDownLatch(1)
        val engine = FakeEngine(
            inferBlock = {
                entered.countDown()
                assertTrue(releaseEngine.await(5, TimeUnit.SECONDS))
                ModelEngineInferenceResult.Succeeded("first")
            }
        )
        val fixture = runtimeFixture(
            engineLoader = ModelEngineLoaderPort { _, _ -> ModelEngineLoadResult.Loaded(engine) }
        )
        activate(fixture)
        val firstRef = AtomicReference<CognitiveInferenceResult>()
        val first = thread(start = true) {
            firstRef.set(fixture.composition.inferencePort.infer(inferenceRequest()))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val second = assertIs<CognitiveInferenceResult.Rejected>(
            fixture.composition.inferencePort.infer(inferenceRequest())
        )
        assertEquals(CognitiveInferenceFailure.RESOURCE_LIMIT_REJECTED, second.reason)
        assertEquals(1, engine.inferCalls)

        releaseEngine.countDown()
        first.join(5_000)
        assertIs<CognitiveInferenceResult.Succeeded>(firstRef.get())
        assertEquals(1, engine.inferCalls)
    }

    @Test
    fun failed_session_cleanup_refuses_to_close_while_exact_ticket_is_still_in_flight() {
        val engine = FakeEngine()
        val fixture = runtimeFixture(
            engineLoader = ModelEngineLoaderPort { _, _ -> ModelEngineLoadResult.Loaded(engine) }
        )
        val session = activate(fixture)
        val ticket = assertIs<RuntimeOperationAdmissionResult.Admitted>(
            fixture.composition.operationSupervisor.admit()
        ).ticket
        fixture.composition.operationSupervisor.failSession(
            session,
            RuntimeHardeningFailure.PROVIDER_FAILED
        )

        val blocked = assertIs<CognitiveModelCleanupResult.DrainRequired>(
            fixture.composition.cleanupFailedSession(session)
        )
        assertEquals(1, blocked.inFlightOperations)
        assertEquals(0, engine.closeCalls)

        fixture.composition.operationSupervisor.release(ticket, RuntimeOperationTerminal.FAILED)
        assertIs<CognitiveModelCleanupResult.Cleaned>(fixture.composition.cleanupFailedSession(session))
        assertEquals(1, engine.closeCalls)
    }

    @Test
    fun retirement_cleanup_failure_cannot_be_misrouted_through_failed_session_cleanup() {
        val engine = FakeEngine(
            closeBlock = { attempt ->
                if (attempt == 1) {
                    ModelEngineCloseResult.Failed(ModelEngineCloseFailure.CLOSE_FAILED)
                } else {
                    ModelEngineCloseResult.Closed
                }
            }
        )
        val fixture = runtimeFixture(
            engineLoader = ModelEngineLoaderPort { _, _ -> ModelEngineLoadResult.Loaded(engine) }
        )
        val session = activate(fixture)
        assertIs<CognitiveModelQuiesceResult.Quiescing>(fixture.composition.beginQuiescing(session))
        assertIs<CognitiveModelRetirementResult.CleanupFailed>(
            fixture.composition.retireIfDrained(session)
        )
        assertEquals(RuntimeModelSessionLifecycle.FAILED, fixture.composition.currentLifecycle())
        assertEquals(RuntimeHardeningFailure.RETIREMENT_FAILED, fixture.composition.currentFailure())
        assertEquals(1, engine.closeCalls)

        assertIs<CognitiveModelCleanupResult.Stale>(fixture.composition.cleanupFailedSession(session))
        assertEquals(1, engine.closeCalls)

        assertIs<CognitiveModelRetirementResult.Retired>(
            fixture.composition.recoverRetirementFailure(session)
        )
        assertEquals(2, engine.closeCalls)
        assertEquals(null, fixture.composition.currentSession())
    }

    @Test
    fun stale_protected_publication_compensates_loaded_engine_and_failed_close_blocks_activation_until_explicit_recovery() {
        val engine = FakeEngine(
            closeBlock = { attempt ->
                if (attempt == 1) {
                    ModelEngineCloseResult.Failed(ModelEngineCloseFailure.CLOSE_FAILED)
                } else {
                    ModelEngineCloseResult.Closed
                }
            }
        )
        lateinit var fixture: RuntimeFixture
        fixture = runtimeFixture(engineLoader = ModelEngineLoaderPort { model, _ ->
            fixture.protectedOwnership.replaceTarget(
                ProtectedModelReference(model.packageId, ProtectedModelGeneration(model.generation.value + 1))
            )
            ModelEngineLoadResult.Loaded(engine)
        })

        val failed = assertIs<CognitiveModelActivationResult.Failed>(
            fixture.composition.activateModel(
                fixture.protectedFixture.envelope,
                fixture.protectedFixture.ciphertext
            )
        )
        assertEquals(CognitiveModelActivationFailure.CLEANUP_FAILED, failed.reason)
        assertEquals(1, engine.closeCalls)

        val blocked = assertIs<CognitiveModelActivationResult.Rejected>(
            fixture.composition.activateModel(
                fixture.protectedFixture.envelope,
                fixture.protectedFixture.ciphertext
            )
        )
        assertEquals(CognitiveModelActivationFailure.BUSY, blocked.reason)

        assertIs<CognitiveModelCleanupResult.Cleaned>(
            fixture.composition.recoverPendingActivationCleanup()
        )
        assertEquals(2, engine.closeCalls)
    }

    @Test
    fun concurrent_activation_is_single_flight() {
        val entered = CountDownLatch(1)
        val releaseLoader = CountDownLatch(1)
        val engine = FakeEngine()
        val fixture = runtimeFixture(engineLoader = ModelEngineLoaderPort { _, _ ->
            entered.countDown()
            assertTrue(releaseLoader.await(5, TimeUnit.SECONDS))
            ModelEngineLoadResult.Loaded(engine)
        })
        val firstRef = AtomicReference<CognitiveModelActivationResult>()
        val first = thread(start = true) {
            firstRef.set(
                fixture.composition.activateModel(
                    fixture.protectedFixture.envelope,
                    fixture.protectedFixture.ciphertext
                )
            )
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val second = assertIs<CognitiveModelActivationResult.Rejected>(
            fixture.composition.activateModel(
                fixture.protectedFixture.envelope,
                fixture.protectedFixture.ciphertext
            )
        )
        assertEquals(CognitiveModelActivationFailure.BUSY, second.reason)

        releaseLoader.countDown()
        first.join(5_000)
        assertIs<CognitiveModelActivationResult.Activated>(firstRef.get())
    }

    @Test
    fun structural_observability_excludes_private_prompt_output_and_handle() {
        val engine = FakeEngine(
            inferBlock = {
                throw IllegalStateException("private-engine-exception")
            }
        )
        val fixture = runtimeFixture(
            compiler = CognitiveModelRequestCompilerPort {
                CognitiveModelRequestCompilerResult.Compiled(
                    CognitiveCompiledModelRequest("private-compiled-prompt")
                )
            },
            engineLoader = ModelEngineLoaderPort { _, _ -> ModelEngineLoadResult.Loaded(engine) }
        )
        activate(fixture)
        fixture.composition.inferencePort.infer(inferenceRequest())

        val rendered = fixture.logs.snapshot().joinToString("\n")
        assertFalse(rendered.contains("private input"))
        assertFalse(rendered.contains("private-compiled-prompt"))
        assertFalse(rendered.contains("private-engine-exception"))
        assertFalse(rendered.contains("private-engine-handle"))
    }

    private fun activate(fixture: RuntimeFixture): RuntimeModelSessionReference =
        assertIs<CognitiveModelActivationResult.Activated>(
            fixture.composition.activateModel(
                fixture.protectedFixture.envelope,
                fixture.protectedFixture.ciphertext
            )
        ).session

    private fun inferenceRequest(
        maxOutputChars: Int = 1024
    ): CognitiveInferenceRequest {
        val turn = CognitiveTurnReference(
            CognitiveTurnId("private-turn"),
            CognitiveTurnGeneration(1)
        )
        return CognitiveInferenceRequest(
            turn = turn,
            input = CognitiveInput("private input"),
            context = CognitiveContextSnapshot(turn, emptyList()),
            maxOutputChars = maxOutputChars
        )
    }

    private fun runtimeFixture(
        engineLoader: ModelEngineLoaderPort,
        compiler: CognitiveModelRequestCompilerPort = DeterministicCognitiveModelRequestCompiler(),
        limits: CognitiveRuntimeLimits = CognitiveRuntimeLimits()
    ): RuntimeFixture {
        val protectedFixture = protectedFixture()
        val protectedOwnership = ProtectedModelRuntimeOwnership().also {
            it.replaceTarget(protectedFixture.reference)
        }
        val protectedAccess = ProtectedModelAccessCoordinator(
            policy = ProtectedModelAccessPolicy { ProtectedModelPolicyDecision.Allowed },
            ownership = protectedOwnership,
            loader = pro.liliya.core.protectedmodel.ProtectedModelPayloadLoader(
                verifier = protectedFixture.verifier,
                dekResolver = ProtectedModelDekResolver { model, _ ->
                    if (model == protectedFixture.reference) protectedFixture.key else null
                },
                maxPlaintextSizeBytes = 1024L * 1024L
            )
        )
        val logs = InMemoryLogWriter()
        val correlations = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "model-runtime-${correlations.incrementAndGet()}" }
        )
        val ids = AtomicInteger(0)
        val composition = CognitiveModelRuntimeComposition(
            foundation = foundation,
            protectedAccess = protectedAccess,
            engineLoader = engineLoader,
            compiler = compiler,
            sessionIds = CognitiveModelRuntimeSessionIdSource {
                RuntimeModelSessionId("runtime-model-${ids.incrementAndGet()}")
            },
            limits = limits
        )
        return RuntimeFixture(composition, protectedOwnership, protectedFixture, logs)
    }

    private fun protectedFixture(): ProtectedFixture {
        val plaintext = "model-v1".encodeToByteArray()
        val signerKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val modelKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val model = ProtectedModelReference(
            ProtectedModelPackageId("model-package"),
            ProtectedModelGeneration(1)
        )
        val modelDek = ModelDekReference(ModelDekId("model-dek"), ModelDekGeneration(1))
        val nonce = ByteArray(12) { (it + 7).toByte() }
        val manifest = ProtectedModelManifest(
            formatVersion = ProtectedModelFormatVersion(1),
            model = model,
            profileId = ProtectedModelProfileId("gguf-q4"),
            plaintextSizeBytes = plaintext.size.toLong(),
            ciphertextSizeBytes = plaintext.size.toLong(),
            modelDek = modelDek,
            encryptionProfile = ProtectedModelEncryptionProfile.AES_256_GCM,
            signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
            signerId = ProtectedModelSignerId("release-signer")
        )
        val encrypted = encrypt(manifest, plaintext, modelKey, nonce)
        val digest = MessageDigest.getInstance("SHA-256").digest(encrypted.ciphertext)
        val signature = sign(manifest, digest, nonce, encrypted.tag, signerKeys.private)
        val envelope = ProtectedModelPackageEnvelope(
            manifest = manifest,
            payloadDigest = digest,
            nonce = nonce,
            authenticationTag = encrypted.tag,
            signature = signature
        )
        val verifier = ProtectedModelPackageVerifier(
            ProtectedModelSignerResolver { signerId, algorithm ->
                if (signerId == manifest.signerId && algorithm == manifest.signatureAlgorithm) {
                    signerKeys.public
                } else {
                    null
                }
            }
        )
        return ProtectedFixture(model, envelope, encrypted.ciphertext, modelKey, verifier)
    }

    private fun encrypt(
        manifest: ProtectedModelManifest,
        plaintext: ByteArray,
        key: SecretKey,
        nonce: ByteArray
    ): Encrypted {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(ProtectedModelManifestCanonicalCodec.encode(manifest))
        val output = cipher.doFinal(plaintext)
        return try {
            Encrypted(
                output.copyOfRange(0, output.size - 16),
                output.copyOfRange(output.size - 16, output.size)
            )
        } finally {
            output.fill(0)
        }
    }

    private fun sign(
        manifest: ProtectedModelManifest,
        digest: ByteArray,
        nonce: ByteArray,
        tag: ByteArray,
        privateKey: PrivateKey
    ): ByteArray {
        val input = ProtectedModelManifestCanonicalCodec.signatureInput(manifest, digest, nonce, tag)
        return try {
            Signature.getInstance("Ed25519").run {
                initSign(privateKey)
                update(input)
                sign()
            }
        } finally {
            input.fill(0)
        }
    }

    private data class Encrypted(
        val ciphertext: ByteArray,
        val tag: ByteArray
    )
}