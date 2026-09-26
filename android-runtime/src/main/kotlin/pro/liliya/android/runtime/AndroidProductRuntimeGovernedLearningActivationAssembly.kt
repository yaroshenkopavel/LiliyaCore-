package pro.liliya.android.runtime

import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.cognitive.CognitiveArtifactIdSource
import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationPort
import pro.liliya.core.cognitive.CognitiveLearningGovernancePort
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.CognitiveRuntimeScopeId
import pro.liliya.core.cognitive.CognitiveTimestampSource
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.learning.EncryptedPersistentLearningApplicationMutationComposition
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.learning.LearningPolicyComposition
import pro.liliya.core.learning.LearningPolicyReference

/**
 * Product-owned entry point for enabling mutation-capable governed learning.
 *
 * The underlying governed-learning composition is intentionally not created until complete
 * [AndroidProductRuntimeLearningEnablementEvidence] has been accepted by the one-shot activation
 * session. Public product wiring must also supply an activation journal so an interrupted activation
 * cannot silently become a fresh attempt after process restart.
 *
 * Evidence is not Authority: the resulting composition still performs the existing Core
 * per-mutation authorization path and this assembly never mints capabilities.
 */
object AndroidProductRuntimeGovernedLearningActivationAssembly {

    fun createLifecycle(
        heart: AndroidHeartRuntimeAssembly,
        foundation: FoundationComposition,
        scope: CognitiveRuntimeScopeId,
        learning: LearningComposition,
        policies: LearningPolicyComposition,
        policyReference: LearningPolicyReference,
        authority: CapabilityAuthorityComposition,
        principal: AuthorityPrincipal,
        governance: CognitiveLearningGovernancePort,
        materialization: CognitiveLearningApplicationMaterializationPort,
        mutations: EncryptedPersistentLearningApplicationMutationComposition,
        artifactIds: CognitiveArtifactIdSource,
        timestamps: CognitiveTimestampSource,
        journal: AndroidProductRuntimeLearningActivationJournal,
        recoverySafety: AndroidProductRuntimeLearningActivationRecoverySafetyPort,
        limits: CognitiveRuntimeLimits = CognitiveRuntimeLimits()
    ): AndroidProductRuntimeLearningActivationLifecycle<AndroidHeartProductionGovernedLearningComposition> {
        val session = create(
            heart = heart,
            foundation = foundation,
            scope = scope,
            learning = learning,
            policies = policies,
            policyReference = policyReference,
            authority = authority,
            principal = principal,
            governance = governance,
            materialization = materialization,
            mutations = mutations,
            artifactIds = artifactIds,
            timestamps = timestamps,
            journal = journal,
            limits = limits
        )
        return AndroidProductRuntimeLearningActivationLifecycle(
            session = session,
            journal = journal,
            recoverySafety = recoverySafety
        )
    }

    fun create(
        heart: AndroidHeartRuntimeAssembly,
        foundation: FoundationComposition,
        scope: CognitiveRuntimeScopeId,
        learning: LearningComposition,
        policies: LearningPolicyComposition,
        policyReference: LearningPolicyReference,
        authority: CapabilityAuthorityComposition,
        principal: AuthorityPrincipal,
        governance: CognitiveLearningGovernancePort,
        materialization: CognitiveLearningApplicationMaterializationPort,
        mutations: EncryptedPersistentLearningApplicationMutationComposition,
        artifactIds: CognitiveArtifactIdSource,
        timestamps: CognitiveTimestampSource,
        journal: AndroidProductRuntimeLearningActivationJournal,
        limits: CognitiveRuntimeLimits = CognitiveRuntimeLimits()
    ): AndroidProductRuntimeLearningActivationSession<AndroidHeartProductionGovernedLearningComposition> =
        createSession(journal) {
            when (
                val created = AndroidHeartProductionGovernedLearningAssembly.create(
                    heart = heart,
                    foundation = foundation,
                    scope = scope,
                    learning = learning,
                    policies = policies,
                    policyReference = policyReference,
                    authority = authority,
                    principal = principal,
                    governance = governance,
                    materialization = materialization,
                    mutations = mutations,
                    artifactIds = artifactIds,
                    timestamps = timestamps,
                    limits = limits
                )
            ) {
                is AndroidHeartProductionGovernedLearningCreateResult.Ready -> created.composition
                is AndroidHeartProductionGovernedLearningCreateResult.Rejected ->
                    throw GovernedLearningActivationCreationRejected(created.reason)
            }
        }

    internal fun createSession(
        journal: AndroidProductRuntimeLearningActivationJournal =
            InMemoryAndroidProductRuntimeLearningActivationJournal(),
        createComposition: () -> AndroidHeartProductionGovernedLearningComposition
    ): AndroidProductRuntimeLearningActivationSession<AndroidHeartProductionGovernedLearningComposition> =
        AndroidProductRuntimeLearningActivationSession(
            activation = createComposition,
            journal = journal
        )

    private class GovernedLearningActivationCreationRejected(
        val reason: AndroidHeartProductionGovernedLearningCreateFailure
    ) : IllegalStateException("governed learning activation creation rejected")
}
