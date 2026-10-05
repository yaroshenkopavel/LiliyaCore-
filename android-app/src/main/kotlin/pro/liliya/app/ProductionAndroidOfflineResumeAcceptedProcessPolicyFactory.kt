package pro.liliya.app

import android.content.Context
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.android.llamacppengine.AndroidLlamaCppCognitiveModelAssembly
import pro.liliya.android.llamacppengine.LlamaCppEnginePolicy
import pro.liliya.android.protectedmodel.staging.AndroidProtectedModelStagingPolicy
import pro.liliya.android.runtime.AndroidHeartProductionPersonaDefinition
import pro.liliya.android.runtime.AndroidProductRuntimeCognitiveStructuralOwnersFactory
import pro.liliya.android.runtime.AndroidProductRuntimeCognitiveStructuralOwnersResult
import pro.liliya.android.runtime.AndroidProductRuntimeFirstWorkingLearningDisabled
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunProductInput
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelBudgetInput
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelSignerTrust
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelSignerTrustKey
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelSignerTrustResult
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelStagingProvisioningFactory
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityPlan
import pro.liliya.android.runtime.AndroidProductRuntimeStartupPreparedInputOwnerTemplate
import pro.liliya.android.runtime.AndroidProductRuntimeStartupTrustOwnership
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
import pro.liliya.core.modelengine.ModelEngineLoadFailure
import pro.liliya.core.modelengine.ModelEngineLoadResult
import pro.liliya.core.modelengine.ModelEngineLoaderPort
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
import pro.liliya.core.protectedmodel.ProtectedModelSignerResolver
import pro.liliya.core.runtime.hardening.RuntimeModelSessionId

/**
 * Production-owned cold-process policy reconstruction for the accepted first-working Android
 * product identity.
 *
 * Reconstruction runs only after the durable signed License has been re-verified into a fresh
 * [AndroidProductRuntimeStartupTrustOwnership].
 *
 * Policy Reconstruction != License restoration.
 * Policy Reconstruction != Authority restoration.
 * Policy Reconstruction != Execution restoration.
 * Policy Reconstruction != chat replay.
 *
 * The compatibility identities intentionally match the physically accepted HostBootstrap durable
 * state. Changing them requires an explicit durable-state migration and separate acceptance.
 */
