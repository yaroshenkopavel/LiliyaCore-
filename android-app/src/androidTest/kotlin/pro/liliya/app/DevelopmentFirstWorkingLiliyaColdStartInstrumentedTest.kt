package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.bouncycastle.jce.provider.BouncyCastleProvider
import pro.liliya.android.llamacppengine.AndroidLlamaCppCognitiveModelAssembly
import pro.liliya.android.llamacppengine.LlamaCppEnginePolicy
import pro.liliya.android.protectedmodel.staging.AndroidProtectedModelStagingPolicy
import pro.liliya.android.runtime.AndroidHeartProductionPersonaDefinition
import pro.liliya.android.runtime.AndroidProductRuntimeCognitiveStructuralOwnersFactory
import pro.liliya.android.runtime.AndroidProductRuntimeCognitiveStructuralOwnersResult
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunKeyChoice
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunKeySecurity
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunProductInput
import pro.liliya.android.runtime.AndroidProductRuntimeFirstWorkingLearningDisabled
import pro.liliya.android.runtime.AndroidProductRuntimeLicenseTrustKey
import pro.liliya.android.runtime.AndroidProductRuntimeObservability
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelBudgetInput
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelSignerTrust
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelSignerTrustKey
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelSignerTrustResult
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelStagingProvisioningFactory
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAdmissionInput
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityPlan
import pro.liliya.android.runtime.AndroidProductRuntimeStartupPreparedInputOwnerTemplate
import pro.liliya.android.runtime.ProductChatResult
import pro.liliya.android.semanticprovider.AndroidOfflineSemanticArtifactProvisionResult
import pro.liliya.android.semanticprovider.AndroidOfflineSemanticArtifactProvisioner
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.cognitive.DeterministicCognitiveModelRequestCompiler
import pro.liliya.core.cognitive.CognitiveModelRuntimeSessionIdSource
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.CognitiveRuntimeScopeId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.identity.SelfIdentityId
import pro.liliya.core.identity.SelfName
import pro.liliya.core.identity.SelfSourceId
import pro.liliya.core.identity.SelfSourceReference
import pro.liliya.core.learning.LearningPolicy
import pro.liliya.core.learning.LearningPolicyComposition
import pro.liliya.core.learning.LearningPolicyId
import pro.liliya.core.learning.LearningPolicyInstallResult
import pro.liliya.core.learning.LearningPolicyReference
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseEntitlement
import pro.liliya.core.license.LicenseEntitlementCanonicalCodec
import pro.liliya.core.license.LicenseFeature
import pro.liliya.core.license.LicenseId
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseReplaySequence
import pro.liliya.core.license.LicenseRevocationEpoch
import pro.liliya.core.license.LicenseSignature
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseSubject
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.modelengine.ModelEngineLoadFailure
import pro.liliya.core.modelengine.ModelEngineLoadResult
import pro.liliya.core.modelengine.ModelEngineLoaderPort
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.personality.PersonalityAttribute
import pro.liliya.core.personality.PersonalityAttributeKey
import pro.liliya.core.personality.PersonalityAttributeValue
import pro.liliya.core.personality.PersonalityProfileId
import pro.liliya.core.personality.PersonalitySourceId
import pro.liliya.core.personality.PersonalitySourceReference
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelResourceBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBudgets
import pro.liliya.core.protectedmodel.ModelDekGeneration
import pro.liliya.core.protectedmodel.ModelDekId
import pro.liliya.core.protectedmodel.ModelDekReference
import pro.liliya.core.protectedmodel.ProtectedModelAccessCoordinator
import pro.liliya.core.protectedmodel.ProtectedModelAccessPolicy
import pro.liliya.core.protectedmodel.ProtectedModelDekMaterial
import pro.liliya.core.protectedmodel.ProtectedModelDekResolver
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
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
import pro.liliya.core.runtime.hardening.RuntimeModelSessionId
import pro.liliya.packager.ProtectedModelOfflinePackager
import pro.liliya.packager.ProtectedModelPackagingContainerBudgets
import pro.liliya.packager.ProtectedModelPackagingRequest
import pro.liliya.packager.ProtectedModelPackagingResult
import pro.liliya.packager.ProtectedModelPackagingSignResult
import pro.liliya.packager.ProtectedModelPackagingSignature
import pro.liliya.packager.ProtectedModelPackagingSigner

