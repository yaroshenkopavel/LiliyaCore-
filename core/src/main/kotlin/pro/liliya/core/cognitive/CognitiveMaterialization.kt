package pro.liliya.core.cognitive

import java.time.Instant

data class CognitiveMaterializationBudgets(
    val maxPlanningGoalChars: Int,
    val maxPlanningSteps: Int,
    val maxPlanningStepChars: Int,
    val maxReasoningPremises: Int,
    val maxReasoningPremiseChars: Int,
    val maxReasoningAnalysisChars: Int,
    val maxReasoningConclusionChars: Int,
    val maxDecisionOptions: Int,
    val maxDecisionOptionChars: Int,
    val maxDecisionRationaleChars: Int
) {
    init {
        require(maxPlanningGoalChars > 0)
        require(maxPlanningSteps > 0)
        require(maxPlanningStepChars > 0)
        require(maxReasoningPremises > 0)
        require(maxReasoningPremiseChars > 0)
        require(maxReasoningAnalysisChars > 0)
        require(maxReasoningConclusionChars > 0)
        require(maxDecisionOptions > 0)
        require(maxDecisionOptionChars > 0)
        require(maxDecisionRationaleChars > 0)
    }

    companion object {
        fun from(limits: CognitiveRuntimeLimits): CognitiveMaterializationBudgets =
            CognitiveMaterializationBudgets(
                maxPlanningGoalChars = limits.maxPlanningGoalChars,
                maxPlanningSteps = limits.maxPlanningSteps,
                maxPlanningStepChars = limits.maxPlanningStepChars,
                maxReasoningPremises = limits.maxReasoningPremises,
                maxReasoningPremiseChars = limits.maxReasoningPremiseChars,
                maxReasoningAnalysisChars = limits.maxReasoningAnalysisChars,
                maxReasoningConclusionChars = limits.maxReasoningConclusionChars,
                maxDecisionOptions = limits.maxDecisionOptions,
                maxDecisionOptionChars = limits.maxDecisionOptionChars,
                maxDecisionRationaleChars = limits.maxDecisionRationaleChars
            )
    }
}

class CognitiveMaterializationRequest(
    val turn: CognitiveTurnReference,
    val inferenceOutput: String,
    val budgets: CognitiveMaterializationBudgets
) {
    init { require(inferenceOutput.isNotBlank()) { "materialization inference output must not be blank" } }

    override fun toString(): String =
        "CognitiveMaterializationRequest(turn=$turn, inferenceOutput=<redacted:${inferenceOutput.length}>, budgets=$budgets)"
}

class CognitiveMaterializationCandidate(
    val planningGoal: String,
    planningSteps: List<String>,
    reasoningPremises: List<String>,
    val reasoningAnalysis: String,
    val reasoningConclusion: String,
    decisionOptions: List<String>,
    val selectedDecisionOptionIndex: Int,
    val decisionRationale: String
) {
    val planningSteps: List<String> = planningSteps.toList()
    val reasoningPremises: List<String> = reasoningPremises.toList()
    val decisionOptions: List<String> = decisionOptions.toList()

    override fun toString(): String =
        "CognitiveMaterializationCandidate(" +
            "planningGoal=<redacted>, planningSteps=<redacted:${planningSteps.size}>, " +
            "reasoningPremises=<redacted:${reasoningPremises.size}>, reasoningAnalysis=<redacted>, " +
            "reasoningConclusion=<redacted>, decisionOptions=<redacted:${decisionOptions.size}>, " +
            "selectedDecisionOptionIndex=$selectedDecisionOptionIndex, decisionRationale=<redacted>)"
}

enum class CognitiveMaterializationFailure {
    MATERIALIZER_REJECTED,
    MATERIALIZER_FAILED,
    RESOURCE_LIMIT_REJECTED,
    STRUCTURE_REJECTED
}

sealed interface CognitiveMaterializationResult {
    data class Succeeded(val candidate: CognitiveMaterializationCandidate) : CognitiveMaterializationResult
    data class Rejected(val reason: CognitiveMaterializationFailure) : CognitiveMaterializationResult
}

fun interface CognitiveMaterializationPort {
    fun materialize(request: CognitiveMaterializationRequest): CognitiveMaterializationResult
}

enum class CognitiveArtifactIdKind {
    PLANNING_PROPOSAL,
    PLANNING_STEP,
    REASONING_ARTIFACT,
    REASONING_PREMISE,
    DECISION,
    DECISION_OPTION
}

fun interface CognitiveArtifactIdSource {
    fun next(kind: CognitiveArtifactIdKind): String
}

fun interface CognitiveTimestampSource {
    fun now(): Instant
}
