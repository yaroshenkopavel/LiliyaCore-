package pro.liliya.app

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Base64
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.llamacppengine.AndroidLlamaCppCognitiveModelAssembly
import pro.liliya.android.llamacppengine.LlamaCppEnginePolicy
import pro.liliya.android.protectedmodel.staging.AndroidProtectedModelStagingPolicy
import pro.liliya.android.runtime.AndroidHeartProductionPersonaDefinition
import pro.liliya.android.runtime.AndroidProductRuntimeCognitiveStructuralOwnersFactory
import pro.liliya.android.runtime.AndroidProductRuntimeCognitiveStructuralOwnersResult
import pro.liliya.android.runtime.AndroidProductRuntimeFirstWorkingLearningDisabled
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelBudgetInput
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelSignerTrust
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelSignerTrustKey
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelSignerTrustResult
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelStagingProvisioningFactory
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityPlan
import pro.liliya.android.runtime.AndroidProductRuntimeStartupPreparedInputOwnerTemplate
import pro.liliya.android.runtime.ProductChatResult
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.cognitive.CognitiveModelRuntimeSessionIdSource
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.CognitiveRuntimeScopeId
import pro.liliya.core.cognitive.DeterministicCognitiveModelRequestCompiler
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
import pro.liliya.core.protectedmodel.ProtectedModelSignerId
import pro.liliya.core.protectedmodel.ProtectedModelSignerResolver
import pro.liliya.core.runtime.hardening.RuntimeModelSessionId

@RunWith(AndroidJUnit4::class)
class PhysicalProductionOfflineResumeReadyInstrumentedTest {
    @Test
    fun cold_process_reconstructs_ready_runtime_and_completes_new_chat() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val application = context as LiliyaApplication

        assertEquals(
            ProductionAndroidAppRuntimeState.CONFIGURATION_REQUIRED,
            application.runtimeOwner.state()
        )
        assertIs<ProductionAndroidAppChatTaskSnapshot.Idle>(
            application.observeApplicationChat { }
        )
        assertNotNull(ProductionAndroidLocalModelSelection.current())

        val foundation = foundation()
        val model = ProtectedModelReference(
            ProtectedModelPackageId(MODEL_PACKAGE_ID),
            ProtectedModelGeneration(1)
        )
        val signerPublicKey = Base64.getDecoder().decode(
            MODEL_SIGNER_PUBLIC_KEY_X509_BASE64
        )
        val signerResolver = try {
            val signerTrust = AndroidProductRuntimeProtectedModelSignerTrust.create(
                listOf(
                    AndroidProductRuntimeProtectedModelSignerTrustKey(
                        signerId = MODEL_SIGNER_ID,
                        material = signerPublicKey
                    )
                )
            )
            assertIs<AndroidProductRuntimeProtectedModelSignerTrustResult.Ready>(
                signerTrust
            ).resolver
        } finally {
            signerPublicKey.fill(0)
        }

        val modelDekAssembly = assertIs<
            pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelDekOpenResult.Ready
        >(
            pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelDekAssembly.open(
                context = context,
                foundation = foundation,
                directoryName = MODEL_DEK_DIRECTORY
            )
        ).assembly

        val protectedOwnership = ProtectedModelRuntimeOwnership().also {
            it.replaceTarget(model)
        }
        val llamaAssembly = llamaAssembly(
            context = context,
            foundation = foundation,
            protectedOwnership = protectedOwnership
        )
        val staging = AndroidProductRuntimeProtectedModelStagingProvisioningFactory.create(
            llamaAssembly = llamaAssembly,
            signerResolver = signerResolver,
            packageBudgets = packageBudgets(),
            dekResolver = modelDekAssembly.store
        )

        val limits = firstWorkingCognitiveLimits()
        val structural = assertIs<AndroidProductRuntimeCognitiveStructuralOwnersResult.Ready>(
            AndroidProductRuntimeCognitiveStructuralOwnersFactory.create(limits)
        ).owners
        val learningDisabled = AndroidProductRuntimeFirstWorkingLearningDisabled.create()
        val policies = LearningPolicyComposition(foundation)
        val installedPolicy = assertIs<LearningPolicyInstallResult.Installed>(
            policies.install(
                LearningPolicy(
                    id = LearningPolicyId(LEARNING_POLICY_ID),
                    rule = LEARNING_POLICY_RULE,
                    createdAt = FIXTURE_TIME
                )
            )
        ).ownership

