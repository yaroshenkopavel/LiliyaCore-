package pro.liliya.core.cognitive

import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.learning.LearningApplicationComposition
import pro.liliya.core.learning.LearningApplicationDownstreamReference
import pro.liliya.core.learning.LearningApplicationMutationApplier
import pro.liliya.core.learning.LearningApplicationMutationComposition
import pro.liliya.core.learning.LearningApplicationTarget
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.learning.LearningDecisionComposition
import pro.liliya.core.learning.LearningPolicyComposition
import pro.liliya.core.learning.LearningPolicyReference

/**
 * Public Cognitive Runtime boundary for governed learning application.
 *
 * Governance approval is not Authority. This composition delegates actual downstream mutation to the
 * existing LearningApplicationMutationApplier and only records structural observability.
 */
class CognitiveGovernedLearningComposition(
    private val foundation: FoundationComposition,
    scope: CognitiveRuntimeScopeId,
    learning: LearningComposition,
    policies: LearningPolicyComposition,
    policyReference: LearningPolicyReference,
    governance: CognitiveLearningGovernancePort,
    decisions: LearningDecisionComposition,
    materialization: CognitiveLearningApplicationMaterializationPort,
    applications: LearningApplicationComposition,
    mutations: LearningApplicationMutationComposition,
    mutationApplier: LearningApplicationMutationApplier,
    principal: AuthorityPrincipal,
    allowedTargets: List<LearningApplicationTarget>,
    artifactIds: CognitiveArtifactIdSource,
    timestamps: CognitiveTimestampSource,
    limits: CognitiveRuntimeLimits = CognitiveRuntimeLimits()
) {
    private val coordinator = CognitiveGovernedLearningCoordinator(
        scope = scope,
        learning = learning,
        policies = policies,
        policyReference = policyReference,
        governance = governance,
        decisions = decisions,
        materialization = materialization,
        applications = applications,
        mutations = mutations,
        mutationApplier = mutationApplier,
        principal = principal,
        allowedTargets = allowedTargets,
        artifactIds = artifactIds,
        timestamps = timestamps,
        limits = limits
    )

    fun process(reference: CognitiveLearningReference): CognitiveGovernedLearningResult {
        val context = foundation.rootContext(
            operation = "processCognitiveGovernedLearning",
            component = "CognitiveRuntime",
            metadata = mapOf(
                "learningGeneration" to reference.generation.value.toString()
            )
        )
        val result = coordinator.process(reference)
        when (result) {
            is CognitiveGovernedLearningResult.GovernanceRejected -> foundation.observability.record(
                DiagnosticSeverity.INFO,
                "COGNITIVE_GOVERNED_LEARNING_GOVERNANCE_REJECTED",
                "cognitive governed learning rejected by governance",
                context,
                decisionMetadata(result.decision)
            )

            is CognitiveGovernedLearningResult.Applied -> foundation.observability.record(
                DiagnosticSeverity.INFO,
                "COGNITIVE_GOVERNED_LEARNING_APPLIED",
                "cognitive governed learning applied",
                context,
                decisionMetadata(result.decision) +
                    applicationMetadata(result.application) +
                    mutationMetadata(result.mutation) +
                    downstreamMetadata(result.receipt.downstream)
            )

            is CognitiveGovernedLearningResult.CompletionCompensated -> foundation.observability.record(
                DiagnosticSeverity.WARNING,
                "COGNITIVE_GOVERNED_LEARNING_COMPLETION_COMPENSATED",
                "cognitive governed learning completion failed and downstream was compensated",
                context,
                decisionMetadata(result.decision) +
                    applicationMetadata(result.application) +
                    mutationMetadata(result.mutation) +
                    mapOf("learningApplicationTarget" to result.target.name.lowercase())
            )

            is CognitiveGovernedLearningResult.PartialFailure -> foundation.observability.record(
                DiagnosticSeverity.ERROR,
                "COGNITIVE_GOVERNED_LEARNING_PARTIAL_FAILURE",
                "cognitive governed learning ended with visible partial failure",
                context,
                decisionMetadata(result.decision) +
                    applicationMetadata(result.application) +
                    mutationMetadata(result.mutation) +
                    downstreamMetadata(result.downstream)
            )

            is CognitiveGovernedLearningResult.AlreadyProcessed -> foundation.observability.record(
                DiagnosticSeverity.INFO,
                "COGNITIVE_GOVERNED_LEARNING_ALREADY_PROCESSED",
                "cognitive governed learning candidate was already processed",
                context,
                mapOf("terminalStatus" to result.status.name)
            )

            is CognitiveGovernedLearningResult.Rejected -> {
                val partial = result.reason ==
                    CognitiveGovernedLearningFailure.COORDINATOR_PARTIAL_FAILURE
                foundation.observability.record(
                    if (partial) DiagnosticSeverity.ERROR else DiagnosticSeverity.WARNING,
                    if (partial) {
                        "COGNITIVE_GOVERNED_LEARNING_COORDINATOR_PARTIAL_FAILURE"
                    } else {
                        "COGNITIVE_GOVERNED_LEARNING_REJECTED"
                    },
                    if (partial) {
                        "cognitive governed learning ended after an unexpected coordinator failure"
                    } else {
                        "cognitive governed learning rejected"
                    },
                    context,
                    mapOf("rejectionReason" to result.reason.name)
                )
            }
        }
        return result
    }

    private fun decisionMetadata(
        reference: pro.liliya.core.learning.LearningDecisionReference
    ): Map<String, String> = mapOf(
        "learningDecisionId" to reference.decisionId.value,
        "learningDecisionGeneration" to reference.generation.value.toString()
    )

    private fun applicationMetadata(
        reference: pro.liliya.core.learning.LearningApplicationIntentReference
    ): Map<String, String> = mapOf(
        "learningApplicationId" to reference.applicationId.value,
        "learningApplicationGeneration" to reference.generation.value.toString()
    )

    private fun mutationMetadata(
        reference: pro.liliya.core.learning.LearningApplicationMutationReference
    ): Map<String, String> = mapOf(
        "learningApplicationMutationId" to reference.mutationId.value,
        "learningApplicationMutationGeneration" to reference.generation.value.toString()
    )

    private fun downstreamMetadata(
        reference: LearningApplicationDownstreamReference
    ): Map<String, String> = mapOf(
        "downstreamType" to when (reference) {
            is LearningApplicationDownstreamReference.Memory -> "memory"
            is LearningApplicationDownstreamReference.Knowledge -> "knowledge"
        }
    )
}
