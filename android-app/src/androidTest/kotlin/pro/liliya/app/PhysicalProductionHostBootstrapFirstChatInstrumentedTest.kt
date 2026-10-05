package pro.liliya.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.bouncycastle.jce.provider.BouncyCastleProvider
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingProvider
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingResult
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
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseDeviceBindingReference
import pro.liliya.core.license.LicenseDeviceBindingReferenceFactory
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
class PhysicalProductionHostBootstrapFirstChatInstrumentedTest {

    @Test
    fun real_license_exact_qwen_reaches_ready_hostbootstrap_and_completes_first_chat() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext.applicationContext
        val testContext = instrumentation.context
        val arguments = InstrumentationRegistry.getArguments()
        val preserveOfflineResumeBaseline =
            arguments.getString(ARG_PRESERVE_OFFLINE_RESUME_BASELINE)
                ?.toBooleanStrictOrNull() ?: false
        val application = targetContext as LiliyaApplication
        val fixtureRoot = File(targetContext.filesDir, "physical-production-hostbootstrap-cold-start")
        val semanticRoot = File(
            targetContext.filesDir,
            AndroidOfflineSemanticArtifactProvisioner.DEFAULT_DIRECTORY
        )
        val stagingRoot = File(targetContext.filesDir, "large-protected-model-staging-v1")
        val runId = if (preserveOfflineResumeBaseline) {
            "offline-resume-baseline"
        } else {
            System.currentTimeMillis().toString()
        }
        val modelDekDirectory = "physical-production-hostbootstrap-model-dek"
        val modelDekRoot = File(targetContext.filesDir, modelDekDirectory)
        val modelDekProtectorId =
            if (preserveOfflineResumeBaseline) {
                OFFLINE_RESUME_MODEL_DEK_PROTECTOR_ID
            } else {
                "physical-production-hostbootstrap-model-dek-protector-" + runId
            }
        val cognitiveStorageDirectoryName =
            if (preserveOfflineResumeBaseline) {
                OFFLINE_RESUME_COGNITIVE_DIRECTORY
            } else {
                "physical-production-hostbootstrap-cognitive-" + runId
            }
        val cognitiveStorageRoot = File(targetContext.filesDir, cognitiveStorageDirectoryName)
        val cognitiveDekId =
            if (preserveOfflineResumeBaseline) {
                OFFLINE_RESUME_COGNITIVE_DEK_ID
            } else {
                "physical-production-hostbootstrap-cognitive-dek-" + runId
            }
        val cognitiveProtectorId =
            if (preserveOfflineResumeBaseline) {
                OFFLINE_RESUME_COGNITIVE_PROTECTOR_ID
            } else {
                "physical-production-hostbootstrap-cognitive-protector-" + runId
            }

        if (preserveOfflineResumeBaseline) {
            val modelCleanup =
                PhysicalAcceptanceCognitiveProtectorCleanup.retireProtectedModelExactIfPresent(
                    targetContext,
                    modelDekProtectorId,
                    1L
                )
            require(modelCleanup == "ABSENT" || modelCleanup == "RETIRED") {
                "physical production model protector pre-cleanup rejected: $modelCleanup"
            }

            val cognitiveCleanup =
                PhysicalAcceptanceCognitiveProtectorCleanup.retireExactIfPresent(
                    targetContext,
                    cognitiveProtectorId,
                    1L
                )
            require(cognitiveCleanup == "ABSENT" || cognitiveCleanup == "RETIRED") {
                "physical production cognitive protector pre-cleanup rejected: $cognitiveCleanup"
            }
        }

        fixtureRoot.deleteRecursively()
        semanticRoot.deleteRecursively()
        stagingRoot.deleteRecursively()
        modelDekRoot.deleteRecursively()
        cognitiveStorageRoot.deleteRecursively()
        require(fixtureRoot.mkdirs())

