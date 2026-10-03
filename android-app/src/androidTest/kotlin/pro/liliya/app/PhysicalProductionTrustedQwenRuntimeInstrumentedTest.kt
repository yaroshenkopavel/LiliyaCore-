package pro.liliya.app

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingProvider
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingResult
import pro.liliya.android.llamacppengine.AndroidLlamaCppCognitiveModelAssembly
import pro.liliya.android.llamacppengine.LlamaCppEnginePolicy
import pro.liliya.android.llamacppengine.LlamaCppPromptFormatPolicy
import pro.liliya.android.protectedmodel.staging.AndroidProtectedModelStagingPolicy
import pro.liliya.android.runtime.AndroidProductRuntimeAdmissionFailure
import pro.liliya.android.runtime.AndroidProductRuntimeAdmissionGate
import pro.liliya.android.runtime.AndroidProductRuntimeAdmissionResult
import pro.liliya.android.runtime.AndroidProductRuntimeSemanticArtifacts
import pro.liliya.android.runtime.AndroidProductRuntimeStartupActiveDekPort
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAdmissionPort
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityAssemblyResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityGrantAssembly
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityPlan
import pro.liliya.android.runtime.AndroidProductRuntimeStartupModelPort
import pro.liliya.android.runtime.AndroidProductRuntimeStartupPreparationResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupPreparedInputsPort
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioner
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningFailure
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningPorts
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupSemanticPort
import pro.liliya.core.authority.AuthorityManager
import pro.liliya.core.authority.AuthorityPolicy
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
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
import pro.liliya.core.encryption.CognitiveDekGeneration
import pro.liliya.core.encryption.CognitiveDekId
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseAuthorityComposition
import pro.liliya.core.license.LicenseAuthorityRequest
import pro.liliya.core.license.LicenseDeviceBindingReferenceFactory
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicensePolicyRequest
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseTrustedVerificationKey
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.license.LicenseVerifier
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.licensetransport.ActivationRedemptionHttpClient
import pro.liliya.core.licensetransport.ActivationRedemptionHttpRequest
import pro.liliya.core.licensetransport.ActivationRedemptionHttpResult
import pro.liliya.core.licensetransport.LicenseHttpTlsTrust
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
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