        val principal = AuthorityPrincipal(PRINCIPAL)
        val capability = CapabilityId(CAPABILITY)
        val policy = ProductionAndroidOfflineResumeProcessPolicy(
            admission = ProductionAndroidOfflineResumeAdmissionPolicy(
                feature = FEATURE,
                principal = principal.value,
                capability = capability.value,
                authorityScope = AuthorityScope.GLOBAL.value
            ),
            authorityPlan = AndroidProductRuntimeStartupAuthorityPlan(
                capabilities = listOf(
                    CapabilityDescriptor(
                        id = capability,
                        providerId = CapabilityProviderId(PROVIDER_ID)
                    )
                ),
                directGrants = listOf(
                    DirectAuthorityGrant(
                        principal = principal,
                        capability = capability,
                        scope = AuthorityScope.GLOBAL
                    )
                )
            ),
            protectedModelBudgets = protectedModelBudgetInput(),
            staging = staging,
            preparedInputOwners = AndroidProductRuntimeStartupPreparedInputOwnerTemplate(
                memoryStoreId = PersistentStoreId("offline-resume-placeholder-memory"),
                knowledgeStoreId = PersistentStoreId("offline-resume-placeholder-knowledge"),
                llamaAssembly = llamaAssembly,
                maxCandidatesPerSource = limits.maxRetrievalResults,
                personaDefinition = personaDefinition(),
                scope = CognitiveRuntimeScopeId(RUNTIME_SCOPE),
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
                learningMutationStoreId =
                    PersistentStoreId("offline-resume-placeholder-learning"),
                artifactIds = structural.artifactIds,
                timestamps = structural.timestamps,
                limits = limits
            )
        )

        try {
            assertTrue(application.configureOfflineResumePolicy(policy))
            val resumed = assertIs<ProductionAndroidOfflineResumeBootstrapResult.Ready>(
                application.attemptOfflineResume()
            )
            assertIs<ProductionAndroidFirstRunProductInstallResult.Installed>(
                resumed.install
            )

            val startupOutcome = application.startApplicationRuntime()
            if (startupOutcome is ProductionAndroidAppStartupOutcome.ProvisioningRejected) {
                error("offline resume provisioning rejected: " + startupOutcome.result)
            }
            val startup = assertIs<ProductionAndroidAppStartupOutcome.Runtime>(startupOutcome)
            assertEquals(ProductionAndroidAppRuntimeState.READY, startup.state)

            val latch = CountDownLatch(1)
            var terminal: ProductionAndroidAppChatTaskSnapshot.Completed? = null
            assertIs<ProductionAndroidAppChatTaskRequestResult.Started>(
                application.requestApplicationChat(NEW_CHAT_MESSAGE) {
                    terminal = it
                    latch.countDown()
                }
            )
            assertTrue(
                latch.await(CHAT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "offline resume chat did not complete"
            )

            val completed = assertNotNull(terminal)
            val outcome = assertIs<ProductionAndroidAppChatTaskOutcome.Result>(
                completed.outcome
            )
            val chat = assertIs<ProductChatResult.Completed>(outcome.value)
            assertTrue(chat.reply.isNotBlank())
            assertTrue(application.consumeApplicationChat(completed.requestId))

            instrumentation.sendStatus(2, Bundle().apply {
                putString("offlineResumeReady.materialLoaded", "true")
                putString("offlineResumeReady.licenseReverified", "true")
                putString("offlineResumeReady.freshAdmissionBuilt", "true")
                putString("offlineResumeReady.freshAuthorityCreated", "true")
                putString("offlineResumeReady.startupInstalled", "true")
                putString("offlineResumeReady.runtimeState", "READY")
                putString("offlineResumeReady.executionStarted", "true")
                putString("offlineResumeReady.chatReplay", "absent")
                putString("offlineResumeReady.newChatCompleted", "true")
                putString("offlineResumeReady.replyChars", chat.reply.length.toString())
                putString("offlineResumeReady.activationNetworkSupplied", "false")
            })
        } finally {
            application.runtimeOwner.close()
        }
    }

