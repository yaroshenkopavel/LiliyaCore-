package pro.liliya.core.learning

data class LearningApplicationIntentReference(
    val applicationId: LearningApplicationId,
    val generation: LearningApplicationGeneration
)

data class LearningApplicationPreflightReceipt(
    val application: LearningApplicationIntentReference,
    val decision: LearningDecisionReference,
    val candidate: LearningCandidateReference,
    val policy: LearningPolicyReference,
    val target: LearningApplicationTarget
)

enum class LearningApplicationPreflightRejection {
    APPLICATION_MISSING,
    APPLICATION_GENERATION_MISMATCH,
    DECISION_MISSING,
    DECISION_GENERATION_MISMATCH,
    DECISION_NOT_APPROVED,
    CANDIDATE_MISSING,
    CANDIDATE_GENERATION_MISMATCH,
    POLICY_MISSING,
    POLICY_GENERATION_MISMATCH
}

sealed interface LearningApplicationPreflightResult {
    /**
     * Structural preconditions are current and the referenced decision is APPROVE.
     * This is not authorization, application, consolidation, execution, or permission to mutate.
     */
    data class ReadyForAuthorization(
        val receipt: LearningApplicationPreflightReceipt
    ) : LearningApplicationPreflightResult

    data class Rejected(
        val reason: LearningApplicationPreflightRejection
    ) : LearningApplicationPreflightResult
}

class LearningApplicationPreflightValidator(
    private val applications: LearningApplicationComposition,
    private val decisions: LearningDecisionComposition,
    private val candidates: LearningComposition,
    private val policies: LearningPolicyComposition
) {
    fun validate(reference: LearningApplicationIntentReference): LearningApplicationPreflightResult {
        val applicationSnapshot = applications.inspect(reference.applicationId)
            ?: return rejected(LearningApplicationPreflightRejection.APPLICATION_MISSING)
        if (applicationSnapshot.generation != reference.generation) {
            return rejected(LearningApplicationPreflightRejection.APPLICATION_GENERATION_MISMATCH)
        }

        val intent = applicationSnapshot.intent
        val decisionSnapshot = decisions.inspect(intent.decision.decisionId)
            ?: return rejected(LearningApplicationPreflightRejection.DECISION_MISSING)
        if (decisionSnapshot.generation != intent.decision.generation) {
            return rejected(LearningApplicationPreflightRejection.DECISION_GENERATION_MISMATCH)
        }
        if (decisionSnapshot.decision.disposition != LearningDecisionDisposition.APPROVE) {
            return rejected(LearningApplicationPreflightRejection.DECISION_NOT_APPROVED)
        }

        val candidateReference = decisionSnapshot.decision.candidate
        val candidateSnapshot = candidates.inspect(candidateReference.candidateId)
            ?: return rejected(LearningApplicationPreflightRejection.CANDIDATE_MISSING)
        if (candidateSnapshot.generation != candidateReference.generation) {
            return rejected(LearningApplicationPreflightRejection.CANDIDATE_GENERATION_MISMATCH)
        }

        val policySnapshot = policies.inspect(intent.policy.policyId)
            ?: return rejected(LearningApplicationPreflightRejection.POLICY_MISSING)
        if (policySnapshot.generation != intent.policy.generation) {
            return rejected(LearningApplicationPreflightRejection.POLICY_GENERATION_MISMATCH)
        }

        return LearningApplicationPreflightResult.ReadyForAuthorization(
            LearningApplicationPreflightReceipt(
                application = reference,
                decision = intent.decision,
                candidate = candidateReference,
                policy = intent.policy,
                target = intent.target
            )
        )
    }

    private fun rejected(reason: LearningApplicationPreflightRejection) =
        LearningApplicationPreflightResult.Rejected(reason)
}