internal object ProductionAndroidOfflineResumeAcceptedProcessPolicyFactory {
    fun create(
        context: Context,
        trust: AndroidProductRuntimeStartupTrustOwnership
    ): ProductionAndroidOfflineResumeProcessPolicy? = try {
        val foundation = trust.foundation
        val model = ProtectedModelReference(
            ProtectedModelPackageId(MODEL_PACKAGE_ID),
            ProtectedModelGeneration(1)
        )

        val signerPublicKey = Base64.getDecoder().decode(
            MODEL_SIGNER_PUBLIC_KEY_X509_BASE64
        )
        val signerResolver = try {
            when (
                val signerTrust = AndroidProductRuntimeProtectedModelSignerTrust.create(
                    listOf(
                        AndroidProductRuntimeProtectedModelSignerTrustKey(
                            signerId = MODEL_SIGNER_ID,
                            material = signerPublicKey
                        )
                    )
                )
            ) {
                is AndroidProductRuntimeProtectedModelSignerTrustResult.Ready ->
                    signerTrust.resolver
                AndroidProductRuntimeProtectedModelSignerTrustResult.Rejected ->
                    return null
            }
        } finally {
            signerPublicKey.fill(0)
        }

        val modelDekAssembly = when (
            val opened =
                pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelDekAssembly.open(
                    context = context.applicationContext,
                    foundation = foundation,
                    directoryName = MODEL_DEK_DIRECTORY
                )
        ) {
            is pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelDekOpenResult.Ready ->
                opened.assembly
            pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelDekOpenResult.Corrupt,
            is pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelDekOpenResult.Incompatible,
            is pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelDekOpenResult.Failed ->
                return null
        }

        val protectedOwnership = ProtectedModelRuntimeOwnership().also {
            it.replaceTarget(model)
        }
        val llamaAssembly = llamaAssembly(
            context = context.applicationContext,
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
        val structural = when (
            val created = AndroidProductRuntimeCognitiveStructuralOwnersFactory.create(limits)
        ) {
            is AndroidProductRuntimeCognitiveStructuralOwnersResult.Ready -> created.owners
            AndroidProductRuntimeCognitiveStructuralOwnersResult.Rejected -> return null
        }
        val learningDisabled = AndroidProductRuntimeFirstWorkingLearningDisabled.create()
        val policies = LearningPolicyComposition(foundation)
        val installedPolicy = when (
            val installed = policies.install(
                LearningPolicy(
                    id = LearningPolicyId(LEARNING_POLICY_ID),
                    rule = LEARNING_POLICY_RULE,
                    createdAt = ACCEPTED_IDENTITY_TIME
                )
            )
        ) {
            is LearningPolicyInstallResult.Installed -> installed.ownership
            is LearningPolicyInstallResult.Rejected -> return null
        }

        val principal = AuthorityPrincipal(PRINCIPAL)
        val capability = CapabilityId(CAPABILITY)

        ProductionAndroidOfflineResumeProcessPolicy(
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
    } catch (_: Throwable) {
        null
    }

    private fun llamaAssembly(
        context: Context,
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
            context = context.applicationContext,
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
                    "production-offline-resume-session-" + sessions.incrementAndGet()
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
        selfCreatedAt = ACCEPTED_IDENTITY_TIME,
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
        personalityCreatedAt = ACCEPTED_IDENTITY_TIME
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

    private const val FEATURE = "model.local"
    private const val PRINCIPAL = "liliya-product-runtime"
    private const val CAPABILITY = "model.local"
    private const val PROVIDER_ID = "physical-production-hostbootstrap-fixture"
    private const val MODEL_PACKAGE_ID =
            "physical-production-hostbootstrap-qwen3-1.7b-q4km"
    private const val MODEL_SIGNER_ID =
            "physical-production-hostbootstrap-model-signer"
    private const val MODEL_DEK_DIRECTORY =
            "physical-production-hostbootstrap-model-dek"
    private const val RUNTIME_SCOPE =
            "physical-production-hostbootstrap-runtime"
    private const val LEARNING_POLICY_ID =
            "physical-production-hostbootstrap-learning-disabled"
    private const val LEARNING_POLICY_RULE =
            "learning disabled until a separately accepted product policy gate"
    private const val MODEL_SIGNER_PUBLIC_KEY_X509_BASE64 =
            "MCowBQYDK2VwAyEAe1hVxYk+lojmHmH/9Ix8A76lVPquVk7nOj4h77dpZIk="

    private const val QWEN_BYTES = 1_282_439_264L
    private const val QWEN_CONTAINER_BYTES = QWEN_BYTES + 128L * 1024L * 1024L
    private const val SEGMENT_BYTES = 4 * 1024 * 1024
    private const val MAX_PROMPT_CHARS = 8_192
    private const val MAX_OUTPUT_CHARS = 2_048
    private val ACCEPTED_IDENTITY_TIME: Instant = Instant.parse("2026-10-03T00:00:00Z")
}

/**
 * Lightweight process-local factory installed by the application on demand.
 *
 * Installation of this factory is inert. Durable material is not loaded and no trust, Authority or
 * Execution state is created until [ProductionAndroidOfflineResumeProductInputFactory.create] is
 * invoked by the normal offline-resume bootstrap.
 */
internal object ProductionAndroidOfflineResumeAcceptedProductInputFactory {
    fun create(
        context: Context
    ): ProductionAndroidOfflineResumeProductInputFactory =
        ProductionAndroidOfflineResumeProductInputFactory { material ->
            createInput(
                context = context.applicationContext,
                material = material
            )
        }

    private fun createInput(
        context: Context,
        material: ProductionAndroidOfflineResumeMaterial
    ): AndroidProductRuntimeFirstRunProductInput? {
        val trust = when (
            val verified = ProductionAndroidOfflineResumeTrustVerifier.verify(
                context = context.applicationContext,
                material = material
            )
        ) {
            is ProductionAndroidOfflineResumeTrustResult.Ready -> verified.ownership
            is ProductionAndroidOfflineResumeTrustResult.Rejected -> return null
        }

        val policy = ProductionAndroidOfflineResumeAcceptedProcessPolicyFactory.create(
            context = context.applicationContext,
            trust = trust
        ) ?: return null

        return when (
            val built = ProductionAndroidOfflineResumeDefaultProductInputFactory.build(
                material = material,
                policy = policy,
                trustPort = ProductionAndroidOfflineResumeTrustPort {
                    ProductionAndroidOfflineResumeTrustResult.Ready(trust)
                },
                admissionPort = ProductionAndroidOfflineResumeAdmissionPort {
                        exactMaterial,
                        exactTrust,
                        admissionPolicy ->
                    ProductionAndroidOfflineResumeAdmissionBuilder.build(
                        context = context.applicationContext,
                        material = exactMaterial,
                        trust = exactTrust,
                        policy = admissionPolicy
                    )
                },
                inputBuildPort = ProductionAndroidOfflineResumeInputBuildPort {
                        exactMaterial,
                        exactPolicy,
                        admission ->
                    ProductionAndroidOfflineResumeDefaultProductInputFactory.buildExactInput(
                        context = context.applicationContext,
                        material = exactMaterial,
                        policy = exactPolicy,
                        admission = admission
                    )
                }
            )
        ) {
            is ProductionAndroidOfflineResumeProductInputResult.Ready -> built.input
            is ProductionAndroidOfflineResumeProductInputResult.Rejected -> null
        }
    }
}
