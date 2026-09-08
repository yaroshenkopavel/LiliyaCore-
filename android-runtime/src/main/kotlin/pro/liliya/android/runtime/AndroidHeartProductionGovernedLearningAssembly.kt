package pro.liliya.android.runtime

import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.cognitive.CognitiveArtifactIdSource
import pro.liliya.core.cognitive.CognitiveGovernedLearningComposition
import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationPort
import pro.liliya.core.cognitive.CognitiveLearningGovernancePort
import pro.liliya.core.cognitive.CognitiveLearningReference
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.CognitiveRuntimeScopeId
import pro.liliya.core.cognitive.CognitiveTimestampSource
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.learning.EncryptedPersistentLearningApplicationMutationComposition
import pro.liliya.core.learning.LearningApplicationAuthorizer
import pro.liliya.core.learning.LearningApplicationComposition
import pro.liliya.core.learning.LearningApplicationMutationApplicationPort
import pro.liliya.core.learning.LearningApplicationMutationAuthorizationGate
import pro.liliya.core.learning.LearningApplicationMutationInspectionPort
import pro.liliya.core.learning.LearningApplicationMutationPreparationPort
import pro.liliya.core.learning.LearningApplicationPreflightValidator
import pro.liliya.core.learning.LearningApplicationTarget
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.learning.LearningDecisionComposition
import pro.liliya.core.learning.LearningPolicyComposition
import pro.liliya.core.learning.LearningPolicyReference
import pro.liliya.core.learning.inspectionPort
import pro.liliya.core.learning.preparationPort

enum class AndroidHeartProductionGovernedLearningCreateFailure {
    HEART_NOT_READY,
    MUTATION_APPLICATION_UNAVAILABLE,
    COMPOSITION_REJECTED
}

sealed interface AndroidHeartProductionGovernedLearningCreateResult {
    data class Ready(
        val composition: AndroidHeartProductionGovernedLearningComposition
    ) : AndroidHeartProductionGovernedLearningCreateResult

    data class Rejected(
        val reason: AndroidHeartProductionGovernedLearningCreateFailure
    ) : AndroidHeartProductionGovernedLearningCreateResult
}

sealed interface AndroidHeartProductionGovernedLearningProcessResult {
    data class Processed(
        val result: AndroidHeartGovernedLearningResult
    ) : AndroidHeartProductionGovernedLearningProcessResult

    data object NotReady : AndroidHeartProductionGovernedLearningProcessResult
    data object Failed : AndroidHeartProductionGovernedLearningProcessResult
}

internal interface AndroidHeartProductionGovernedLearningBridge {
    fun state(): HeartRuntimeState

    fun mutationApplicationPort(
        authorizationGate: LearningApplicationMutationAuthorizationGate
    ): LearningApplicationMutationApplicationPort?

    fun governedLearning(
        composition: CognitiveGovernedLearningComposition
    ): AndroidHeartGovernedLearningComposition?
}

/**
 * Production governed-learning operation boundary.
 *
 * This class owns only live Heart admission and delegation. Core governed-learning, Authority,
 * encrypted mutation persistence and semantic synchronization keep their existing ownership.
 */
class AndroidHeartProductionGovernedLearningComposition internal constructor(
    private val bridge: AndroidHeartProductionGovernedLearningBridge,
    private val governed: AndroidHeartGovernedLearningComposition
) {
    fun process(
        reference: CognitiveLearningReference
    ): AndroidHeartProductionGovernedLearningProcessResult {
        if (bridge.state() != HeartRuntimeState.READY) {
            return AndroidHeartProductionGovernedLearningProcessResult.NotReady
        }

        return try {
            AndroidHeartProductionGovernedLearningProcessResult.Processed(
                governed.process(reference)
            )
        } catch (_: Exception) {
            AndroidHeartProductionGovernedLearningProcessResult.Failed
        }
    }

    override fun toString(): String =
        "AndroidHeartProductionGovernedLearningComposition(" +
            "bridge=<redacted>,governed=<redacted>)"
}

/**
 * Production composition root for the physically accepted H4D governed-learning path.
 *
 * v0.1 is MEMORY-only. Policy and Capability Authority are supplied by an outer trusted owner.
 * This assembly never creates or grants Authority permissions.
 */
