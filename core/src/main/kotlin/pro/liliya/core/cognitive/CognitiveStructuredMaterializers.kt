package pro.liliya.core.cognitive

class StructuredCognitiveMaterializationPort(
    responseBudgets: CognitiveStructuredResponseBudgets
) : CognitiveMaterializationPort {
    private val parser = CognitiveStructuredResponseParser(responseBudgets)

    override fun materialize(request: CognitiveMaterializationRequest): CognitiveMaterializationResult {
        val response = when (val parsed = parser.parse(request.inferenceOutput)) {
            is CognitiveStructuredResponseParseResult.Parsed -> parsed.response
            is CognitiveStructuredResponseParseResult.Rejected -> {
                return CognitiveMaterializationResult.Rejected(parsed.reason.toGenerationFailure())
            }
        }

        if (!withinGenerationBudgets(response, request.budgets)) {
            return CognitiveMaterializationResult.Rejected(
                CognitiveMaterializationFailure.RESOURCE_LIMIT_REJECTED
            )
        }

        return CognitiveMaterializationResult.Succeeded(
            CognitiveMaterializationCandidate(
                planningGoal = response.planningGoal,
                planningSteps = response.planningSteps,
                reasoningPremises = response.reasoningPremises,
                reasoningAnalysis = response.reasoningAnalysis,
                reasoningConclusion = response.reasoningConclusion,
                decisionOptions = response.decisionOptions,
                selectedDecisionOptionIndex = response.selectedDecisionOptionIndex,
                decisionRationale = response.decisionRationale
            )
        )
    }

    private fun withinGenerationBudgets(
        response: CognitiveStructuredResponse,
        budgets: CognitiveMaterializationBudgets
    ): Boolean =
        response.planningGoal.length <= budgets.maxPlanningGoalChars &&
            response.planningSteps.size <= budgets.maxPlanningSteps &&
            response.planningSteps.all { it.length <= budgets.maxPlanningStepChars } &&
            response.reasoningPremises.size <= budgets.maxReasoningPremises &&
            response.reasoningPremises.all { it.length <= budgets.maxReasoningPremiseChars } &&
            response.reasoningAnalysis.length <= budgets.maxReasoningAnalysisChars &&
            response.reasoningConclusion.length <= budgets.maxReasoningConclusionChars &&
            response.decisionOptions.size <= budgets.maxDecisionOptions &&
            response.decisionOptions.all { it.length <= budgets.maxDecisionOptionChars } &&
            response.decisionRationale.length <= budgets.maxDecisionRationaleChars
}

class StructuredCognitiveOutcomeMaterializationPort(
    responseBudgets: CognitiveStructuredResponseBudgets
) : CognitiveOutcomeMaterializationPort {
    private val parser = CognitiveStructuredResponseParser(responseBudgets)

    override fun materialize(
        request: CognitiveOutcomeMaterializationRequest
    ): CognitiveOutcomeMaterializationResult {
        val response = when (val parsed = parser.parse(request.inferenceOutput)) {
            is CognitiveStructuredResponseParseResult.Parsed -> parsed.response
            is CognitiveStructuredResponseParseResult.Rejected -> {
                return CognitiveOutcomeMaterializationResult.Rejected(parsed.reason.toOutcomeFailure())
            }
        }

        if (!matchesAuthoritativeCognition(response, request)) {
            return CognitiveOutcomeMaterializationResult.Rejected(
                CognitiveOutcomeMaterializationFailure.STRUCTURE_REJECTED
            )
        }

        if (
            response.resultContent.length > request.budgets.maxResultChars ||
            response.reflectionContent.length > request.budgets.maxReflectionChars ||
            response.learningProposal.length > request.budgets.maxLearningProposalChars
        ) {
            return CognitiveOutcomeMaterializationResult.Rejected(
                CognitiveOutcomeMaterializationFailure.RESOURCE_LIMIT_REJECTED
            )
        }

        return CognitiveOutcomeMaterializationResult.Succeeded(
            CognitiveOutcomeCandidate(
                resultContent = response.resultContent,
                reflectionContent = response.reflectionContent,
                learningProposal = response.learningProposal
            )
        )
    }

    private fun matchesAuthoritativeCognition(
        response: CognitiveStructuredResponse,
        request: CognitiveOutcomeMaterializationRequest
    ): Boolean {
        val planning = request.planning.proposal
        if (response.planningGoal != planning.goal) return false
        if (response.planningSteps != planning.steps.map { it.description }) return false

        val reasoning = request.reasoning.artifact
        if (response.reasoningPremises != reasoning.premises.map { it.statement }) return false
        if (response.reasoningAnalysis != reasoning.analysis) return false
        if (response.reasoningConclusion != reasoning.conclusion) return false

        val decision = request.decision.decision
        if (response.decisionOptions != decision.options.map { it.description }) return false
        val selectedIndex = decision.options.indexOfFirst { it.id == decision.selectedOptionId }
        if (selectedIndex < 0 || response.selectedDecisionOptionIndex != selectedIndex) return false
        if (response.decisionRationale != decision.rationale) return false

        return true
    }
}

private fun CognitiveStructuredResponseFailure.toGenerationFailure(): CognitiveMaterializationFailure = when (this) {
    CognitiveStructuredResponseFailure.OUTPUT_LIMIT_REJECTED,
    CognitiveStructuredResponseFailure.COUNT_REJECTED,
    CognitiveStructuredResponseFailure.FIELD_LIMIT_REJECTED ->
        CognitiveMaterializationFailure.RESOURCE_LIMIT_REJECTED

    CognitiveStructuredResponseFailure.VERSION_REJECTED,
    CognitiveStructuredResponseFailure.STRUCTURE_REJECTED,
    CognitiveStructuredResponseFailure.ESCAPE_REJECTED,
    CognitiveStructuredResponseFailure.CONTROL_CHARACTER_REJECTED,
    CognitiveStructuredResponseFailure.TRAILING_DATA_REJECTED ->
        CognitiveMaterializationFailure.STRUCTURE_REJECTED
}

private fun CognitiveStructuredResponseFailure.toOutcomeFailure(): CognitiveOutcomeMaterializationFailure = when (this) {
    CognitiveStructuredResponseFailure.OUTPUT_LIMIT_REJECTED,
    CognitiveStructuredResponseFailure.COUNT_REJECTED,
    CognitiveStructuredResponseFailure.FIELD_LIMIT_REJECTED ->
        CognitiveOutcomeMaterializationFailure.RESOURCE_LIMIT_REJECTED

    CognitiveStructuredResponseFailure.VERSION_REJECTED,
    CognitiveStructuredResponseFailure.STRUCTURE_REJECTED,
    CognitiveStructuredResponseFailure.ESCAPE_REJECTED,
    CognitiveStructuredResponseFailure.CONTROL_CHARACTER_REJECTED,
    CognitiveStructuredResponseFailure.TRAILING_DATA_REJECTED ->
        CognitiveOutcomeMaterializationFailure.STRUCTURE_REJECTED
}