    internal fun createOfflineResumeProcessPolicy(
        context: android.content.Context
    ): ProductionAndroidOfflineResumeProcessPolicy {
        val foundation = foundation()
        val model = ProtectedModelReference(
            ProtectedModelPackageId(MODEL_PACKAGE_ID),
            ProtectedModelGeneration(1)
        )
        val signerPublicKey = Base64.getDecoder().decode(
            MODEL_SIGNER_PUBLIC_KEY_X509_BASE64
        )
        val signerResolver = try {
            val signerTrust = AndroidProductRuntimeProtectedModelSignerTrust.create(
                listOf(
                    AndroidProductRuntimeProtectedModelSignerTrustKey(
                        signerId = MODEL_SIGNER_ID,
                        material = signerPublicKey
                    )
                )
            )
            assertIs<AndroidProductRuntimeProtectedModelSignerTrustResult.Ready>(
                signerTrust
            ).resolver
        } finally {
            signerPublicKey.fill(0)
        }

        val modelDekAssembly = assertIs<
            pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelDekOpenResult.Ready
        >(
            pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelDekAssembly.open(
                context = context,
                foundation = foundation,
                directoryName = MODEL_DEK_DIRECTORY
            )
        ).assembly

        val protectedOwnership = ProtectedModelRuntimeOwnership().also {
            it.replaceTarget(model)
        }
        val llamaAssembly = llamaAssembly(
            context = context,
            foundation = foundation,
            protectedOwnership = protectedOwnership
        )
        val staging = AndroidProductRuntimeProtectedModelStagingProvisioningFactory.create(
            llamaAssembly = llamaAssembly,
            signerResolver = signerResolver,
            packageBudgets = packageBudgets(),
            dekResolver = modelDekAssembly.store
        )

        val limits = firstWorkingCognitiveLimits()
        val structural = assertIs<AndroidProductRuntimeCognitiveStructuralOwnersResult.Ready>(
            AndroidProductRuntimeCognitiveStructuralOwnersFactory.create(limits)
        ).owners
        val learningDisabled = AndroidProductRuntimeFirstWorkingLearningDisabled.create()
        val policies = LearningPolicyComposition(foundation)
        val installedPolicy = assertIs<LearningPolicyInstallResult.Installed>(
            policies.install(
                LearningPolicy(
                    id = LearningPolicyId(LEARNING_POLICY_ID),
                    rule = LEARNING_POLICY_RULE,
                    createdAt = FIXTURE_TIME
                )
            )
        ).ownership

        val principal = AuthorityPrincipal(PRINCIPAL)
        val capability = CapabilityId(CAPABILITY)
        return ProductionAndroidOfflineResumeProcessPolicy(
            admission = ProductionAndroidOfflineResumeAdmissionPolicy(
                feature = FEATURE,
                principal = principal.value,
                capability = capability.value,
                authorityScope = AuthorityScope.GLOBAL.value
            ),
            authorityPlan = AndroidProductRuntimeStartupAuthorityPlan(
                capabilities = listOf(
                    CapabilityDescriptor(
                        id = capability,
                        providerId = CapabilityProviderId(PROVIDER_ID)
                    )
                ),
                directGrants = listOf(
                    DirectAuthorityGrant(
                        principal = principal,
                        capability = capability,
                        scope = AuthorityScope.GLOBAL
                    )
                )
            ),
            protectedModelBudgets = protectedModelBudgetInput(),
            staging = staging,
            preparedInputOwners = AndroidProductRuntimeStartupPreparedInputOwnerTemplate(
                memoryStoreId = PersistentStoreId("offline-resume-placeholder-memory"),
                knowledgeStoreId = PersistentStoreId("offline-resume-placeholder-knowledge"),
                llamaAssembly = llamaAssembly,
                maxCandidatesPerSource = limits.maxRetrievalResults,
                personaDefinition = personaDefinition(),
                scope = CognitiveRuntimeScopeId(RUNTIME_SCOPE),
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
                learningMutationStoreId =
                    PersistentStoreId("offline-resume-placeholder-learning"),
                artifactIds = structural.artifactIds,
                timestamps = structural.timestamps,
                limits = limits
            )
        )
    }

