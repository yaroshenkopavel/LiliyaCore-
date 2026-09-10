package pro.liliya.android.runtime

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.Test
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.CognitiveRuntimeScopeId
import pro.liliya.core.identity.SelfIdentityId
import pro.liliya.core.identity.SelfName
import pro.liliya.core.identity.SelfSourceId
import pro.liliya.core.learning.LearningPolicyComposition
import pro.liliya.core.learning.LearningPolicyId
import pro.liliya.core.learning.LearningPolicyReference
import pro.liliya.core.learning.LearningPolicyGeneration
import pro.liliya.core.personality.PersonalityAttribute
import pro.liliya.core.personality.PersonalityAttributeKey
import pro.liliya.core.personality.PersonalityAttributeValue
import pro.liliya.core.personality.PersonalityProfileId
import pro.liliya.core.personality.PersonalitySourceId
import pro.liliya.core.persistence.PersistentStoreId

class AndroidProductRuntimeStartupPreparedOwnerTemplateFactoryContractTest {
    @Test
    fun exact_explicit_owners_are_forwarded_without_policy_or_identity_substitution() {
        val limits = CognitiveRuntimeLimits()
        val structural = requireReady(
            AndroidProductRuntimeCognitiveStructuralOwnersFactory.create(limits)
        )
        val disabled = requireDisabled(
            AndroidProductRuntimeDisabledLearningOwnersFactory.create(limits)
        )
        val base = fixtureBase(limits)

        val template = AndroidProductRuntimeStartupPreparedOwnerTemplateFactory.create(
            base = base,
            structural = structural,
            governance = disabled.governance,
            learningMaterialization = disabled.applicationMaterialization
        )

        assertEquals(base.memoryStoreId, template.memoryStoreId)
        assertEquals(base.knowledgeStoreId, template.knowledgeStoreId)
        assertSame(base.llamaAssembly, template.llamaAssembly)
        assertEquals(base.maxCandidatesPerSource, template.maxCandidatesPerSource)
        assertSame(base.personaDefinition, template.personaDefinition)
        assertEquals(base.scope, template.scope)
        assertSame(structural.cognitiveMaterialization, template.cognitiveMaterialization)
        assertSame(structural.outcomeMaterialization, template.outcomeMaterialization)
        assertSame(base.policies, template.policies)
        assertEquals(base.policyReference, template.policyReference)
        assertEquals(base.principal, template.principal)
        assertSame(disabled.governance, template.governance)
        assertSame(disabled.applicationMaterialization, template.learningMaterialization)
        assertEquals(base.learningMutationStoreId, template.learningMutationStoreId)
        assertSame(structural.artifactIds, template.artifactIds)
        assertSame(structural.timestamps, template.timestamps)
        assertSame(base.limits, template.limits)
        assertSame(base.personaLimits, template.personaLimits)
    }

    private fun requireReady(
        result: AndroidProductRuntimeCognitiveStructuralOwnersResult
    ): AndroidProductRuntimeCognitiveStructuralOwners =
        (result as AndroidProductRuntimeCognitiveStructuralOwnersResult.Ready).owners

    private fun requireDisabled(
        result: AndroidProductRuntimeDisabledLearningOwnersResult
    ): AndroidProductRuntimeDisabledLearningOwners =
        (result as AndroidProductRuntimeDisabledLearningOwnersResult.Ready).owners

    private fun fixtureBase(
        limits: CognitiveRuntimeLimits
    ): AndroidProductRuntimeStartupPreparedOwnerBaseInput {
        error("fixture requires existing product llama/policy owners and is compile-only placeholder")
    }
}