@RunWith(AndroidJUnit4::class)
class DevelopmentFirstWorkingLiliyaColdStartInstrumentedTest {

    @Test
    fun exact_test_product_inputs_reach_ready_runtime_and_complete_first_chat_turn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext.applicationContext
        val testContext = instrumentation.context
        val application = targetContext as LiliyaApplication
        val fixtureRoot = File(targetContext.filesDir, "development-first-working-liliya-cold-start")
        val semanticRoot = File(
            targetContext.filesDir,
            AndroidOfflineSemanticArtifactProvisioner.DEFAULT_DIRECTORY
        )
        val stagingRoot = File(targetContext.filesDir, "large-protected-model-staging-v1")
        val modelDekDirectory = "development-first-working-liliya-model-dek"
        val modelDekRoot = File(targetContext.filesDir, modelDekDirectory)
        fixtureRoot.deleteRecursively()
        semanticRoot.deleteRecursively()
        stagingRoot.deleteRecursively()
        modelDekRoot.deleteRecursively()
        require(fixtureRoot.mkdirs())

        val modelSource = File(fixtureRoot, STORIES_15M_ASSET)
        val protectedPackage = File(fixtureRoot, "stories15M-q4_0.lpm1")
        val model = ProtectedModelReference(
            ProtectedModelPackageId("first-working-liliya-stories15m"),
            ProtectedModelGeneration(1)
        )
        val modelDek = ModelDekReference(
            ModelDekId("first-working-liliya-model-dek"),
            ModelDekGeneration(1)
        )
        val modelDekBytes = ByteArray(32) { index -> (index * 7 + 11).toByte() }
        val modelSignerId = ProtectedModelSignerId("first-working-liliya-model-signer")
        val modelSignerProvider = BouncyCastleProvider()
        val modelSigner = KeyPairGenerator.getInstance(
            "Ed25519",
            modelSignerProvider
        ).generateKeyPair()
        var modelDekAssembly: pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelDekAssembly? = null
        var modelDekProtectorDescriptor: pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorDescriptor? = null

