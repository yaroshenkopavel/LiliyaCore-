package pro.liliya.android.runtime

import pro.liliya.android.llamacppengine.AndroidLlamaCppCognitiveModelAssembly
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationPort
import pro.liliya.core.cognitive.CognitiveLearningGovernancePort
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.CognitiveRuntimeScopeId
import pro.liliya.core.learning.LearningPolicyComposition
import pro.liliya.core.learning.LearningPolicyReference
import pro.liliya.core.persistence.PersistentStoreId

/**
 * Explicit product-owned values for the static startup prepared-input template.
 *
 * No Foundation, cognitive-storage or CapabilityAuthority owner appears here because
 * AndroidProductRuntimeStartupRequestSource supplies those from verified startup ownership.
 */
data class AndroidProductRuntimeStartupPreparedOwnerBaseInput(
    val memoryStoreId: PersistentStoreId,
    val knowledgeStoreId: PersistentStoreId,
    val llamaAssembly: AndroidLlamaCppCognitiveModelAssembly,
    val maxCandidatesPerSource: Int,
    val personaDefinition: AndroidHeartProductionPersonaDefinition,
    val scope: CognitiveRuntimeScopeId,
    val policies: LearningPolicyComposition,
    val policyReference: LearningPolicyReference,
    val principal: AuthorityPrincipal,
    val learningMutationStoreId: PersistentStoreId,
    val limits: CognitiveRuntimeLimits,
    val personaLimits: AndroidHeartProductionPersonaLimits
) {
    init {
        require(maxCandidatesPerSource > 0) {
            "startup maxCandidatesPerSource must be positive"
        }
    }
}

/**
 * Pure composition of already-authoritative product owners into the canonical startup template.
 *
 * Prepared Template Factory != Foundation.
 * Prepared Template Factory != Capability Authority.
 * Prepared Template Factory != Learning Policy Selection.
 * Prepared Template Factory != Persona Definition Selection.
 * Prepared Template Factory != Store-ID Selection.
 */
object AndroidProductRuntimeStartupPreparedOwnerTemplateFactory {
    fun create(
        base: AndroidProductRuntimeStartupPreparedOwnerBaseInput,
        structural: AndroidProductRuntimeCognitiveStructuralOwners,
        governance: CognitiveLearningGovernancePort,
        learningMaterialization: CognitiveLearningApplicationMaterializationPort
    ): AndroidProductRuntimeStartupPreparedInputOwnerTemplate =
        AndroidProductRuntimeStartupPreparedInputOwnerTemplate(
            memoryStoreId = base.memoryStoreId,
            knowledgeStoreId = base.knowledgeStoreId,
            llamaAssembly = base.llamaAssembly,
            maxCandidatesPerSource = base.maxCandidatesPerSource,
            personaDefinition = base.personaDefinition,
            scope = base.scope,
            cognitiveMaterialization = structural.cognitiveMaterialization,
            outcomeMaterialization = structural.outcomeMaterialization,
            policies = base.policies,
            policyReference = base.policyReference,
            principal = base.principal,
            governance = governance,
            learningMaterialization = learningMaterialization,
            learningMutationStoreId = base.learningMutationStoreId,
            artifactIds = structural.artifactIds,
            timestamps = structural.timestamps,
            limits = base.limits,
            personaLimits = base.personaLimits
        )
}
