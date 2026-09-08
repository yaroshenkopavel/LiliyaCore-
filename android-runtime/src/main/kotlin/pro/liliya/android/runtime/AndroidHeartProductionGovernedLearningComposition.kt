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
import pro.liliya.core.learning.LearningApplicationMutationAuthorizationGate
import pro.liliya.core.learning.LearningApplicationPreflightValidator
import pro.liliya.core.learning.LearningApplicationTarget
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.learning.LearningDecisionComposition
import pro.liliya.core.learning.LearningPolicyComposition
import pro.liliya.core.learning.LearningPolicyReference
import pro.liliya.core.learning.inspectionPort
import pro.liliya.core.learning.preparationPort

enum class AndroidHeartProductionGovernedLearningCreationFailure {
    HEART_NOT_READY,
    MUTATION_APPLICATION_UNAVAILABLE,
    INVALID_CONFIGURATION
}

sealed interface AndroidHeartProductionGovernedLearningCreationResult {
    data class Ready(
        val composition: AndroidHeartProductionGovernedLearningComposition
    ) : AndroidHeartProductionGovernedLearningCreationResult

    data class Rejected(
        val reason: AndroidHeartProductionGovernedLearningCreationFailure
    ) : AndroidHeartProductionGovernedLearningCreationResult
}

enum class AndroidHeartProductionGovernedLearningFailure {
    HEART_NOT_READY,
    INTERNAL_FAILURE
}

sealed interface AndroidHeartProductionGovernedLearningResult {
    data class Completed(
        val result: AndroidHeartGovernedLearningResult
    ) : AndroidHeartProductionGovernedLearningResult

    data class Rejected(
        val reason: AndroidHeartProductionGovernedLearningFailure
    ) : AndroidHeartProductionGovernedLearningResult
}

/**
 * Production-owned composition root for the already-accepted governed-learning pipeline.
 *
 * This class owns wiring only. Candidate/policy state, Authority, encrypted mutation state,
 * authoritative Memory, semantic index and Learning semantics remain owned by their existing
 * compositions.
 */
internal fun interface AndroidHeartProductionGovernedLearningProcessPort {
    fun process(reference: CognitiveLearningReference): AndroidHeartGovernedLearningResult
}

internal fun interface AndroidHeartProductionGovernedLearningStatePort {
    fun state(): HeartRuntimeState
}

class AndroidHeartProductionGovernedLearningComposition internal constructor(
    private val heartState: AndroidHeartProductionGovernedLearningStatePort,
    private val governed: AndroidHeartProductionGovernedLearningProcessPort
) {
    fun process(
        reference: CognitiveLearningReference
    ): AndroidHeartProductionGovernedLearningResult {
        if (heartState.state() != HeartRuntimeState.READY) {
            return AndroidHeartProductionGovernedLearningResult.Rejected(
                AndroidHeartProductionGovernedLearningFailure.HEART_NOT_READY
            )
        }

        return try {
            AndroidHeartProductionGovernedLearningResult.Completed(
                governed.process(reference)
            )
        } catch (_: Exception) {
            AndroidHeartProductionGovernedLearningResult.Rejected(
                AndroidHeartProductionGovernedLearningFailure.INTERNAL_FAILURE
            )
        }
    }

    override fun toString(): String =
        "AndroidHeartProductionGovernedLearningComposition(heartState=<redacted>,governed=<redacted>)"

    companion object {
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
            limits: CognitiveRuntimeLimits
        ): AndroidHeartProductionGovernedLearningCreationResult {
            if (heart.state() != HeartRuntimeState.READY) {
                return AndroidHeartProductionGovernedLearningCreationResult.Rejected(
                    AndroidHeartProductionGovernedLearningCreationFailure.HEART_NOT_READY
                )
            }

            val decisions = LearningDecisionComposition(foundation)
            val applications = LearningApplicationComposition(foundation)

            val preflight = LearningApplicationPreflightValidator(
                applications = applications,
                decisions = decisions,
                learning = learning,
                policies = policies
            )
            val authorizer = LearningApplicationAuthorizer(
                preflight = preflight,
                authority = authority
            )
            val authorizationGate = LearningApplicationMutationAuthorizationGate(
                mutations = mutations.inspectionPort(),
                authorizer = authorizer
            )
            val mutationApplication = heart.learningMutationApplicationPort(
                foundation = foundation,
                mutations = mutations,
                authorizationGate = authorizationGate
            ) ?: return AndroidHeartProductionGovernedLearningCreationResult.Rejected(
                AndroidHeartProductionGovernedLearningCreationFailure.MUTATION_APPLICATION_UNAVAILABLE
            )

            val core = try {
                CognitiveGovernedLearningComposition(
                    foundation = foundation,
                    scope = scope,
                    learning = learning,
                    policies = policies,
                    policyReference = policyReference,
                    governance = governance,
                    decisions = decisions,
                    materialization = materialization,
                    applications = applications,
                    mutations = mutations.preparationPort(),
                    mutationApplier = mutationApplication,
                    principal = principal,
                    allowedTargets = listOf(LearningApplicationTarget.MEMORY),
                    artifactIds = artifactIds,
                    timestamps = timestamps,
                    limits = limits
                )
            } catch (_: RuntimeException) {
                return AndroidHeartProductionGovernedLearningCreationResult.Rejected(
                    AndroidHeartProductionGovernedLearningCreationFailure.INVALID_CONFIGURATION
                )
            }

            val governed = heart.governedLearning(core)
                ?: return AndroidHeartProductionGovernedLearningCreationResult.Rejected(
                    AndroidHeartProductionGovernedLearningCreationFailure.HEART_NOT_READY
                )

            return AndroidHeartProductionGovernedLearningCreationResult.Ready(
                AndroidHeartProductionGovernedLearningComposition(
                    heartState = AndroidHeartProductionGovernedLearningStatePort { heart.state() },
                    governed = AndroidHeartProductionGovernedLearningProcessPort { reference ->
                        governed.process(reference)
                    }
                )
            )
        }
    }
}