        try {
            provisionSemanticBundle(targetContext, testContext)
            testContext.assets.open(STORIES_15M_ASSET).use { input ->
                FileOutputStream(modelSource).use { output -> input.copyTo(output) }
            }
            assertEquals(STORIES_15M_BYTES, modelSource.length())

            val resourceBudgets = resourceBudgets()
            val packageBudgets = packageBudgets()
            val containerBytes = 24L * 1024L * 1024L
            val signatureBytes = 128
            assertIs<ProtectedModelPackagingResult.Packaged>(
                ProtectedModelOfflinePackager().packageFile(
                    ProtectedModelPackagingRequest(
                        source = modelSource,
                        destination = protectedPackage,
                        model = model,
                        modelProfileId = ProtectedModelProfileId("first-working-liliya-real-model"),
                        modelDek = modelDek,
                        modelDekMaterial = ProtectedModelDekMaterial(modelDekBytes),
                        signerId = modelSignerId,
                        signer = ProtectedModelPackagingSigner { input ->
                            val signer = Signature.getInstance("Ed25519", modelSignerProvider)
                            signer.initSign(modelSigner.private)
                            signer.update(input)
                            ProtectedModelPackagingSignResult.Signed(
                                ProtectedModelPackagingSignature(signer.sign())
                            )
                        },
                        resourceBudgets = resourceBudgets,
                        packageBudgets = packageBudgets,
                        containerBudgets = ProtectedModelPackagingContainerBudgets(
                            containerBytes,
                            signatureBytes
                        ),
                        segmentPlaintextBytes = SEGMENT_BYTES
                    )
                )
            )

            val fixtureFoundation = foundation()
            val signerTrust = AndroidProductRuntimeProtectedModelSignerTrust.create(
                listOf(
                    AndroidProductRuntimeProtectedModelSignerTrustKey(
                        signerId = modelSignerId.value,
                        material = modelSigner.public.encoded
                    )
                )
            )
            val signerResolver = (
                signerTrust as? AndroidProductRuntimeProtectedModelSignerTrustResult.Ready
            )?.resolver ?: error("Cold-start protected-model signer trust rejected")

            val openedPackage = when (
                val opened = pro.liliya.android.runtime.ProductProtectedModelLocalPackage.open(
                    file = protectedPackage,
                    manifestBudgets = resourceBudgets,
                    packageBudgets = packageBudgets,
                    containerBudgets = pro.liliya.android.runtime.ProductProtectedModelLocalPackageBudgets(
                        maxContainerBytes = containerBytes,
                        maxSignatureBytes = signatureBytes
                    )
                )
            ) {
                is pro.liliya.android.runtime.ProductProtectedModelLocalPackageOpenResult.Opened -> opened
                is pro.liliya.android.runtime.ProductProtectedModelLocalPackageOpenResult.Rejected ->
                    error("Cold-start LPM1 open rejected before DEK provisioning: " + opened.reason)
            }

            when (
                val verified = pro.liliya.core.protectedmodel.LargeProtectedModelPackageVerifier(
                    signerResolver = signerResolver,
                    budgets = packageBudgets,
                    signatureProvider = modelSignerProvider
                ).verify(openedPackage.envelope)
            ) {
                is pro.liliya.core.protectedmodel.LargeProtectedModelPackageVerificationResult.Verified -> {
                    assertEquals(model, verified.value.manifest.payload.model)
                    assertEquals(modelDek, verified.value.manifest.payload.modelDek)
                }
                is pro.liliya.core.protectedmodel.LargeProtectedModelPackageVerificationResult.Rejected ->
                    error("Cold-start LPM1 signature verification rejected before DEK provisioning: " + verified.reason)
                is pro.liliya.core.protectedmodel.LargeProtectedModelPackageVerificationResult.Failed ->
                    error("Cold-start LPM1 signature verification failed before DEK provisioning: " + verified)
            }

            val exactDekAssembly = when (
                val opened = pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelDekAssembly.open(
                    context = targetContext,
                    foundation = fixtureFoundation,
                    directoryName = modelDekDirectory
                )
            ) {
                is pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelDekOpenResult.Ready -> opened.assembly
                else -> error("Cold-start protected-model DEK assembly rejected: " + opened)
            }
            modelDekAssembly = exactDekAssembly

            val protectorCreation = exactDekAssembly.keyProtector.create(
                pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorCreationRequest(
                    id = pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorId(
                        "first-working-liliya-model-dek-protector"
                    ),
                    generation = pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorGeneration(1),
                    requestedSecurityLevel =
                        pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorSecurityLevel.SOFTWARE
                )
            )
            val protectorDescriptor = (
                protectorCreation as? pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorResult.Success<*>
            )?.value as? pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorDescriptor
                ?: error("Cold-start model DEK protector rejected: " + protectorCreation)
            modelDekProtectorDescriptor = protectorDescriptor

            val provisioningRequest = pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningRequest(
                model = model,
                dek = modelDek
            )
            val lmdk1 = lmdk1Artifact(provisioningRequest, modelDekBytes)
            try {
                val registered = assertIs<
                    pro.liliya.core.protectedmodel.ProtectedModelDekProvisionAndRegisterResult.Registered
                >(
                    pro.liliya.android.runtime.AndroidProductRuntimeLocalModelDekImport
                        .importAndRegister(
                            openInput = { java.io.ByteArrayInputStream(lmdk1) },
                            store = exactDekAssembly.store,
                            request = provisioningRequest,
                            protectorDescriptor = protectorDescriptor
                        )
                )
                assertEquals(model, registered.binding.model)
                assertEquals(modelDek, registered.binding.dek)
            } finally {
                lmdk1.fill(0)
            }

            modelDekRoot.walkTopDown().filter { it.isFile }.forEach { file ->
                val durableBytes = file.readBytes()
                try {
                    assertTrue(!containsSubsequence(durableBytes, modelDekBytes))
                } finally {
                    durableBytes.fill(0)
                }
            }

            val protectedOwnership = ProtectedModelRuntimeOwnership().also {
                it.replaceTarget(model)
            }
            val llamaAssembly = llamaAssembly(
                context = targetContext,
                foundation = fixtureFoundation,
                protectedOwnership = protectedOwnership
            )
            val staging = AndroidProductRuntimeProtectedModelStagingProvisioningFactory.create(
                llamaAssembly = llamaAssembly,
                signerResolver = signerResolver,
                packageBudgets = packageBudgets,
                dekResolver = exactDekAssembly.store
            )

            val limits = firstWorkingCognitiveLimits()
            val structural = assertIs<AndroidProductRuntimeCognitiveStructuralOwnersResult.Ready>(
                AndroidProductRuntimeCognitiveStructuralOwnersFactory.create(limits)
            ).owners
            val learningDisabled = AndroidProductRuntimeFirstWorkingLearningDisabled.create()
            val policies = LearningPolicyComposition(fixtureFoundation)
            val installedPolicy = assertIs<LearningPolicyInstallResult.Installed>(
                policies.install(
                    LearningPolicy(
                        id = LearningPolicyId("first-working-liliya-learning-disabled"),
                        rule = "learning disabled until a separately accepted product policy gate",
                        createdAt = FIXTURE_TIME
                    )
                )
            ).ownership
            val principal = AuthorityPrincipal("first-working-liliya")
            val capability = CapabilityId("runtime.start")
            val scope = AuthorityScope.GLOBAL
            val licenseMaterial = licenseMaterial()
            val observability = AndroidProductRuntimeObservability.create(
                File(fixtureRoot, "observability")
            ).also { it.installLoggerWriter() }

            val input = AndroidProductRuntimeFirstRunProductInput(
                context = targetContext,
                observability = observability,
                supportedLicenseSchemaVersion = 1,
                licenseTrustKeys = listOf(
                    AndroidProductRuntimeLicenseTrustKey(
                        keyId = licenseMaterial.keyId.value,
                        material = licenseMaterial.publicKey
                    )
                ),
                licenseEnvelope = licenseMaterial.envelope,
                authorityPlan = AndroidProductRuntimeStartupAuthorityPlan(
                    capabilities = listOf(
                        CapabilityDescriptor(
                            id = capability,
                            providerId = CapabilityProviderId("first-working-liliya-fixture")
                        )
                    ),
                    directGrants = listOf(
                        DirectAuthorityGrant(
                            principal = principal,
                            capability = capability,
                            scope = scope,
                            expiresAt = Instant.parse("2027-09-10T00:00:00Z")
                        )
                    )
                ),
                admission = AndroidProductRuntimeStartupAdmissionInput(
                    productId = PRODUCT_ID,
                    feature = FEATURE,
                    subject = SUBJECT,
                    now = FIXTURE_TIME,
                    minimumRevocationEpoch = 0,
                    minimumReplaySequence = 1,
                    suspiciousTimeOrReplayState = false,
                    principal = principal.value,
                    capability = capability.value,
                    authorityScope = scope.value
                ),
                keyChoice = AndroidProductRuntimeFirstRunKeyChoice.CreateOnce(
                    dekId = "first-working-liliya-cognitive-dek",
                    protectorId = "first-working-liliya-cognitive-protector",
                    protectorGeneration = 1,
                    security = AndroidProductRuntimeFirstRunKeySecurity.SOFTWARE
                ),
                localModelFile = protectedPackage,
                protectedModelBudgets = AndroidProductRuntimeProtectedModelBudgetInput(
                    maxTotalPlaintextBytes = resourceBudgets.maxTotalPlaintextBytes,
                    maxTotalCiphertextBodyBytes = resourceBudgets.maxTotalCiphertextBodyBytes,
                    maxTotalProtectedPayloadBytes = resourceBudgets.maxTotalProtectedPayloadBytes,
                    maxSegmentCount = resourceBudgets.maxSegmentCount,
                    minNonFinalSegmentPlaintextBytes = resourceBudgets.minNonFinalSegmentPlaintextBytes,
                    maxSegmentPlaintextBytes = resourceBudgets.maxSegmentPlaintextBytes,
                    maxSegmentCiphertextBodyBytes = resourceBudgets.maxSegmentCiphertextBodyBytes,
                    maxStructuralIdentifierChars = resourceBudgets.maxStructuralIdentifierChars,
                    maxCanonicalManifestBytes = resourceBudgets.maxCanonicalManifestBytes,
                    maxModelProfileIdChars = packageBudgets.maxModelProfileIdChars,
                    maxSignerIdChars = packageBudgets.maxSignerIdChars,
                    maxCanonicalSignedManifestBytes = packageBudgets.maxCanonicalSignedManifestBytes,
                    maxContainerBytes = containerBytes,
                    maxSignatureBytes = signatureBytes
                ),
                staging = staging,
                preparedInputOwners = AndroidProductRuntimeStartupPreparedInputOwnerTemplate(
                    memoryStoreId = PersistentStoreId("first-working-liliya-memory"),
                    knowledgeStoreId = PersistentStoreId("first-working-liliya-knowledge"),
                    llamaAssembly = llamaAssembly,
                    maxCandidatesPerSource = limits.maxRetrievalResults,
                    personaDefinition = personaDefinition(),
                    scope = CognitiveRuntimeScopeId("first-working-liliya-runtime"),
                    cognitiveMaterialization = structural.cognitiveMaterialization,
                    outcomeMaterialization = structural.outcomeMaterialization,
                    policies = policies,
                    policyReference = LearningPolicyReference(
                        installedPolicy.policy.id,
                        installedPolicy.generation
                    ),
                    principal = principal,
                    governance = learningDisabled.governance,
                    learningMaterialization = learningDisabled.learningMaterialization,
                    learningMutationStoreId = PersistentStoreId(
                        "first-working-liliya-learning-mutations"
                    ),
                    artifactIds = structural.artifactIds,
                    timestamps = structural.timestamps,
                    limits = limits
                )
            )

            assertIs<ProductionAndroidFirstRunProductInstallResult.Installed>(
                application.configureFirstRun(input)
            )
            val startupOutcome = application.startApplicationRuntime()
            if (startupOutcome is ProductionAndroidAppStartupOutcome.ProvisioningRejected) {
                error("Cold-start provisioning rejected: " + startupOutcome.result)
            }
            val startup = assertIs<ProductionAndroidAppStartupOutcome.Runtime>(startupOutcome)
            assertEquals(ProductionAndroidAppRuntimeState.READY, startup.state)

            val chatResult = application.runtimeOwner.send("Hello Liliya")
            val chat = when (chatResult) {
                is ProductChatResult.Completed -> chatResult
                is ProductChatResult.Rejected -> {
                    val generationReason = generationRejectionReason(fixtureRoot)
                    error(
                        "Cold-start chat rejected: " + chatResult.reason +
                            generationReason?.let { " / $it" }.orEmpty()
                    )
                }
            }
            assertTrue(chat.reply.isNotBlank())
            assertTrue(chat.reply.length <= MAX_OUTPUT_CHARS)
        } finally {
            val exactDekAssembly = modelDekAssembly
            val exactProtector = modelDekProtectorDescriptor
            if (exactDekAssembly != null && exactProtector != null) {
                runCatching { exactDekAssembly.keyProtector.retire(exactProtector) }
            }
            modelDekBytes.fill(0)
            application.runtimeOwner.close()
            fixtureRoot.deleteRecursively()
            semanticRoot.deleteRecursively()
            stagingRoot.deleteRecursively()
            modelDekRoot.deleteRecursively()
        }
    }

    private fun generationRejectionReason(fixtureRoot: File): String? {
        val log = File(fixtureRoot, "observability/liliya-events.log")
        if (!log.isFile) return null
        return log.useLines { lines ->
            lines.filter { it.contains("\tCOGNITIVE_GENERATION_REJECTED\t") }
                .mapNotNull { line ->
                    GENERATION_REJECTION_REASON.find(line)?.groupValues?.getOrNull(1)
                }
                .lastOrNull()
        }
    }

    private fun lmdk1Artifact(
        request: pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningRequest,
        material: ByteArray
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(output).use { data ->
            data.write(
                byteArrayOf(
                    'L'.code.toByte(),
                    'M'.code.toByte(),
                    'D'.code.toByte(),
                    'K'.code.toByte(),
                    '1'.code.toByte()
                )
            )
            data.writeInt(1)
            writeLmdkString(data, request.model.packageId.value)
            data.writeLong(request.model.generation.value)
            writeLmdkString(data, request.dek.id.value)
            data.writeLong(request.dek.generation.value)
            data.writeInt(material.size)
            data.write(material)
        }
        return output.toByteArray()
    }

    private fun writeLmdkString(output: java.io.DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        try {
            output.writeInt(bytes.size)
            output.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        for (start in 0..haystack.size - needle.size) {
            var same = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    same = false
                    break
                }
            }
            if (same) return true
        }
        return false
    }

    private fun provisionSemanticBundle(
        targetContext: android.content.Context,
        testContext: android.content.Context
    ) {
        val provisioner = AndroidOfflineSemanticArtifactProvisioner()
        val result = testContext.assets.open(SEMANTIC_ENCODER_ASSET).use { encoder ->
            testContext.assets.open(SEMANTIC_TOKENIZER_ASSET).use { tokenizer ->
                provisioner.provision(
                    context = targetContext,
                    encoderInput = encoder,
                    tokenizerInput = tokenizer
                )
            }
        }
        assertIs<AndroidOfflineSemanticArtifactProvisionResult.Provisioned>(result)
    }

    private fun foundation(): FoundationComposition {
        val logs = InMemoryLogWriter()
        val correlations = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator {
                "first-working-liliya-foundation-${correlations.incrementAndGet()}"
            }
        )
    }

    private fun llamaAssembly(
        context: android.content.Context,
        foundation: FoundationComposition,
        protectedOwnership: ProtectedModelRuntimeOwnership
    ): AndroidLlamaCppCognitiveModelAssembly {
        val sessions = AtomicInteger(0)
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
        val compiler = DeterministicCognitiveModelRequestCompiler()
        return AndroidLlamaCppCognitiveModelAssembly.create(
            context = context,
            stagingPolicy = AndroidProtectedModelStagingPolicy(freeSpaceReserveBytes = 0L),
            stagingBudgets = LargeProtectedModelStagingBudgets(
                maxTotalPlaintextBytes = STORIES_15M_BYTES,
                maxSegmentPlaintextBytes = SEGMENT_BYTES.toLong(),
                maxSegmentCount = 128,
                maxActiveAttempts = 1,
                maxOpaqueIdentifierChars = 128
            ),
            llamaPolicy = LlamaCppEnginePolicy(
                contextTokens = 2_048,
                maxPromptTokens = 1_024,
                maxGeneratedTokens = 512,
                batchTokens = 256,
                microBatchTokens = 64,
                threadCount = 1,
                maxPromptChars = MAX_PROMPT_CHARS,
                maxPromptUtf8Bytes = MAX_PROMPT_CHARS * 4,
                maxOutputChars = MAX_OUTPUT_CHARS,
                maxOutputUtf8Bytes = MAX_OUTPUT_CHARS * 4,
                useMmap = true,
            ),
            foundation = foundation,
            protectedAccess = protectedAccess,
            legacyEngineLoader = legacyLoader,
            compiler = compiler,
            sessionIds = CognitiveModelRuntimeSessionIdSource {
                RuntimeModelSessionId(
                    "first-working-liliya-session-${sessions.incrementAndGet()}"
                )
            },
            limits = firstWorkingCognitiveLimits()
        )
    }

    private fun personaDefinition() = AndroidHeartProductionPersonaDefinition(
        selfIdentityId = SelfIdentityId("liliya"),
        selfName = SelfName("Liliya"),
        selfSourceId = SelfSourceId("first-working-liliya-fixture"),
        selfSourceReference = SelfSourceReference("development-cold-start-v0.1"),
        selfCreatedAt = FIXTURE_TIME,
        personalityProfileId = PersonalityProfileId("liliya-first-working-persona"),
        personalityAttributes = listOf(
            PersonalityAttribute(
                PersonalityAttributeKey("tone"),
                PersonalityAttributeValue("helpful concise assistant")
            )
        ),
        personalitySourceId = PersonalitySourceId("first-working-liliya-fixture"),
        personalitySourceReference = PersonalitySourceReference("development-cold-start-v0.1"),
        personalityCreatedAt = FIXTURE_TIME
    )

    private data class LicenseMaterial(
        val keyId: LicenseKeyId,
        val publicKey: ByteArray,
        val envelope: LicenseSignedEnvelope
    )

    private fun licenseMaterial(): LicenseMaterial {
        val version = LicenseVersion(1)
        val keyId = LicenseKeyId("first-working-liliya-license-key")
        val entitlement = LicenseEntitlement(
            id = LicenseId("first-working-liliya-license"),
            subject = LicenseSubject(SUBJECT),
            productId = LicenseProductId(PRODUCT_ID),
            features = setOf(LicenseFeature(FEATURE)),
            version = version,
            signingKeyId = keyId,
            issuedAt = Instant.parse("2026-09-09T00:00:00Z"),
            notBefore = Instant.parse("2026-09-09T00:00:00Z"),
            expiresAt = Instant.parse("2027-09-10T00:00:00Z"),
            offlineLeaseUntil = Instant.parse("2026-10-10T00:00:00Z"),
            revocationEpoch = LicenseRevocationEpoch(0),
            replaySequence = LicenseReplaySequence(1)
        )
        val payload = LicenseEntitlementCanonicalCodec.encode(entitlement)
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(payload.copyBytes())
            sign()
        }
        return LicenseMaterial(
            keyId = keyId,
            publicKey = pair.public.encoded,
            envelope = LicenseSignedEnvelope(
                schemaVersion = version,
                algorithm = LicenseAlgorithm("ECDSA-P256-SHA256"),
                signingKeyId = keyId,
                payload = payload,
                signature = LicenseSignature.of(signature)
            )
        )
    }

    private fun resourceBudgets() = LargeProtectedModelResourceBudgets(
        maxTotalPlaintextBytes = 32L * 1024L * 1024L,
        maxTotalCiphertextBodyBytes = 32L * 1024L * 1024L,
        maxTotalProtectedPayloadBytes = 33L * 1024L * 1024L,
        maxSegmentCount = 128,
        minNonFinalSegmentPlaintextBytes = 64L * 1024L,
        maxSegmentPlaintextBytes = 1024L * 1024L,
        maxSegmentCiphertextBodyBytes = 1024L * 1024L,
        maxStructuralIdentifierChars = 256,
        maxCanonicalManifestBytes = 2L * 1024L * 1024L
    )

    private fun firstWorkingCognitiveLimits() = CognitiveRuntimeLimits(
        maxInferenceOutputChars = MAX_OUTPUT_CHARS,
        maxModelPromptChars = MAX_PROMPT_CHARS,
        maxPlanningGoalChars = 64,
        maxPlanningSteps = 1,
        maxPlanningStepChars = 64,
        maxReasoningPremises = 1,
        maxReasoningPremiseChars = 64,
        maxReasoningAnalysisChars = 64,
        maxReasoningConclusionChars = 64,
        maxDecisionOptions = 1,
        maxDecisionOptionChars = 64,
        maxDecisionRationaleChars = 64,
        maxResultChars = 64,
        maxReflectionChars = 64,
        maxLearningProposalChars = 64
    )

    private fun packageBudgets() = LargeProtectedModelPackageBudgets(
        maxModelProfileIdChars = 128,
        maxSignerIdChars = 128,
        maxCanonicalSignedManifestBytes = 2L * 1024L * 1024L
    )

    private companion object {
        const val PRODUCT_ID = "liliya-core"
        const val FEATURE = "runtime"
        const val SUBJECT = "development-device-owner"
        const val STORIES_15M_ASSET = "stories15M-q4_0.gguf"
        const val STORIES_15M_BYTES = 19_077_344L
        const val SEMANTIC_ENCODER_ASSET = "multilingual-e5-small-liliya-v0.1.onnx"
        const val SEMANTIC_TOKENIZER_ASSET = "multilingual-e5-small-tokenizer-v0.1.onnx"
        const val SEGMENT_BYTES = 256 * 1024
        const val MAX_PROMPT_CHARS = 2_048
        const val MAX_OUTPUT_CHARS = 768
        val GENERATION_REJECTION_REASON = Regex("(?:^|,)rejectionReason=([^,\\t]+)")
        val FIXTURE_TIME: Instant = Instant.parse("2026-09-10T00:00:00Z")
    }
}