        val modelSource = File(targetContext.filesDir, QWEN_FILE_NAME)
        val protectedPackage = File(fixtureRoot, "Qwen3-1.7B-Q4_K_M.lpm1")
        val model = ProtectedModelReference(
            ProtectedModelPackageId("physical-production-hostbootstrap-qwen3-1.7b-q4km"),
            ProtectedModelGeneration(1)
        )
        val modelDek = ModelDekReference(
            ModelDekId("physical-production-hostbootstrap-model-dek"),
            ModelDekGeneration(1)
        )
        val modelDekBytes = ByteArray(32) { index -> (index * 7 + 11).toByte() }
        val modelSignerId = ProtectedModelSignerId("physical-production-hostbootstrap-model-signer")
        val modelSignerProvider = BouncyCastleProvider()
        val modelSigner = if (preserveOfflineResumeBaseline) {
            offlineResumeModelSignerKeyPair(modelSignerProvider)
        } else {
            KeyPairGenerator.getInstance(
                "Ed25519",
                modelSignerProvider
            ).generateKeyPair()
        }
        var modelDekAssembly: pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelDekAssembly? = null
        var modelDekProtectorDescriptor: pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorDescriptor? = null
        var fullAcceptancePassed = false

        try {
            provisionSemanticBundle(targetContext, testContext)
            assertTrue(modelSource.isFile)
            assertEquals(QWEN_BYTES, modelSource.length())
            assertEquals(QWEN_SHA256, sha256(modelSource))

            val resourceBudgets = resourceBudgets()
            val packageBudgets = packageBudgets()
            val containerBytes = QWEN_CONTAINER_BYTES
            val signatureBytes = 128
            assertIs<ProtectedModelPackagingResult.Packaged>(
                ProtectedModelOfflinePackager().packageFile(
                    ProtectedModelPackagingRequest(
                        source = modelSource,
                        destination = protectedPackage,
                        model = model,
                        modelProfileId = ProtectedModelProfileId("physical-production-hostbootstrap-real-model"),
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

            val runtimeModelFile = if (preserveOfflineResumeBaseline) {
                val modelDirectory = File(targetContext.filesDir, "models")
                modelDirectory.deleteRecursively()
                assertIs<ProductionAndroidLocalModelSelectionResult.Selected>(
                    ProductionAndroidLocalModelSelection.importSelected(
                        directory = modelDirectory,
                        openInput = { protectedPackage.inputStream() }
                    )
                ).file
            } else {
                protectedPackage
            }

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
                        modelDekProtectorId
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
                        id = LearningPolicyId("physical-production-hostbootstrap-learning-disabled"),
                        rule = "learning disabled until a separately accepted product policy gate",
                        createdAt = FIXTURE_TIME
                    )
                )
            ).ownership
            val principal = AuthorityPrincipal("liliya-product-runtime")
            val capability = CapabilityId("model.local")
            val scope = AuthorityScope.GLOBAL
            val licenseMaterial = licenseMaterial(targetContext, arguments)
            val policyNow = Instant.now()
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
                            providerId = CapabilityProviderId("physical-production-hostbootstrap-fixture")
                        )
                    ),
                    directGrants = listOf(
                        DirectAuthorityGrant(
                            principal = principal,
                            capability = capability,
                            scope = scope
                        )
                    )
                ),
                admission = AndroidProductRuntimeStartupAdmissionInput(
                    productId = licenseMaterial.productId,
                    feature = licenseMaterial.feature,
                    subject = licenseMaterial.subject,
                    now = policyNow,
                    minimumRevocationEpoch = licenseMaterial.revocationEpoch,
                    minimumReplaySequence = licenseMaterial.replaySequence,
                    suspiciousTimeOrReplayState = false,
                    requiredDeviceBindingReference = licenseMaterial.deviceBinding.value,
                    principal = principal.value,
                    capability = capability.value,
                    authorityScope = scope.value
                ),
                keyChoice = AndroidProductRuntimeFirstRunKeyChoice.CreateOnce(
                    dekId = cognitiveDekId,
                    protectorId = cognitiveProtectorId,
                    protectorGeneration = 1,
                    security = AndroidProductRuntimeFirstRunKeySecurity.SOFTWARE
                ),
                localModelFile = runtimeModelFile,
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
                cognitiveStorageDirectoryName = cognitiveStorageDirectoryName,
                preparedInputOwners = AndroidProductRuntimeStartupPreparedInputOwnerTemplate(
                    memoryStoreId = PersistentStoreId("physical-production-hostbootstrap-memory"),
                    knowledgeStoreId = PersistentStoreId("physical-production-hostbootstrap-knowledge"),
                    llamaAssembly = llamaAssembly,
                    maxCandidatesPerSource = limits.maxRetrievalResults,
                    personaDefinition = personaDefinition(),
                    scope = CognitiveRuntimeScopeId("physical-production-hostbootstrap-runtime"),
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
                        "physical-production-hostbootstrap-learning-mutations"
                    ),
                    artifactIds = structural.artifactIds,
                    timestamps = structural.timestamps,
                    limits = limits
                )
            )

            if (preserveOfflineResumeBaseline) {
                val caBytes = Base64.getDecoder().decode(
                    requiredArgument(arguments.getString(ARG_CA_BASE64))
                )
                try {
                    assertIs<ProductionAndroidOfflineDeploymentProfileStoreResult.Stored>(
                        ProductionAndroidOfflineDeploymentProfileEncryptedStore
                            .create(targetContext)
                            .store(
                                ProductionAndroidOfflineDeploymentProfile(
                                    productId = licenseMaterial.productId,
                                    endpoint = requiredArgument(
                                        arguments.getString(ARG_ENDPOINT)
                                    ),
                                    connectTimeoutMillis = 10_000,
                                    readTimeoutMillis = 60_000,
                                    tlsCertificates = listOf(caBytes),
                                    supportedLicenseSchemaVersion = 1,
                                    licenseTrustKeys = listOf(
                                        ProductionAndroidOfflineDeploymentLicenseTrustKey(
                                            keyId = licenseMaterial.keyId.value,
                                            material = licenseMaterial.publicKey
                                        )
                                    ),
                                    offlineResumePolicyId = "liliya-android-offline-resume-v1",
                                    offlineResumePolicyVersion = 1,
                                    modelSignerTrustKeys = listOf(
                                        ProductionAndroidOfflineDeploymentModelSignerTrustKey(
                                            signerId =
                                                "physical-production-hostbootstrap-model-signer",
                                            material = Base64.getDecoder().decode(
                                                OFFLINE_RESUME_MODEL_SIGNER_PUBLIC_KEY_X509_BASE64
                                            )
                                        )
                                    ),
                                    semanticDirectoryName =
                                        AndroidOfflineSemanticArtifactProvisioner.DEFAULT_DIRECTORY,
                                    cognitiveStorageDirectoryName =
                                        cognitiveStorageDirectoryName
                                )
                            )
                    )
                } finally {
                    caBytes.fill(0)
                }
            }

            val installed = if (preserveOfflineResumeBaseline) {
                ProductionAndroidFirstRunProductInstall.prepareAndInstallDurably(input)
            } else {
                application.configureFirstRun(input)
            }
            assertIs<ProductionAndroidFirstRunProductInstallResult.Installed>(installed)

            val startupOutcome = application.startApplicationRuntime()
            if (startupOutcome is ProductionAndroidAppStartupOutcome.ProvisioningRejected) {
                error("Cold-start provisioning rejected: " + startupOutcome.result)
            }
            val startup = assertIs<ProductionAndroidAppStartupOutcome.Runtime>(startupOutcome)
            assertEquals(ProductionAndroidAppRuntimeState.READY, startup.state)

            val uiTranscriptChars = exerciseRealChatSurface(instrumentation)

            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString("hostBootstrap.realSignedLicense", "true")
                putString("hostBootstrap.deviceBound", "true")
                putString("hostBootstrap.authorityAdmission", "true")
                putString("hostBootstrap.ready", "true")
                putString("hostBootstrap.runtimeState", "READY")
                putString("hostBootstrap.qwenBytes", QWEN_BYTES.toString())
                putString("hostBootstrap.qwenSha256", QWEN_SHA256)
                putString("hostBootstrap.qwenRevision", QWEN_REVISION)
                putString("hostBootstrap.chatCompleted", "true")
                putString("hostBootstrap.chatPath", "UI")
                putString("hostBootstrap.uiSurfaceReady", "true")
                putString("hostBootstrap.uiChatCompleted", "true")
                putString("hostBootstrap.uiTranscriptChars", uiTranscriptChars.toString())
                putString("hostBootstrap.implicitAuthorityMinted", "false")
            })
            println("LILIYA_PHYSICAL_PRODUCTION_HOSTBOOTSTRAP_FIRST_CHAT=PASS")
            fullAcceptancePassed = true
        } finally {
            val exactDekAssembly = modelDekAssembly
            val exactProtector = modelDekProtectorDescriptor
            if (
                !preserveOfflineResumeBaseline &&
                exactDekAssembly != null &&
                exactProtector != null
            ) {
                runCatching { exactDekAssembly.keyProtector.retire(exactProtector) }
            }
            modelDekBytes.fill(0)
            application.runtimeOwner.close()

            val cognitiveCleanup = if (preserveOfflineResumeBaseline) {
                "PRESERVED"
            } else {
                runCatching {
                    PhysicalAcceptanceCognitiveProtectorCleanup.retireExactIfPresent(
                        targetContext,
                        cognitiveProtectorId,
                        1L
                    )
                }.getOrElse { "CLEANUP_EXCEPTION" }
            }
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString("hostBootstrap.cognitiveProtectorCleanup", cognitiveCleanup)
                putString(
                    "hostBootstrap.offlineResumeBaselinePreserved",
                    preserveOfflineResumeBaseline.toString()
                )
            })
            require(
                cognitiveCleanup == "ABSENT" ||
                    cognitiveCleanup == "RETIRED" ||
                    cognitiveCleanup == "PRESERVED"
            ) {
                "physical production cognitive protector final cleanup rejected"
            }
            if (fullAcceptancePassed) {
                require(modelSource.delete() || !modelSource.exists()) {
                    "physical production raw Qwen cleanup rejected"
                }
            }

            fixtureRoot.deleteRecursively()
            stagingRoot.deleteRecursively()
            if (!preserveOfflineResumeBaseline) {
                semanticRoot.deleteRecursively()
                modelDekRoot.deleteRecursively()
                cognitiveStorageRoot.deleteRecursively()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun exerciseRealChatSurface(
        instrumentation: android.app.Instrumentation
    ): Int {
        val monitor = instrumentation.addMonitor(
            LiliyaActivity::class.java.name,
            null,
            false
        )
        val launch = instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${instrumentation.targetContext.packageName}/.LiliyaProvisioningActivity"
        )
        launch.close()
        val activity = monitor.waitForActivityWithTimeout(30_000L) as? LiliyaActivity
            ?: error("production LiliyaActivity did not launch")
        var recreatedActivity: LiliyaActivity? = null

        try {
            assertTrue(
                waitForUi(instrumentation, 30_000L) {
                    containsExactText(activity.window.decorView, "Готова")
                },
                "production chat surface did not reach visible READY"
            )

            var userOnlyTranscriptChars = 0
            instrumentation.runOnMainSync {
                val root = activity.window.decorView
                val input = findEditTextByHint(root, "Сообщение")
                    ?: error("production chat input missing")
                val send = findButtonByText(root, "Отправить")
                    ?: error("production chat send button missing")
                assertTrue(input.isEnabled, "chat input must be enabled only at READY")
                assertTrue(send.isEnabled, "chat send must be enabled only at READY")
                input.setText(UI_CHAT_MESSAGE)
                assertTrue(send.performClick(), "production chat send click rejected")
                val transcript = findTextContaining(root, UI_CHAT_MESSAGE)
                    ?: error("user turn not visible after production send")
                userOnlyTranscriptChars = transcript.length
            }

            assertTrue(userOnlyTranscriptChars > UI_CHAT_MESSAGE.length)
            val uiCompleted = waitForUi(instrumentation, 240_000L) {
                val root = activity.window.decorView
                val transcript = findTextContaining(root, UI_CHAT_MESSAGE)
                containsExactText(root, "Готова") &&
                    transcript != null &&
                    transcript.length > userOnlyTranscriptChars
            }
            if (!uiCompleted) {
                val chatState = when (
                    (activity.application as LiliyaApplication)
                        .observeApplicationChat { }
                ) {
                    ProductionAndroidAppChatTaskSnapshot.Idle -> "IDLE"
                    is ProductionAndroidAppChatTaskSnapshot.InFlight -> "IN_FLIGHT"
                    is ProductionAndroidAppChatTaskSnapshot.Completed -> "COMPLETED"
                }
                instrumentation.sendStatus(2, android.os.Bundle().apply {
                    putString("hostBootstrap.uiChatStateAtTimeout", chatState)
                })
            }
            assertTrue(
                uiCompleted,
                "production chat UI did not render completed response"
            )

            var finalTranscriptChars = 0
            instrumentation.runOnMainSync {
                val transcript = findTextContaining(
                    activity.window.decorView,
                    UI_CHAT_MESSAGE
                ) ?: error("completed production transcript missing")
                finalTranscriptChars = transcript.length
            }
            assertTrue(finalTranscriptChars > userOnlyTranscriptChars)

            val recreationMonitor = instrumentation.addMonitor(
                LiliyaActivity::class.java.name,
                null,
                false
            )
            instrumentation.runOnMainSync { activity.recreate() }
            val recreated = recreationMonitor.waitForActivityWithTimeout(30_000L)
                as? LiliyaActivity
                ?: error("production READY LiliyaActivity did not recreate")
            recreatedActivity = recreated

            assertTrue(
                waitForUi(instrumentation, 30_000L) {
                    val root = recreated.window.decorView
                    val transcript = findTextContaining(root, UI_CHAT_MESSAGE)
                    val input = findEditTextByHint(root, "Сообщение")
                    val send = findButtonByText(root, "Отправить")
                    containsExactText(root, "Готова") &&
                        transcript != null &&
                        transcript.length >= finalTranscriptChars &&
                        input?.isEnabled == true &&
                        send?.isEnabled == true
                },
                "recreated production chat surface did not restore READY state"
            )
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString("hostBootstrap.uiReadyRecreated", "true")
            })
            return finalTranscriptChars
        } finally {
            instrumentation.runOnMainSync {
                recreatedActivity?.let {
                    if (!it.isFinishing && !it.isDestroyed) it.finish()
                }
                if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun waitForUi(
        instrumentation: android.app.Instrumentation,
        timeoutMillis: Long,
        predicate: () -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            var matched = false
            instrumentation.runOnMainSync { matched = predicate() }
            if (matched) return true
            Thread.sleep(100L)
        }
        return false
    }

    private fun containsExactText(view: View, expected: String): Boolean {
        if (view is TextView && view.text?.toString() == expected) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (containsExactText(view.getChildAt(index), expected)) return true
            }
        }
        return false
    }

    private fun findEditTextByHint(view: View, hint: String): EditText? {
        if (view is EditText && view.hint?.toString() == hint) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findEditTextByHint(view.getChildAt(index), hint)?.let { return it }
            }
        }
        return null
    }

    private fun findButtonByText(view: View, text: String): Button? {
        if (view is Button && view.text?.toString() == text) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findButtonByText(view.getChildAt(index), text)?.let { return it }
            }
        }
        return null
    }

    private fun findTextContaining(view: View, text: String): String? {
        if (view is TextView) {
            val value = view.text?.toString().orEmpty()
            if (value.contains(text)) return value
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findTextContaining(view.getChildAt(index), text)?.let { return it }
            }
        }
        return null
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
                "physical-production-hostbootstrap-foundation-${correlations.incrementAndGet()}"
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
                maxTotalPlaintextBytes = QWEN_BYTES,
                maxSegmentPlaintextBytes = SEGMENT_BYTES.toLong(),
                maxSegmentCount = 512,
                maxActiveAttempts = 1,
                maxOpaqueIdentifierChars = 128
            ),
            llamaPolicy = LlamaCppEnginePolicy(
                contextTokens = 2_048,
                maxPromptTokens = 1_024,
                maxGeneratedTokens = 768,
                batchTokens = 256,
                microBatchTokens = 64,
                threadCount = 4,
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
                    "physical-production-hostbootstrap-session-${sessions.incrementAndGet()}"
                )
            },
            limits = firstWorkingCognitiveLimits()
        )
    }

    private fun personaDefinition() = AndroidHeartProductionPersonaDefinition(
        selfIdentityId = SelfIdentityId("liliya"),
        selfName = SelfName("Liliya"),
        selfSourceId = SelfSourceId("physical-production-hostbootstrap-fixture"),
        selfSourceReference = SelfSourceReference("physical-production-hostbootstrap-v1"),
        selfCreatedAt = FIXTURE_TIME,
        personalityProfileId = PersonalityProfileId("liliya-first-working-persona"),
        personalityAttributes = listOf(
            PersonalityAttribute(
                PersonalityAttributeKey("tone"),
                PersonalityAttributeValue("helpful concise assistant")
            )
        ),
        personalitySourceId = PersonalitySourceId("physical-production-hostbootstrap-fixture"),
        personalitySourceReference = PersonalitySourceReference("physical-production-hostbootstrap-v1"),
        personalityCreatedAt = FIXTURE_TIME
    )

    private data class LicenseMaterial(
        val keyId: LicenseKeyId,
        val publicKey: ByteArray,
        val envelope: LicenseSignedEnvelope,
        val productId: String,
        val feature: String,
        val subject: String,
        val revocationEpoch: Long,
        val replaySequence: Long?,
        val deviceBinding: LicenseDeviceBindingReference
    )

    private fun licenseMaterial(
        context: android.content.Context,
        arguments: android.os.Bundle
    ): LicenseMaterial {
        val endpoint = URL(requiredArgument(arguments.getString(ARG_ENDPOINT)))
        assertEquals("https", endpoint.protocol)

        val caBytes = Base64.getDecoder().decode(
            requiredArgument(arguments.getString(ARG_CA_BASE64))
        )
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
        val publicKey = Base64.getDecoder().decode(
            requiredArgument(arguments.getString(ARG_LICENSE_KEY_BASE64))
        )
        val trusted = LicenseTrustedVerificationKey.of(
            keyId = activated.license.signingKeyId,
            algorithm = LicenseAlgorithm(ALGORITHM),
            material = publicKey
        )
        val verified = assertIs<LicenseVerificationResult.Verified>(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(LicenseAlgorithm(ALGORITHM)),
                trustedKeys = LicenseTrustedKeyResolver { requested ->
                    trusted.takeIf { it.keyId == requested }
                },
                signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
            ).verify(activated.license)
        )

        val entitlement = verified.entitlement
        assertEquals(
            LicenseProductId(requiredArgument(arguments.getString(ARG_PRODUCT))),
            entitlement.productId
        )
        assertEquals(activated.subject, entitlement.subject.value)
        val exactBinding = assertNotNull(entitlement.deviceBindingReference)
        assertEquals(expectedBinding, exactBinding)
        assertNull(entitlement.expiresAt)
        assertNull(entitlement.offlineLeaseUntil)
        val feature = entitlement.features.first { it.value == GRANTED_CAPABILITY }

        return LicenseMaterial(
            keyId = activated.license.signingKeyId,
            publicKey = publicKey,
            envelope = activated.license,
            productId = entitlement.productId.value,
            feature = feature.value,
            subject = entitlement.subject.value,
            revocationEpoch = entitlement.revocationEpoch.value,
            replaySequence = entitlement.replaySequence?.value,
            deviceBinding = exactBinding
        )
    }

    private fun offlineResumeModelSignerKeyPair(
        provider: BouncyCastleProvider
    ): KeyPair {
        val keyFactory = KeyFactory.getInstance("Ed25519", provider)
        val privateKeyBytes = Base64.getDecoder().decode(
            OFFLINE_RESUME_MODEL_SIGNER_PRIVATE_KEY_PKCS8_BASE64
        )
        val publicKeyBytes = Base64.getDecoder().decode(
            OFFLINE_RESUME_MODEL_SIGNER_PUBLIC_KEY_X509_BASE64
        )
        return try {
            KeyPair(
                keyFactory.generatePublic(
                    X509EncodedKeySpec(publicKeyBytes)
                ),
                keyFactory.generatePrivate(
                    PKCS8EncodedKeySpec(privateKeyBytes)
                )
            )
        } finally {
            privateKeyBytes.fill(0)
            publicKeyBytes.fill(0)
        }
    }

    private fun requiredArgument(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: error("missing physical production HostBootstrap argument")

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

    private fun resourceBudgets() = LargeProtectedModelResourceBudgets(
        maxTotalPlaintextBytes = QWEN_BYTES,
        maxTotalCiphertextBodyBytes = QWEN_BYTES + 16L * 1024L * 1024L,
        maxTotalProtectedPayloadBytes = QWEN_BYTES + 32L * 1024L * 1024L,
        maxSegmentCount = 512,
        minNonFinalSegmentPlaintextBytes = 64L * 1024L,
        maxSegmentPlaintextBytes = SEGMENT_BYTES.toLong(),
        maxSegmentCiphertextBodyBytes = SEGMENT_BYTES.toLong() + 64L,
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
        const val ARG_ENDPOINT = "activationEndpoint"
        const val ARG_CA_BASE64 = "activationCaBase64"
        const val ARG_LICENSE_KEY_BASE64 = "activationLicenseKeyDerBase64"
        const val ARG_PRODUCT = "activationProductId"
        const val ARG_PRESERVE_OFFLINE_RESUME_BASELINE =
            "preserveOfflineResumeBaseline"
        const val ACTIVATION_CODE_FILE = "physical-activation-code.once"

        const val OFFLINE_RESUME_MODEL_DEK_PROTECTOR_ID =
            "physical-offline-resume-model-dek-protector"
        const val OFFLINE_RESUME_COGNITIVE_DIRECTORY =
            "physical-offline-resume-cognitive"
        const val OFFLINE_RESUME_COGNITIVE_DEK_ID =
            "physical-offline-resume-cognitive-dek"
        const val OFFLINE_RESUME_COGNITIVE_PROTECTOR_ID =
            "physical-offline-resume-cognitive-protector"
        const val OFFLINE_RESUME_MODEL_SIGNER_PRIVATE_KEY_PKCS8_BASE64 =
            "MC4CAQAwBQYDK2VwBCIEINqvJt3k48ugJmxUatdOfRc7Pz0McVLztyrqNH1BypHG"
        const val OFFLINE_RESUME_MODEL_SIGNER_PUBLIC_KEY_X509_BASE64 =
            "MCowBQYDK2VwAyEAe1hVxYk+lojmHmH/9Ix8A76lVPquVk7nOj4h77dpZIk="
        const val ALGORITHM = "ECDSA-P256-SHA256"
        const val GRANTED_CAPABILITY = "model.local"

        const val QWEN_FILE_NAME = "Qwen3-1.7B-Q4_K_M.gguf"
        const val QWEN_BYTES = 1_282_439_264L
        const val QWEN_SHA256 =
            "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"
        const val QWEN_REVISION = "daeb8e2d528a760970442092f6bf1e55c3b659eb"
        const val QWEN_CONTAINER_BYTES = QWEN_BYTES + 128L * 1024L * 1024L

        const val SEMANTIC_ENCODER_ASSET = "multilingual-e5-small-liliya-v0.1.onnx"
        const val SEMANTIC_TOKENIZER_ASSET = "multilingual-e5-small-tokenizer-v0.1.onnx"
        const val SEGMENT_BYTES = 4 * 1024 * 1024
        const val MAX_PROMPT_CHARS = 8_192
        const val MAX_OUTPUT_CHARS = 2_048
        const val UI_CHAT_MESSAGE = "Hello from physical Liliya UI /no_think"
        val GENERATION_REJECTION_REASON = Regex("(?:^|,)rejectionReason=([^,\\t]+)")
        val FIXTURE_TIME: Instant = Instant.parse("2026-10-03T00:00:00Z")
    }
}