object AndroidHeartProductionGovernedLearningAssembly {

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
        limits: CognitiveRuntimeLimits = CognitiveRuntimeLimits()
    ): AndroidHeartProductionGovernedLearningCreateResult {
        val bridge = object : AndroidHeartProductionGovernedLearningBridge {
            override fun state(): HeartRuntimeState = heart.state()

            override fun mutationApplicationPort(
                authorizationGate: LearningApplicationMutationAuthorizationGate
            ): LearningApplicationMutationApplicationPort? =
                heart.learningMutationApplicationPort(
                    foundation = foundation,
                    mutations = mutations,
                    authorizationGate = authorizationGate
                )

            override fun governedLearning(
                composition: CognitiveGovernedLearningComposition
            ): AndroidHeartGovernedLearningComposition? =
                heart.governedLearning(composition)
        }

        return createInternal(
            bridge = bridge,
            foundation = foundation,
            scope = scope,
            learning = learning,
            policies = policies,
            policyReference = policyReference,
            authority = authority,
            principal = principal,
            governance = governance,
            materialization = materialization,
            mutationPreparation = mutations.preparationPort(),
            mutationInspection = mutations.inspectionPort(),
            artifactIds = artifactIds,
            timestamps = timestamps,
            limits = limits
        )
    }

    internal fun createInternal(
        bridge: AndroidHeartProductionGovernedLearningBridge,
        foundation: FoundationComposition,
        scope: CognitiveRuntimeScopeId,
        learning: LearningComposition,
        policies: LearningPolicyComposition,
        policyReference: LearningPolicyReference,
        authority: CapabilityAuthorityComposition,
        principal: AuthorityPrincipal,
        governance: CognitiveLearningGovernancePort,
        materialization: CognitiveLearningApplicationMaterializationPort,
        mutationPreparation: LearningApplicationMutationPreparationPort,
        mutationInspection: LearningApplicationMutationInspectionPort,
        artifactIds: CognitiveArtifactIdSource,
        timestamps: CognitiveTimestampSource,
        limits: CognitiveRuntimeLimits = CognitiveRuntimeLimits()
    ): AndroidHeartProductionGovernedLearningCreateResult {
        if (bridge.state() != HeartRuntimeState.READY) {
            return rejected(
                AndroidHeartProductionGovernedLearningCreateFailure.HEART_NOT_READY
            )
        }

        return try {
            val decisions = LearningDecisionComposition(foundation)
            val applications = LearningApplicationComposition(foundation)
            val preflight = LearningApplicationPreflightValidator(
                applications = applications,
                decisions = decisions,
                candidates = learning,
                policies = policies
            )
            val authorizer = LearningApplicationAuthorizer(
                preflight = preflight,
                authority = authority
            )
            val authorizationGate = LearningApplicationMutationAuthorizationGate(
                mutations = mutationInspection,
                authorizer = authorizer
            )
            val mutationApplication = bridge.mutationApplicationPort(authorizationGate)
                ?: return rejected(
                    AndroidHeartProductionGovernedLearningCreateFailure
                        .MUTATION_APPLICATION_UNAVAILABLE
                )

            val core = CognitiveGovernedLearningComposition(
                foundation = foundation,
                scope = scope,
                learning = learning,
                policies = policies,
                policyReference = policyReference,
                governance = governance,
                decisions = decisions,
                materialization = materialization,
                applications = applications,
                mutations = mutationPreparation,
                mutationApplier = mutationApplication,
                principal = principal,
                allowedTargets = listOf(LearningApplicationTarget.MEMORY),
                artifactIds = artifactIds,
                timestamps = timestamps,
                limits = limits
            )
            val governed = bridge.governedLearning(core)
                ?: return rejected(
                    AndroidHeartProductionGovernedLearningCreateFailure
                        .MUTATION_APPLICATION_UNAVAILABLE
                )

            AndroidHeartProductionGovernedLearningCreateResult.Ready(
                AndroidHeartProductionGovernedLearningComposition(
                    bridge = bridge,
                    governed = governed
                )
            )
        } catch (_: IllegalArgumentException) {
            rejected(
                AndroidHeartProductionGovernedLearningCreateFailure.COMPOSITION_REJECTED
            )
        }
    }

    private fun rejected(
        reason: AndroidHeartProductionGovernedLearningCreateFailure
    ): AndroidHeartProductionGovernedLearningCreateResult.Rejected =
        AndroidHeartProductionGovernedLearningCreateResult.Rejected(reason)
}
