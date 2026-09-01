package pro.liliya.core.cognitive

import pro.liliya.core.learning.LearningApplicationTarget
import pro.liliya.core.learning.LearningCandidateSnapshot
import pro.liliya.core.learning.LearningPolicySnapshot

data class CognitiveLearningGovernanceBudgets(
    val maxRationaleChars: Int
) {
    init { require(maxRationaleChars > 0) { "learning governance rationale budget must be positive" } }

    companion object {
        fun from(limits: CognitiveRuntimeLimits) = CognitiveLearningGovernanceBudgets(
            maxRationaleChars = limits.maxLearningGovernanceRationaleChars
        )
    }
}

class CognitiveLearningGovernanceRequest(
    val candidate: LearningCandidateSnapshot,
    val policy: LearningPolicySnapshot,
    allowedTargets: List<LearningApplicationTarget>,
    val budgets: CognitiveLearningGovernanceBudgets
) {
    val allowedTargets: List<LearningApplicationTarget> = allowedTargets.distinct().toList()

    init { require(this.allowedTargets.isNotEmpty()) { "learning governance allowed targets must not be empty" } }

    override fun toString(): String =
        "CognitiveLearningGovernanceRequest(" +
            "candidateId=${candidate.candidate.id}, candidateGeneration=${candidate.generation}, " +
            "policyId=${policy.policy.id}, policyGeneration=${policy.generation}, " +
            "allowedTargets=$allowedTargets, candidateProposal=<redacted>, policyRule=<redacted>, budgets=$budgets)"
}

sealed interface CognitiveLearningGovernanceResult {
    class Approved(
        val target: LearningApplicationTarget,
        val rationale: String
    ) : CognitiveLearningGovernanceResult {
        init { require(rationale.isNotBlank()) { "learning governance rationale must not be blank" } }
        override fun toString(): String = "Approved(target=$target, rationale=<redacted:${rationale.length}>)"
    }

    class Rejected(
        val rationale: String
    ) : CognitiveLearningGovernanceResult {
        init { require(rationale.isNotBlank()) { "learning governance rationale must not be blank" } }
        override fun toString(): String = "Rejected(rationale=<redacted:${rationale.length}>)"
    }
}

/** Trusted policy seam. This is not CognitiveInferencePort and carries no Authority handles. */
fun interface CognitiveLearningGovernancePort {
    fun evaluate(request: CognitiveLearningGovernanceRequest): CognitiveLearningGovernanceResult
}

data class CognitiveLearningApplicationBudgets(
    val maxContentChars: Int
) {
    init { require(maxContentChars > 0) { "learning application content budget must be positive" } }

    companion object {
        fun from(limits: CognitiveRuntimeLimits) = CognitiveLearningApplicationBudgets(
            maxContentChars = limits.maxLearningMutationContentChars
        )
    }
}

class CognitiveLearningApplicationMaterializationRequest(
    val candidate: LearningCandidateSnapshot,
    val policy: LearningPolicySnapshot,
    val target: LearningApplicationTarget,
    val budgets: CognitiveLearningApplicationBudgets
) {
    override fun toString(): String =
        "CognitiveLearningApplicationMaterializationRequest(" +
            "candidateId=${candidate.candidate.id}, candidateGeneration=${candidate.generation}, " +
            "policyId=${policy.policy.id}, policyGeneration=${policy.generation}, target=$target, " +
            "candidateProposal=<redacted>, policyRule=<redacted>, budgets=$budgets)"
}

sealed interface CognitiveLearningApplicationMaterializationResult {
    class Succeeded(
        val content: String
    ) : CognitiveLearningApplicationMaterializationResult {
        init { require(content.isNotBlank()) { "learning mutation content must not be blank" } }
        override fun toString(): String = "Succeeded(content=<redacted:${content.length}>)"
    }

    data class Rejected(
        val reason: CognitiveLearningApplicationMaterializationFailure
    ) : CognitiveLearningApplicationMaterializationResult
}

enum class CognitiveLearningApplicationMaterializationFailure {
    MATERIALIZER_REJECTED,
    RESOURCE_LIMIT_REJECTED
}

/** Typed-content seam only. Target, ids, provenance, idempotency and Authority remain Core-owned. */
fun interface CognitiveLearningApplicationMaterializationPort {
    fun materialize(
        request: CognitiveLearningApplicationMaterializationRequest
    ): CognitiveLearningApplicationMaterializationResult
}