    private fun foundation(): FoundationComposition {
        val logs = InMemoryLogWriter()
        val correlations = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator {
                "physical-offline-resume-foundation-${correlations.incrementAndGet()}"
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
                useMmap = true
            ),
            foundation = foundation,
            protectedAccess = protectedAccess,
            legacyEngineLoader = legacyLoader,
            compiler = DeterministicCognitiveModelRequestCompiler(),
            sessionIds = CognitiveModelRuntimeSessionIdSource {
                RuntimeModelSessionId(
                    "physical-offline-resume-session-${sessions.incrementAndGet()}"
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
        personalitySourceReference =
            PersonalitySourceReference("physical-production-hostbootstrap-v1"),
        personalityCreatedAt = FIXTURE_TIME
    )

    private fun protectedModelBudgetInput(): AndroidProductRuntimeProtectedModelBudgetInput {
        val resources = resourceBudgets()
        val packages = packageBudgets()
        return AndroidProductRuntimeProtectedModelBudgetInput(
            maxTotalPlaintextBytes = resources.maxTotalPlaintextBytes,
            maxTotalCiphertextBodyBytes = resources.maxTotalCiphertextBodyBytes,
            maxTotalProtectedPayloadBytes = resources.maxTotalProtectedPayloadBytes,
            maxSegmentCount = resources.maxSegmentCount,
            minNonFinalSegmentPlaintextBytes = resources.minNonFinalSegmentPlaintextBytes,
            maxSegmentPlaintextBytes = resources.maxSegmentPlaintextBytes,
            maxSegmentCiphertextBodyBytes = resources.maxSegmentCiphertextBodyBytes,
            maxStructuralIdentifierChars = resources.maxStructuralIdentifierChars,
            maxCanonicalManifestBytes = resources.maxCanonicalManifestBytes,
            maxModelProfileIdChars = packages.maxModelProfileIdChars,
            maxSignerIdChars = packages.maxSignerIdChars,
            maxCanonicalSignedManifestBytes = packages.maxCanonicalSignedManifestBytes,
            maxContainerBytes = QWEN_CONTAINER_BYTES,
            maxSignatureBytes = 128
        )
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

    private fun packageBudgets() = LargeProtectedModelPackageBudgets(
        maxModelProfileIdChars = 128,
        maxSignerIdChars = 128,
        maxCanonicalSignedManifestBytes = 2L * 1024L * 1024L
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

    private companion object {
        const val FEATURE = "model.local"
        const val PRINCIPAL = "liliya-product-runtime"
        const val CAPABILITY = "model.local"
        const val PROVIDER_ID = "physical-production-hostbootstrap-fixture"
        const val MODEL_PACKAGE_ID =
            "physical-production-hostbootstrap-qwen3-1.7b-q4km"
        const val MODEL_SIGNER_ID =
            "physical-production-hostbootstrap-model-signer"
        const val MODEL_DEK_DIRECTORY =
            "physical-production-hostbootstrap-model-dek"
        const val RUNTIME_SCOPE =
            "physical-production-hostbootstrap-runtime"
        const val LEARNING_POLICY_ID =
            "physical-production-hostbootstrap-learning-disabled"
        const val LEARNING_POLICY_RULE =
            "learning disabled until a separately accepted product policy gate"
        const val MODEL_SIGNER_PUBLIC_KEY_X509_BASE64 =
            "MCowBQYDK2VwAyEAe1hVxYk+lojmHmH/9Ix8A76lVPquVk7nOj4h77dpZIk="

        const val QWEN_BYTES = 1_282_439_264L
        const val QWEN_CONTAINER_BYTES = QWEN_BYTES + 128L * 1024L * 1024L
        const val SEGMENT_BYTES = 4 * 1024 * 1024
        const val MAX_PROMPT_CHARS = 8_192
        const val MAX_OUTPUT_CHARS = 2_048
        const val CHAT_TIMEOUT_SECONDS = 240L
        const val NEW_CHAT_MESSAGE = "Hello after offline resume /no_think"
        val FIXTURE_TIME: Instant = Instant.parse("2026-10-03T00:00:00Z")
    }
}
