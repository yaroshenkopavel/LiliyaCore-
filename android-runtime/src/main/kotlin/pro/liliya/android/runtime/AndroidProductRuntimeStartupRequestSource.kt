package pro.liliya.android.runtime

import android.content.Context
import java.io.File
import pro.liliya.android.cognitivestorage.AndroidCognitiveStorageAssembly
import pro.liliya.android.cognitivestorage.AndroidCognitiveStorageFirstRunKeySetup
import pro.liliya.android.cognitivestorage.AndroidCognitiveStorageFirstRunKeySetupRequest
import pro.liliya.android.cognitivestorage.AndroidCognitiveStorageOpenResult
import pro.liliya.android.semanticprovider.AndroidOfflineSemanticArtifactProvisioner
import pro.liliya.core.authority.AuthorityManager
import pro.liliya.core.authority.AuthorityPolicy
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.license.LicenseAuthorityComposition
import pro.liliya.core.license.LicenseAuthorityRequest
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicensePolicyRequest
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelResourceBudgets

/**
 * Static prepared-input owners whose Foundation and cognitive-storage ownership are supplied by
 * the startup request source itself.
 *
 * Template != Authority/License/Provisioning policy.
 */
data class AndroidProductRuntimeStartupPreparedInputOwnerTemplate(
    val memoryStoreId: pro.liliya.core.persistence.PersistentStoreId,
    val knowledgeStoreId: pro.liliya.core.persistence.PersistentStoreId,
    val llamaAssembly: pro.liliya.android.llamacppengine.AndroidLlamaCppCognitiveModelAssembly,
    val maxCandidatesPerSource: Int,
    val personaDefinition: AndroidHeartProductionPersonaDefinition,
    val scope: pro.liliya.core.cognitive.CognitiveRuntimeScopeId,
    val cognitiveMaterialization: pro.liliya.core.cognitive.CognitiveMaterializationPort,
    val outcomeMaterialization: pro.liliya.core.cognitive.CognitiveOutcomeMaterializationPort,
    val policies: pro.liliya.core.learning.LearningPolicyComposition,
    val policyReference: pro.liliya.core.learning.LearningPolicyReference,
    val principal: pro.liliya.core.authority.AuthorityPrincipal,
    val governance: pro.liliya.core.cognitive.CognitiveLearningGovernancePort,
    val learningMaterialization: pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationPort,
    val mutations: pro.liliya.core.learning.EncryptedPersistentLearningApplicationMutationComposition,
    val artifactIds: pro.liliya.core.cognitive.CognitiveArtifactIdSource,
    val timestamps: pro.liliya.core.cognitive.CognitiveTimestampSource,
    val limits: pro.liliya.core.cognitive.CognitiveRuntimeLimits =
        pro.liliya.core.cognitive.CognitiveRuntimeLimits(),
    val personaLimits: AndroidHeartProductionPersonaLimits = AndroidHeartProductionPersonaLimits()
)

data class AndroidProductRuntimeStartupRequestSourceInput(
    val context: Context,
    val foundation: FoundationComposition,
    val capabilityAuthority: CapabilityAuthorityComposition,
    val verifiedLicense: LicenseVerificationResult.Verified,
    val licenseRequest: LicensePolicyRequest,
    val licensePolicyContext: LicensePolicyContext,
    val authorityRequest: LicenseAuthorityRequest,
    val keyRequest: AndroidCognitiveStorageFirstRunKeySetupRequest,
    val localModelFile: File,
    val manifestBudgets: LargeProtectedModelResourceBudgets,
    val packageBudgets: LargeProtectedModelPackageBudgets,
    val containerBudgets: ProductProtectedModelLocalPackageBudgets,
    val stagingProvisioner: ProductGenerationStagingProvisioner,
    val preparedInputOwners: AndroidProductRuntimeStartupPreparedInputOwnerTemplate,
    val cognitiveStorageDirectoryName: String? = null,
    val semanticDirectoryName: String = AndroidOfflineSemanticArtifactProvisioner.DEFAULT_DIRECTORY
)

enum class AndroidProductRuntimeStartupRequestSourceFailure {
    AUTHORITY_PRINCIPAL_MISMATCH,
    COGNITIVE_STORAGE_CORRUPT,
    COGNITIVE_STORAGE_INCOMPATIBLE,
    COGNITIVE_STORAGE_FAILED,
    INTERNAL_FAILURE
}

sealed interface AndroidProductRuntimeStartupRequestSourceResult {
    data class Ready(
        val request: AndroidProductRuntimeStartupCompositionRequest
    ) : AndroidProductRuntimeStartupRequestSourceResult

    data class Rejected(
        val reason: AndroidProductRuntimeStartupRequestSourceFailure
    ) : AndroidProductRuntimeStartupRequestSourceResult
}

internal fun interface AndroidProductRuntimeStartupStorageOpenPort {
    fun open(): AndroidCognitiveStorageOpenResult
}

internal fun interface AndroidProductRuntimeStartupRequestBuildPort {
    fun build(
        storage: AndroidCognitiveStorageAssembly
    ): AndroidProductRuntimeStartupCompositionRequest
}

