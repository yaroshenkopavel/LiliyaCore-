package pro.liliya.android.runtime

import pro.liliya.android.cognitivestorage.AndroidCognitiveStorageAssembly
import pro.liliya.android.llamacppengine.AndroidLlamaCppCognitiveModelAssembly
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.cognitive.CognitiveArtifactIdSource
import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationPort
import pro.liliya.core.cognitive.CognitiveLearningGovernancePort
import pro.liliya.core.cognitive.CognitiveMaterializationPort
import pro.liliya.core.cognitive.CognitiveOutcomeMaterializationPort
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.CognitiveRuntimeScopeId
import pro.liliya.core.cognitive.CognitiveTimestampSource
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.learning.LearningPolicyComposition
import pro.liliya.core.learning.LearningPolicyReference
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.protectedmodel.LargeProtectedModelStagedSourceOwnership

/**
 * Static production owners that are independent of the dynamic first-run DEK/semantic/model result.
 *
 * Prepared Input Owners != Provisioning Authority.
 * Prepared Input Owners != License/Capability Authority minting.
 */
data class AndroidProductRuntimeStartupPreparedInputOwners(
    val foundation: FoundationComposition,
    val cognitiveStorage: AndroidCognitiveStorageAssembly,
    val memoryStoreId: PersistentStoreId,
    val knowledgeStoreId: PersistentStoreId,
    val llamaAssembly: AndroidLlamaCppCognitiveModelAssembly,
    val maxCandidatesPerSource: Int,
    val personaDefinition: AndroidHeartProductionPersonaDefinition,
    val scope: CognitiveRuntimeScopeId,
    val cognitiveMaterialization: CognitiveMaterializationPort,
    val outcomeMaterialization: CognitiveOutcomeMaterializationPort,
    val policies: LearningPolicyComposition,
    val policyReference: LearningPolicyReference,
    val authority: CapabilityAuthorityComposition,
    val principal: AuthorityPrincipal,
    val governance: CognitiveLearningGovernancePort,
    val learningMaterialization: CognitiveLearningApplicationMaterializationPort,
    val learningMutationStoreId: PersistentStoreId,
    val artifactIds: CognitiveArtifactIdSource,
    val timestamps: CognitiveTimestampSource,
    val limits: CognitiveRuntimeLimits = CognitiveRuntimeLimits(),
    val personaLimits: AndroidHeartProductionPersonaLimits =
        AndroidHeartProductionPersonaLimits()
)


internal fun interface AndroidProductRuntimeStartupLearningMutationsOpenPort {
    fun open(
        storeId: PersistentStoreId,
        activeDek: CognitiveDekReference
    ): pro.liliya.android.cognitivestorage.AndroidEncryptedLearningMutationOpenResult
}

internal fun resolveStartupLearningMutations(
    storeId: PersistentStoreId,
    activeDek: CognitiveDekReference,
    openPort: AndroidProductRuntimeStartupLearningMutationsOpenPort
): AndroidProductRuntimeStartupPreparationResult<pro.liliya.core.learning.EncryptedPersistentLearningApplicationMutationComposition> =
    when (val opened = try {
        openPort.open(storeId, activeDek)
    } catch (_: Exception) {
        return AndroidProductRuntimeStartupPreparationResult.Rejected
    }) {
        is pro.liliya.android.cognitivestorage.AndroidEncryptedLearningMutationOpenResult.Opened ->
            AndroidProductRuntimeStartupPreparationResult.Ready(opened.composition)
        pro.liliya.android.cognitivestorage.AndroidEncryptedLearningMutationOpenResult.Corrupt,
        is pro.liliya.android.cognitivestorage.AndroidEncryptedLearningMutationOpenResult.Incompatible,
        is pro.liliya.android.cognitivestorage.AndroidEncryptedLearningMutationOpenResult.EncryptionUnavailable,
        is pro.liliya.android.cognitivestorage.AndroidEncryptedLearningMutationOpenResult.Failed ->
            AndroidProductRuntimeStartupPreparationResult.Rejected
    }

internal fun interface AndroidProductRuntimeStartupPreparedInputsBuildPort {
    fun build(
        activeDek: CognitiveDekReference,
        semantic: AndroidProductRuntimeSemanticArtifacts,
        stagedModel: LargeProtectedModelStagedSourceOwnership
    ): AndroidProductRuntimeStartupPreparationResult<AndroidProductRuntimeHostPreparedInputs>
}

class AndroidProductRuntimeStartupPreparedInputsAdapter internal constructor(
    private val buildPort: AndroidProductRuntimeStartupPreparedInputsBuildPort
) : AndroidProductRuntimeStartupPreparedInputsPort {
    constructor(
        owners: AndroidProductRuntimeStartupPreparedInputOwners
    ) : this(
        AndroidProductRuntimeStartupPreparedInputsBuildPort { activeDek, semantic, stagedModel ->
            val mutations = when (
                val resolved = resolveStartupLearningMutations(
                    storeId = owners.learningMutationStoreId,
                    activeDek = activeDek,
                    openPort = AndroidProductRuntimeStartupLearningMutationsOpenPort { storeId, dek ->
                        owners.cognitiveStorage.openEncryptedLearningMutations(storeId, dek)
                    }
                )
            ) {
                is AndroidProductRuntimeStartupPreparationResult.Ready -> resolved.value
                AndroidProductRuntimeStartupPreparationResult.Rejected ->
                    return@AndroidProductRuntimeStartupPreparedInputsBuildPort AndroidProductRuntimeStartupPreparationResult.Rejected
            }

            AndroidProductRuntimeStartupPreparationResult.Ready(
                AndroidProductRuntimeHostPreparedInputs(
                    foundation = owners.foundation,
                    cognitiveStorage = owners.cognitiveStorage,
                    memoryStoreId = owners.memoryStoreId,
                    knowledgeStoreId = owners.knowledgeStoreId,
                    activeDek = activeDek,
                    semanticRoot = semantic.root,
                    semanticEncoderFile = semantic.encoderFile,
                    llamaAssembly = owners.llamaAssembly,
                    stagedModel = stagedModel,
                    maxCandidatesPerSource = owners.maxCandidatesPerSource,
                    personaDefinition = owners.personaDefinition,
                    scope = owners.scope,
                    cognitiveMaterialization = owners.cognitiveMaterialization,
                    outcomeMaterialization = owners.outcomeMaterialization,
                    policies = owners.policies,
                    policyReference = owners.policyReference,
                    authority = owners.authority,
                    principal = owners.principal,
                    governance = owners.governance,
                    learningMaterialization = owners.learningMaterialization,
                    mutations = mutations,
                    artifactIds = owners.artifactIds,
                    timestamps = owners.timestamps,
                    limits = owners.limits,
                    personaLimits = owners.personaLimits
                )
            )
        }
    )

    override fun prepare(
        activeDek: CognitiveDekReference,
        semantic: AndroidProductRuntimeSemanticArtifacts,
        stagedModel: LargeProtectedModelStagedSourceOwnership
    ): AndroidProductRuntimeStartupPreparationResult<AndroidProductRuntimeHostPreparedInputs> =
        try {
            buildPort.build(activeDek, semantic, stagedModel)
        } catch (_: Exception) {
            AndroidProductRuntimeStartupPreparationResult.Rejected
        }
}