/**
 * Physical production trusted first-run Qwen runtime acceptance.
 *
 * No model download is allowed here. The exact pinned GGUF must already be staged app-private as
 * an operator-supplied artifact. The test verifies size + SHA-256 before protected-model staging.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalProductionTrustedQwenRuntimeInstrumentedTest {

    @Test
    fun admitted_real_license_executes_exact_local_qwen_without_implicit_authority() {
        assertEquals("arm64-v8a", Build.SUPPORTED_ABIS.firstOrNull())

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext.applicationContext

        val endpoint = URL(required(args.getString(ARG_ENDPOINT)))
        assertEquals("https", endpoint.protocol)

        val caBytes = Base64.getDecoder().decode(required(args.getString(ARG_CA_BASE64)))
        val tlsTrust = try {
            LicenseHttpTlsTrust.ofCertificate(caBytes)
        } finally {
            caBytes.fill(0)
        }

        val binding = assertIs<AndroidActivationDeviceBindingResult.Ready>(
            AndroidActivationDeviceBindingProvider(context).loadOrCreate()
        ).binding
        val expectedBinding = LicenseDeviceBindingReferenceFactory.create(
            installationId = binding.installationId,
            deviceKeyFingerprint = binding.deviceKeyFingerprint
        )

        val activationCodeFile = File(context.filesDir, ACTIVATION_CODE_FILE)
        assertTrue(activationCodeFile.isFile)
        val activationCode = activationCodeFile.readText(Charsets.UTF_8).trim()
        assertTrue(activationCode.isNotBlank())
        assertTrue(activationCodeFile.delete())
        assertTrue(!activationCodeFile.exists())

        val redemption = ActivationRedemptionHttpClient(
            LicenseHttpTransportConfig(
                endpoint = endpoint,
                connectTimeoutMillis = 10_000,
                readTimeoutMillis = 60_000,
                developmentAllowInsecureHttp = false,
                tlsTrust = tlsTrust
            )
        ).redeem(
            ActivationRedemptionHttpRequest(
                activationCode = activationCode,
                attemptId = ProductionAndroidActivationAttemptIdentity.loadOrCreate(context),
                installationId = binding.installationId,
                deviceKeyFingerprint = binding.deviceKeyFingerprint
            )
        )

        val activated = assertIs<ActivationRedemptionHttpResult.Activated>(redemption)
        val publicKey = Base64.getDecoder().decode(required(args.getString(ARG_LICENSE_KEY_BASE64)))
        val trusted = try {
            LicenseTrustedVerificationKey.of(
                keyId = activated.license.signingKeyId,
                algorithm = LicenseAlgorithm(ALGORITHM),
                material = publicKey
            )
        } finally {
            publicKey.fill(0)
        }

        val verified = assertIs<LicenseVerificationResult.Verified>(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(LicenseAlgorithm(ALGORITHM)),
                trustedKeys = LicenseTrustedKeyResolver { requested: LicenseKeyId ->
                    trusted.takeIf { it.keyId == requested }
                },
                signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
            ).verify(activated.license)
        )

        assertEquals(LicenseProductId(required(args.getString(ARG_PRODUCT))), verified.entitlement.productId)
        assertEquals(activated.subject, verified.entitlement.subject.value)
        assertEquals(expectedBinding, assertNotNull(verified.entitlement.deviceBindingReference))
        assertNull(verified.entitlement.expiresAt)
        assertNull(verified.entitlement.offlineLeaseUntil)
        assertTrue(verified.entitlement.features.any { it.value == GRANTED_CAPABILITY })

        val localModel = File(context.filesDir, QWEN_FILE_NAME)
        assertTrue(localModel.isFile)
        assertEquals(QWEN_BYTES, localModel.length())
        assertEquals(QWEN_SHA256, sha256(localModel))

        val foundation = foundation()
        val policyNow = Instant.now()
        val principal = AuthorityPrincipal(PRINCIPAL)
        val grantedCapability = CapabilityId(GRANTED_CAPABILITY)
        val deniedCapability = CapabilityId(DENIED_CAPABILITY)
        val scope = AuthorityScope.GLOBAL

        val capabilityAuthority = CapabilityAuthorityComposition(
            foundation = foundation,
            now = { policyNow }
        )
        val installed = assertIs<AndroidProductRuntimeStartupAuthorityAssemblyResult.Ready>(
            AndroidProductRuntimeStartupAuthorityGrantAssembly.install(
                authority = capabilityAuthority,
                plan = AndroidProductRuntimeStartupAuthorityPlan(
                    capabilities = listOf(
                        CapabilityDescriptor(
                            id = grantedCapability,
                            providerId = CapabilityProviderId(PROVIDER)
                        )
                    ),
                    directGrants = listOf(
                        DirectAuthorityGrant(
                            principal = principal,
                            capability = grantedCapability,
                            scope = scope
                        )
                    )
                )
            )
        )

        try {
            val authorityManager = AuthorityManager(
                policy = AuthorityPolicy { request ->
                    capabilityAuthority.authorize(
                        request = request,
                        context = foundation.rootContext(
                            operation = "physical-production-qwen-runtime",
                            component = "PhysicalProductionTrustedQwenRuntimeInstrumentedTest"
                        )
                    )
                },
                observability = foundation.observability
            )
            val licenseAuthority = LicenseAuthorityComposition(
                foundation = foundation,
                authorityManager = authorityManager
            )
            val feature = verified.entitlement.features.first { it.value == GRANTED_CAPABILITY }
            val licenseRequest = LicensePolicyRequest(
                productId = verified.entitlement.productId,
                feature = feature,
                subject = verified.entitlement.subject
            )
            val policyContext = LicensePolicyContext(
                now = policyNow,
                minimumRevocationEpoch = verified.entitlement.revocationEpoch,
                minimumReplaySequence = verified.entitlement.replaySequence,
                requiredDeviceBindingReference = expectedBinding
            )

            fun admission(capability: CapabilityId): AndroidProductRuntimeAdmissionResult =
                AndroidProductRuntimeAdmissionGate.admit(
                    composition = licenseAuthority,
                    verified = verified,
                    licenseRequest = licenseRequest,
                    policyContext = policyContext,
                    authorityRequest = LicenseAuthorityRequest(
                        principal = principal,
                        capability = capability,
                        scope = scope
                    )
                )

            val denied = assertIs<AndroidProductRuntimeAdmissionResult.Rejected>(
                admission(deniedCapability)
            )
            assertEquals(AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED, denied.reason)

            val deniedCalls = DownstreamCalls.create()
            val deniedProvisioning = AndroidProductRuntimeStartupProvisioner.prepare(
                ports(
                    context = context,
                    admission = { admission(deniedCapability) },
                    calls = deniedCalls,
                    modelPort = AndroidProductRuntimeStartupModelPort {
                        deniedCalls.model.incrementAndGet()
                        deniedCalls.qwen.incrementAndGet()
                        AndroidProductRuntimeStartupPreparationResult.Rejected
                    }
                )
            )
            assertIs<AndroidProductRuntimeStartupProvisioningResult.AdmissionRejected>(deniedProvisioning)
            assertEquals(0, deniedCalls.total())
            assertIs<AndroidProductRuntimeAdmissionResult.Admitted>(admission(grantedCapability))

            val positiveCalls = DownstreamCalls.create()
            val qwenEvidence = QwenEvidence()
            val runtimeOwnership = ProtectedModelRuntimeOwnership()
            val model = ProtectedModelReference(
                packageId = ProtectedModelPackageId("production-trusted-qwen3-1.7b-q4km"),
                generation = ProtectedModelGeneration(1)
            )
            runtimeOwnership.replaceTarget(model)
            val llama = llamaAssembly(context, foundation, runtimeOwnership)

            val positiveProvisioning = AndroidProductRuntimeStartupProvisioner.prepare(
                ports(
                    context = context,
                    admission = { admission(grantedCapability) },
                    calls = positiveCalls,
                    modelPort = AndroidProductRuntimeStartupModelPort {
                        positiveCalls.model.incrementAndGet()
                        positiveCalls.qwen.incrementAndGet()
                        val staged = provisionExactCandidate(
                            source = localModel,
                            coordinator = llama.stagingCoordinator,
                            model = model,
                            evidence = qwenEvidence
                        )
                        var retired = false
                        try {
                            val activatedModel = assertIs<CognitiveModelActivationResult.Activated>(
                                llama.stagedActivation.activate(staged)
                            )
                            qwenEvidence.activated = true

                            val inference = assertIs<CognitiveInferenceResult.Succeeded>(
                                llama.inferencePort.infer(inferenceRequest())
                            )
                            assertTrue(inference.output.isNotBlank())
                            qwenEvidence.inferred = true
                            qwenEvidence.outputChars = inference.output.length

                            assertIs<CognitiveModelQuiesceResult.Quiescing>(
                                llama.cognitiveRuntime.beginQuiescing(activatedModel.session)
                            )
                            assertIs<CognitiveModelRetirementResult.Retired>(
                                llama.cognitiveRuntime.retireIfDrained(activatedModel.session)
                            )
                            assertIs<LargeProtectedModelStagingRetireResult.Retired>(
                                staged.retire()
                            )
                            retired = true
                        } finally {
                            if (!retired) runCatching { staged.retire() }
                        }
                        AndroidProductRuntimeStartupPreparationResult.Ready(staged)
                    }
                )
            )

            val stoppedBeforeHostBootstrap =
                assertIs<AndroidProductRuntimeStartupProvisioningResult.Rejected>(positiveProvisioning)
            assertEquals(
                AndroidProductRuntimeStartupProvisioningFailure.PREPARED_INPUTS_REJECTED,
                stoppedBeforeHostBootstrap.reason
            )
            assertEquals(1, positiveCalls.activeDek.get())
            assertEquals(1, positiveCalls.semantic.get())
            assertEquals(1, positiveCalls.model.get())
            assertEquals(1, positiveCalls.prepared.get())
            assertEquals(1, positiveCalls.qwen.get())
            assertTrue(qwenEvidence.localArtifactVerified)
            assertTrue(qwenEvidence.activated)
            assertTrue(qwenEvidence.inferred)
            assertTrue(qwenEvidence.outputChars > 0)

            recordEvidence(
                linkedMapOf(
                    "realSignedLicense" to "true",
                    "deviceBound" to "true",
                    "authorityAdmission" to "true",
                    "authorityDeniedQwenCalls" to deniedCalls.qwen.get().toString(),
                    "localArtifact" to "true",
                    "qwenBytes" to QWEN_BYTES.toString(),
                    "qwenSha256" to QWEN_SHA256,
                    "qwenActivated" to qwenEvidence.activated.toString(),
                    "qwenInference" to qwenEvidence.inferred.toString(),
                    "qwenOutputChars" to qwenEvidence.outputChars.toString(),
                    "hostBootstrapStarted" to "false",
                    "implicitAuthorityMinted" to "false"
                )
            )
            println("LILIYA_PHYSICAL_PRODUCTION_TRUSTED_QWEN_RUNTIME=PASS")
        } finally {
            installed.ownership.directGrants.asReversed().forEach { runCatching { it.revoke() } }
            installed.ownership.capabilities.asReversed().forEach { runCatching { it.unregister() } }
        }
    }

    private fun ports(
        context: Context,
        admission: () -> AndroidProductRuntimeAdmissionResult,
        calls: DownstreamCalls,
        modelPort: AndroidProductRuntimeStartupModelPort
    ): AndroidProductRuntimeStartupProvisioningPorts {
        val semanticRoot = File(context.filesDir, "physical-production-qwen-semantic-placeholder")
        val encoder = File(semanticRoot, "encoder-placeholder.onnx")
        return AndroidProductRuntimeStartupProvisioningPorts(
            admission = AndroidProductRuntimeStartupAdmissionPort { admission() },
            activeDek = AndroidProductRuntimeStartupActiveDekPort {
                calls.activeDek.incrementAndGet()
                AndroidProductRuntimeStartupPreparationResult.Ready(
                    CognitiveDekReference(
                        id = CognitiveDekId("physical-production-qwen-cognitive-dek"),
                        generation = CognitiveDekGeneration(1)
                    )
                )
            },
            semantic = AndroidProductRuntimeStartupSemanticPort {
                calls.semantic.incrementAndGet()
                AndroidProductRuntimeStartupPreparationResult.Ready(
                    AndroidProductRuntimeSemanticArtifacts(
                        root = semanticRoot,
                        encoderFile = encoder
                    )
                )
            },
            model = modelPort,
            preparedInputs = AndroidProductRuntimeStartupPreparedInputsPort { _, _, _ ->
                calls.prepared.incrementAndGet()
                AndroidProductRuntimeStartupPreparationResult.Rejected
            }
        )
    }

    private fun provisionExactCandidate(
        source: File,
        coordinator: LargeProtectedModelStagingCoordinator,
        model: ProtectedModelReference,
        evidence: QwenEvidence
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
            FileInputStream(source).use { input ->
                var total = 0L
                var segmentIndex = 0
                while (total < QWEN_BYTES) {
                    val wanted = minOf(SEGMENT_BYTES.toLong(), QWEN_BYTES - total).toInt()
                    val segment = ByteArray(wanted)
                    var offset = 0
                    while (offset < wanted) {
                        val read = input.read(segment, offset, wanted - offset)
                        if (read < 0) error("local Qwen artifact ended early")
                        offset += read
                    }
                    assertIs<LargeProtectedModelStagingAppendResult.Appended>(
                        session.append(segmentIndex, segment)
                    )
                    total += wanted.toLong()
                    segment.fill(0)
                    segmentIndex += 1
                }
                assertEquals(-1, input.read())
                assertEquals(QWEN_BYTES, total)
            }
            evidence.localArtifactVerified = true
            val ownership = assertIs<LargeProtectedModelStagingPublishResult.Published>(
                session.sealAndPublish()
            ).ownership
            published = true
            return ownership
        } finally {
            if (!published && coordinator.currentAttempt() == session.attempt) {
                assertIs<LargeProtectedModelStagingAbortResult.Aborted>(session.abort())
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
            CognitiveModelRequestCompilerResult.Compiled(
                CognitiveCompiledModelRequest(request.inference.input.text)
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
                useMmap = true,
                promptFormatPolicy = LlamaCppPromptFormatPolicy.MODEL_DEFAULT_CHAT_TEMPLATE
            ),
            foundation = foundation,
            protectedAccess = protectedAccess,
            legacyEngineLoader = legacyLoader,
            compiler = compiler,
            sessionIds = CognitiveModelRuntimeSessionIdSource {
                RuntimeModelSessionId("physical-production-qwen-" + ids.incrementAndGet())
            },
            limits = CognitiveRuntimeLimits(
                maxInferenceOutputChars = 512,
                maxModelPromptChars = 8192
            )
        )
    }

    private fun inferenceRequest(): CognitiveInferenceRequest {
        val turn = CognitiveTurnReference(
            id = CognitiveTurnId("physical-production-qwen-inference"),
            generation = CognitiveTurnGeneration(1)
        )
        return CognitiveInferenceRequest(
            turn = turn,
            input = CognitiveInput("What is two plus two? /no_think"),
            context = CognitiveContextSnapshot(turn, emptyList()),
            maxOutputChars = 512
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
            buffer.fill(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun foundation(): FoundationComposition {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val correlations = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, logs)
            },
            correlationIds = CorrelationIdGenerator {
                "physical-production-qwen-" + correlations.incrementAndGet()
            }
        )
    }

    private fun recordEvidence(values: Map<String, String>) {
        val bundle = Bundle()
        values.forEach { (key, value) ->
            bundle.putString("productionQwen." + key, value)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, bundle)
    }

    private fun required(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: error("missing physical production trusted-Qwen argument")

    private fun segmentCount(bytes: Long): Int =
        ((bytes + SEGMENT_BYTES - 1L) / SEGMENT_BYTES).toInt()

    private data class DownstreamCalls(
        val activeDek: AtomicInteger,
        val semantic: AtomicInteger,
        val model: AtomicInteger,
        val prepared: AtomicInteger,
        val qwen: AtomicInteger
    ) {
        fun total(): Int =
            activeDek.get() + semantic.get() + model.get() + prepared.get() + qwen.get()

        companion object {
            fun create() = DownstreamCalls(
                AtomicInteger(0),
                AtomicInteger(0),
                AtomicInteger(0),
                AtomicInteger(0),
                AtomicInteger(0)
            )
        }
    }

    private class QwenEvidence {
        var localArtifactVerified: Boolean = false
        var activated: Boolean = false
        var inferred: Boolean = false
        var outputChars: Int = 0
    }

    private companion object {
        const val ARG_ENDPOINT = "activationEndpoint"
        const val ARG_CA_BASE64 = "activationCaBase64"
        const val ARG_LICENSE_KEY_BASE64 = "activationLicenseKeyDerBase64"
        const val ARG_PRODUCT = "activationProductId"
        const val ACTIVATION_CODE_FILE = "physical-activation-code.once"
        const val ALGORITHM = "ECDSA-P256-SHA256"

        const val PRINCIPAL = "liliya-product-runtime"
        const val GRANTED_CAPABILITY = "model.local"
        const val DENIED_CAPABILITY = "runtime.ungranted"
        const val PROVIDER = "production-trusted-qwen-runtime"

        const val QWEN_FILE_NAME = "Qwen3-1.7B-Q4_K_M.gguf"
        const val QWEN_BYTES = 1_282_439_264L
        const val QWEN_SHA256 =
            "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"

        const val SEGMENT_BYTES = 4 * 1024 * 1024
    }
}
