package pro.liliya.app

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.authority.ScopedGrantAuthorityPolicy
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
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicensePolicyRequest
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseSubject
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseTrustedVerificationKey
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.license.LicenseVerifier
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.licensetransport.LicenseHttpTransportClient
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig
import pro.liliya.core.licensetransport.LicenseServiceTransportRequest
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
 * Physical-device causal overlay over the exact frozen RC and accepted live startup gate.
 *
 * Proves only this boundary:
 * live signed License -> exact Authority Admission -> StartupProvisioner -> exact Qwen3 execution.
 *
 * It deliberately stops before prepared inputs / HostBootstrap. That later boundary remains a
 * separate acceptance claim. A denied Authority request must not touch any downstream startup
 * owner and therefore cannot stage, activate, or infer Qwen.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalLiveAdmittedQwenExecutionInstrumentedTest {

    @Test
    fun live_admission_causes_exact_qwen_execution_while_authority_denial_stays_fail_closed() {
        assertEquals("arm64-v8a", Build.SUPPORTED_ABIS.firstOrNull())

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val application = instrumentation.targetContext.applicationContext as LiliyaApplication
        val targetContext = instrumentation.targetContext.applicationContext

        val endpoint = URL(requiredArgument(arguments.getString(ARG_ENDPOINT)))
        assertEquals("https", endpoint.protocol)

        val caBytes = Base64.getDecoder().decode(
            requiredArgument(arguments.getString(ARG_CA_BASE64))
        )
        try {
            installAcceptanceTrust(caBytes)
        } finally {
            caBytes.fill(0)
        }

        val encryptedStore = ProductionAndroidProductAuthEncryptedStore.create(application)
        val secretProbe = encryptedStore.openSecret()
        try {
            assertTrue(secretProbe.isNotEmpty())
        } finally {
            secretProbe.fill(0)
        }

        val acquisition = ProductionAndroidLicenseEnvelopeAcquisition.acquire(
            client = LicenseHttpTransportClient(
                LicenseHttpTransportConfig(
                    endpoint = endpoint,
                    connectTimeoutMillis = 5_000,
                    readTimeoutMillis = 10_000
                )
            ),
            request = LicenseServiceTransportRequest(
                protocolVersion = LicenseServiceProtocolVersion(1),
                operation = LicenseServiceOperation.ISSUE,
                productId = LicenseProductId(PRODUCT),
                subjectReference = LicenseSubject(SUBJECT),
                requestId = LicenseServiceRequestId(REQUEST_ID)
            ),
            authentication = ProductionAndroidProductAuthCredentialAdapter.bearerFactory(encryptedStore)
        )
        val signed = assertIs<ProductionAndroidLicenseEnvelopeAcquisitionResult.Signed>(acquisition)

        val publicKey = Base64.getDecoder().decode(
            requiredArgument(arguments.getString(ARG_ENTITLEMENT_KEY_BASE64))
        )
        val trusted = try {
            LicenseTrustedVerificationKey.of(
                keyId = signed.envelope.signingKeyId,
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
            ).verify(signed.envelope)
        )

        val foundation = foundation()
        val policyNow = verified.entitlement.notBefore
        val principal = AuthorityPrincipal(PRINCIPAL)
        val grantedCapability = CapabilityId(GRANTED_CAPABILITY)
        val deniedCapability = CapabilityId(DENIED_CAPABILITY)
        val scope = AuthorityScope.GLOBAL

        val capabilityAuthority = CapabilityAuthorityComposition(
            foundation = foundation,
            now = { policyNow }
        )
        assertIs<AndroidProductRuntimeStartupAuthorityAssemblyResult.Ready>(
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

        val activeGrants = capabilityAuthority.directGrantSnapshot().map { it.asScopedGrant() }
        assertEquals(1, activeGrants.size)

        val licenseAuthority = LicenseAuthorityComposition(
            foundation = foundation,
            authorityManager = AuthorityManager(
                policy = ScopedGrantAuthorityPolicy(
                    grants = activeGrants,
                    now = { policyNow }
                ),
                observability = foundation.observability
            )
        )

        val feature = verified.entitlement.features.first()
        val licenseRequest = LicensePolicyRequest(
            productId = verified.entitlement.productId,
            feature = feature,
            subject = verified.entitlement.subject
        )
        val policyContext = LicensePolicyContext(
            now = policyNow,
            minimumRevocationEpoch = verified.entitlement.revocationEpoch,
            minimumReplaySequence = verified.entitlement.replaySequence,
            suspiciousTimeOrReplayState = false
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

        val negativeAdmission = assertIs<AndroidProductRuntimeAdmissionResult.Rejected>(
            admission(deniedCapability)
        )
        assertEquals(AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED, negativeAdmission.reason)

        val deniedCalls = DownstreamCalls.create()
        val deniedProvisioning = AndroidProductRuntimeStartupProvisioner.prepare(
            ports(
                admission = { admission(deniedCapability) },
                calls = deniedCalls,
                modelPort = AndroidProductRuntimeStartupModelPort {
                    deniedCalls.model.incrementAndGet()
                    deniedCalls.qwen.incrementAndGet()
                    AndroidProductRuntimeStartupPreparationResult.Rejected
                }
            )
        )
        val deniedAtGate = assertIs<AndroidProductRuntimeStartupProvisioningResult.AdmissionRejected>(
            deniedProvisioning
        )
        val deniedResult = assertIs<AndroidProductRuntimeAdmissionResult.Rejected>(deniedAtGate.result)
        assertEquals(AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED, deniedResult.reason)
        assertEquals(0, deniedCalls.activeDek.get())
        assertEquals(0, deniedCalls.semantic.get())
        assertEquals(0, deniedCalls.model.get())
        assertEquals(0, deniedCalls.prepared.get())
        assertEquals(0, deniedCalls.qwen.get())

        val admitted = assertIs<AndroidProductRuntimeAdmissionResult.Admitted>(
            admission(grantedCapability)
        )
        assertTrue(admitted.ownership.toString().isNotBlank())

        val positiveCalls = DownstreamCalls.create()
        val qwenEvidence = QwenEvidence()
        val runtimeOwnership = ProtectedModelRuntimeOwnership()
        val model = ProtectedModelReference(
            packageId = ProtectedModelPackageId("physical-live-admitted-qwen3-1.7b-q4km"),
            generation = ProtectedModelGeneration(1)
        )
        runtimeOwnership.replaceTarget(model)
        val llama = llamaAssembly(targetContext, foundation, runtimeOwnership)

        val positiveProvisioning = AndroidProductRuntimeStartupProvisioner.prepare(
            ports(
                admission = { admission(grantedCapability) },
                calls = positiveCalls,
                modelPort = AndroidProductRuntimeStartupModelPort {
                    positiveCalls.model.incrementAndGet()
                    positiveCalls.qwen.incrementAndGet()
                    val staged = provisionExactCandidate(
                        coordinator = llama.stagingCoordinator,
                        model = model,
                        evidence = qwenEvidence
                    )
                    var retired = false
                    try {
                        val activated = assertIs<CognitiveModelActivationResult.Activated>(
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
                            llama.cognitiveRuntime.beginQuiescing(activated.session)
                        )
                        assertIs<CognitiveModelRetirementResult.Retired>(
                            llama.cognitiveRuntime.retireIfDrained(activated.session)
                        )
                        assertIs<LargeProtectedModelStagingRetireResult.Retired>(staged.retire())
                        retired = true
                    } finally {
                        if (!retired) {
                            runCatching { staged.retire() }
                        }
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
        assertTrue(qwenEvidence.downloadVerified)
        assertTrue(qwenEvidence.activated)
        assertTrue(qwenEvidence.inferred)
        assertTrue(qwenEvidence.outputChars > 0)

        val replaySequence = verified.entitlement.replaySequence?.value ?: -1L
        recordEvidence(
            linkedMapOf(
                "https" to "true",
                "encryptedProductAuth" to "true",
                "liveLicense" to "true",
                "frozenCoreSignatureVerified" to "true",
                "authorityAdmission" to "true",
                "authorityDeniedDownstreamCalls" to "0",
                "authorityDeniedQwenCalls" to deniedCalls.qwen.get().toString(),
                "admittedActiveDekCalls" to positiveCalls.activeDek.get().toString(),
                "admittedSemanticCalls" to positiveCalls.semantic.get().toString(),
                "admittedModelCalls" to positiveCalls.model.get().toString(),
                "admittedPreparedCalls" to positiveCalls.prepared.get().toString(),
                "qwenModel" to QWEN_FILE_NAME,
                "qwenBytes" to QWEN_BYTES.toString(),
                "qwenSha256" to QWEN_SHA256,
                "qwenRevision" to QWEN_REVISION,
                "qwenDownloadVerified" to qwenEvidence.downloadVerified.toString(),
                "qwenActivated" to qwenEvidence.activated.toString(),
                "qwenInference" to qwenEvidence.inferred.toString(),
                "qwenOutputChars" to qwenEvidence.outputChars.toString(),
                "hostBootstrapStarted" to "false",
                "replaySequence" to replaySequence.toString()
            )
        )
        println("LILIYA_PHYSICAL_LIVE_ADMITTED_QWEN_EXECUTION=PASS")
    }

    private fun ports(
        admission: () -> AndroidProductRuntimeAdmissionResult,
        calls: DownstreamCalls,
        modelPort: AndroidProductRuntimeStartupModelPort
    ): AndroidProductRuntimeStartupProvisioningPorts {
        val semanticRoot = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "physical-live-admitted-qwen-semantic-placeholder"
        )
        val encoder = File(semanticRoot, "encoder-placeholder.onnx")
        return AndroidProductRuntimeStartupProvisioningPorts(
            admission = AndroidProductRuntimeStartupAdmissionPort { admission() },
            activeDek = AndroidProductRuntimeStartupActiveDekPort {
                calls.activeDek.incrementAndGet()
                AndroidProductRuntimeStartupPreparationResult.Ready(
                    CognitiveDekReference(
                        id = CognitiveDekId("physical-live-admitted-qwen-cognitive-dek"),
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
                error("physical admitted Qwen GGUF SHA-256 mismatch")
            }
            evidence.downloadVerified = true

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
                RuntimeModelSessionId("physical-live-admitted-qwen-" + ids.incrementAndGet())
            },
            limits = CognitiveRuntimeLimits(
                maxInferenceOutputChars = 512,
                maxModelPromptChars = 8192
            )
        )
    }

    private fun inferenceRequest(): CognitiveInferenceRequest {
        val turn = CognitiveTurnReference(
            id = CognitiveTurnId("physical-live-admitted-qwen-inference"),
            generation = CognitiveTurnGeneration(1)
        )
        return CognitiveInferenceRequest(
            turn = turn,
            input = CognitiveInput("What is two plus two? /no_think"),
            context = CognitiveContextSnapshot(turn, emptyList()),
            maxOutputChars = 512
        )
    }

    private fun foundation(): FoundationComposition {
        val logs = InMemoryLogWriter()
        val correlations = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator {
                "physical-live-admitted-qwen-" + correlations.incrementAndGet()
            }
        )
    }

    private fun installAcceptanceTrust(certificateBytes: ByteArray) {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificateBytes))
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null)
        store.setCertificateEntry("liliya-local-acceptance-ca", certificate)

        val trustManagerFactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        trustManagerFactory.init(store)

        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, trustManagerFactory.trustManagers, null)
        HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
    }

    private fun readExactSegment(input: InputStream, expected: Int): ByteArray {
        val output = ByteArray(expected)
        var offset = 0
        while (offset < expected) {
            val read = input.read(output, offset, expected - offset)
            if (read < 0) error("candidate GGUF ended early")
            offset += read
        }
        return output
    }

    private fun segmentCount(bytes: Long): Int =
        ((bytes + SEGMENT_BYTES - 1L) / SEGMENT_BYTES).toInt()

    private fun recordEvidence(values: Map<String, String>) {
        val bundle = Bundle()
        values.forEach { (key, value) -> bundle.putString("admittedQwen." + key, value) }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, bundle)
    }

    private fun requiredArgument(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: error("missing physical live-admitted-Qwen acceptance argument")

    private data class DownstreamCalls(
        val activeDek: AtomicInteger,
        val semantic: AtomicInteger,
        val model: AtomicInteger,
        val prepared: AtomicInteger,
        val qwen: AtomicInteger
    ) {
        companion object {
            fun create() = DownstreamCalls(
                activeDek = AtomicInteger(0),
                semantic = AtomicInteger(0),
                model = AtomicInteger(0),
                prepared = AtomicInteger(0),
                qwen = AtomicInteger(0)
            )
        }
    }

    private class QwenEvidence {
        var downloadVerified: Boolean = false
        var activated: Boolean = false
        var inferred: Boolean = false
        var outputChars: Int = 0
    }

    private companion object {
        const val ARG_ENDPOINT = "liveLicenseEndpoint"
        const val ARG_CA_BASE64 = "liveCaBase64"
        const val ARG_ENTITLEMENT_KEY_BASE64 = "liveEntitlementKeyBase64"

        const val PRODUCT = "liliya-pro"
        const val SUBJECT = "physical-android-product-auth-subject"
        const val REQUEST_ID = "physical-android-product-auth-admitted-qwen-001"
        const val ALGORITHM = "ECDSA-P256-SHA256"

        const val PRINCIPAL = "physical-liliya-app"
        const val GRANTED_CAPABILITY = "model.local"
        const val DENIED_CAPABILITY = "runtime.ungranted"
        const val PROVIDER = "physical-live-admitted-qwen-acceptance"

        const val QWEN_FILE_NAME = "Qwen3-1.7B-Q4_K_M.gguf"
        const val QWEN_BYTES = 1_282_439_264L
        const val QWEN_SHA256 = "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"
        const val QWEN_REVISION = "daeb8e2d528a760970442092f6bf1e55c3b659eb"
        const val QWEN_URL =
            "https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/" +
                QWEN_REVISION + "/" + QWEN_FILE_NAME + "?download=true"

        const val SEGMENT_BYTES = 4 * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 120_000
    }
}