/**
 * Production source for one explicit startup-composition request.
 *
 * Request Source != License Authority.
 * Request Source != Capability Authority.
 * Request Source != DEK Selection.
 * Request Source != Model Discovery/Download.
 * Request Source != Learning Governance.
 */
object AndroidProductRuntimeStartupRequestSource {
    fun create(
        input: AndroidProductRuntimeStartupRequestSourceInput
    ): AndroidProductRuntimeStartupRequestSourceResult {
        if (input.authorityRequest.principal != input.preparedInputOwners.principal) {
            return rejected(
                AndroidProductRuntimeStartupRequestSourceFailure.AUTHORITY_PRINCIPAL_MISMATCH
            )
        }

        return create(
            storageOpen = AndroidProductRuntimeStartupStorageOpenPort {
                input.cognitiveStorageDirectoryName?.let { directoryName ->
                    AndroidCognitiveStorageAssembly.open(
                        context = input.context.applicationContext,
                        foundation = input.foundation,
                        directoryName = directoryName
                    )
                } ?: AndroidCognitiveStorageAssembly.open(
                    context = input.context.applicationContext,
                    foundation = input.foundation
                )
            },
        requestBuild = AndroidProductRuntimeStartupRequestBuildPort { storage ->
            val authorityManager = AuthorityManager(
                policy = AuthorityPolicy { request ->
                    input.capabilityAuthority.authorize(
                        request = request,
                        context = input.foundation.rootContext(
                            operation = "startup-request-authority",
                            component = "AndroidProductRuntimeStartupRequestSource"
                        )
                    )
                },
                observability = input.foundation.observability
            )
            val licenseAuthority = LicenseAuthorityComposition(
                foundation = input.foundation,
                authorityManager = authorityManager
            )
            val template = input.preparedInputOwners
            val owners = AndroidProductRuntimeStartupPreparedInputOwners(
                foundation = input.foundation,
                cognitiveStorage = storage,
                memoryStoreId = template.memoryStoreId,
                knowledgeStoreId = template.knowledgeStoreId,
                llamaAssembly = template.llamaAssembly,
                maxCandidatesPerSource = template.maxCandidatesPerSource,
                personaDefinition = template.personaDefinition,
                scope = template.scope,
                cognitiveMaterialization = template.cognitiveMaterialization,
                outcomeMaterialization = template.outcomeMaterialization,
                policies = template.policies,
                policyReference = template.policyReference,
                authority = input.capabilityAuthority,
                principal = template.principal,
                governance = template.governance,
                learningMaterialization = template.learningMaterialization,
                mutations = template.mutations,
                artifactIds = template.artifactIds,
                timestamps = template.timestamps,
                limits = template.limits,
                personaLimits = template.personaLimits
            )
            AndroidProductRuntimeStartupCompositionRequest(
                licenseAuthority = licenseAuthority,
                verifiedLicense = input.verifiedLicense,
                licenseRequest = input.licenseRequest,
                licensePolicyContext = input.licensePolicyContext,
                authorityRequest = input.authorityRequest,
                keySetup = AndroidCognitiveStorageFirstRunKeySetup(storage),
                keyRequest = input.keyRequest,
                semanticContext = input.context.applicationContext,
                semanticProvisioner = AndroidOfflineSemanticArtifactProvisioner(),
                semanticDirectoryName = input.semanticDirectoryName,
                localModelFile = input.localModelFile,
                manifestBudgets = input.manifestBudgets,
                packageBudgets = input.packageBudgets,
                containerBudgets = input.containerBudgets,
                stagingProvisioner = input.stagingProvisioner,
                preparedInputOwners = owners
            )
        }
        )
    }

    internal fun create(
        storageOpen: AndroidProductRuntimeStartupStorageOpenPort,
        requestBuild: AndroidProductRuntimeStartupRequestBuildPort
    ): AndroidProductRuntimeStartupRequestSourceResult {
        val storage = when (val opened = try {
            storageOpen.open()
        } catch (_: Exception) {
            return rejected(AndroidProductRuntimeStartupRequestSourceFailure.INTERNAL_FAILURE)
        }) {
            is AndroidCognitiveStorageOpenResult.Ready -> opened.assembly
            AndroidCognitiveStorageOpenResult.Corrupt ->
                return rejected(AndroidProductRuntimeStartupRequestSourceFailure.COGNITIVE_STORAGE_CORRUPT)
            is AndroidCognitiveStorageOpenResult.Incompatible ->
                return rejected(AndroidProductRuntimeStartupRequestSourceFailure.COGNITIVE_STORAGE_INCOMPATIBLE)
            is AndroidCognitiveStorageOpenResult.Failed ->
                return rejected(AndroidProductRuntimeStartupRequestSourceFailure.COGNITIVE_STORAGE_FAILED)
        }

        val request = try {
            requestBuild.build(storage)
        } catch (_: Exception) {
            return rejected(AndroidProductRuntimeStartupRequestSourceFailure.INTERNAL_FAILURE)
        }

        return AndroidProductRuntimeStartupRequestSourceResult.Ready(request)
    }

    private fun rejected(
        reason: AndroidProductRuntimeStartupRequestSourceFailure
    ): AndroidProductRuntimeStartupRequestSourceResult.Rejected =
        AndroidProductRuntimeStartupRequestSourceResult.Rejected(reason)
}
