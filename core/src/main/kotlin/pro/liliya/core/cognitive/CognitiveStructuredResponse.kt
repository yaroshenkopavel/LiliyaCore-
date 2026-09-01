package pro.liliya.core.cognitive

data class CognitiveStructuredResponseBudgets(
    val maxOutputChars: Int,
    val maxPlanningGoalChars: Int,
    val maxPlanningSteps: Int,
    val maxPlanningStepChars: Int,
    val maxReasoningPremises: Int,
    val maxReasoningPremiseChars: Int,
    val maxReasoningAnalysisChars: Int,
    val maxReasoningConclusionChars: Int,
    val maxDecisionOptions: Int,
    val maxDecisionOptionChars: Int,
    val maxDecisionRationaleChars: Int,
    val maxResultChars: Int,
    val maxReflectionChars: Int,
    val maxLearningProposalChars: Int
) {
    init {
        require(maxOutputChars > 0)
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
        require(maxResultChars > 0)
        require(maxReflectionChars > 0)
        require(maxLearningProposalChars > 0)
    }

    companion object {
        fun from(limits: CognitiveRuntimeLimits): CognitiveStructuredResponseBudgets =
            CognitiveStructuredResponseBudgets(
                maxOutputChars = limits.maxInferenceOutputChars,
                maxPlanningGoalChars = limits.maxPlanningGoalChars,
                maxPlanningSteps = limits.maxPlanningSteps,
                maxPlanningStepChars = limits.maxPlanningStepChars,
                maxReasoningPremises = limits.maxReasoningPremises,
                maxReasoningPremiseChars = limits.maxReasoningPremiseChars,
                maxReasoningAnalysisChars = limits.maxReasoningAnalysisChars,
                maxReasoningConclusionChars = limits.maxReasoningConclusionChars,
                maxDecisionOptions = limits.maxDecisionOptions,
                maxDecisionOptionChars = limits.maxDecisionOptionChars,
                maxDecisionRationaleChars = limits.maxDecisionRationaleChars,
                maxResultChars = limits.maxResultChars,
                maxReflectionChars = limits.maxReflectionChars,
                maxLearningProposalChars = limits.maxLearningProposalChars
            )
    }
}

class CognitiveStructuredResponse internal constructor(
    val planningGoal: String,
    planningSteps: List<String>,
    reasoningPremises: List<String>,
    val reasoningAnalysis: String,
    val reasoningConclusion: String,
    decisionOptions: List<String>,
    val selectedDecisionOptionIndex: Int,
    val decisionRationale: String,
    val resultContent: String,
    val reflectionContent: String,
    val learningProposal: String
) {
    val planningSteps: List<String> = planningSteps.toList()
    val reasoningPremises: List<String> = reasoningPremises.toList()
    val decisionOptions: List<String> = decisionOptions.toList()

    override fun toString(): String =
        "CognitiveStructuredResponse(" +
            "planningGoal=<redacted:${planningGoal.length}>, " +
            "planningSteps=<redacted:${planningSteps.size}>, " +
            "reasoningPremises=<redacted:${reasoningPremises.size}>, " +
            "reasoningAnalysis=<redacted:${reasoningAnalysis.length}>, " +
            "reasoningConclusion=<redacted:${reasoningConclusion.length}>, " +
            "decisionOptions=<redacted:${decisionOptions.size}>, " +
            "selectedDecisionOptionIndex=$selectedDecisionOptionIndex, " +
            "decisionRationale=<redacted:${decisionRationale.length}>, " +
            "resultContent=<redacted:${resultContent.length}>, " +
            "reflectionContent=<redacted:${reflectionContent.length}>, " +
            "learningProposal=<redacted:${learningProposal.length}>)"
}

enum class CognitiveStructuredResponseFailure {
    OUTPUT_LIMIT_REJECTED,
    VERSION_REJECTED,
    STRUCTURE_REJECTED,
    COUNT_REJECTED,
    FIELD_LIMIT_REJECTED,
    ESCAPE_REJECTED,
    CONTROL_CHARACTER_REJECTED,
    TRAILING_DATA_REJECTED
}

sealed interface CognitiveStructuredResponseParseResult {
    data class Parsed(
        val response: CognitiveStructuredResponse
    ) : CognitiveStructuredResponseParseResult

    data class Rejected(
        val reason: CognitiveStructuredResponseFailure
    ) : CognitiveStructuredResponseParseResult
}

object CognitiveStructuredResponseProtocol {
    const val VERSION = "LILIYA_COGNITIVE_RESPONSE_V1"

    val minimumEnvelopeChars: Int = buildString {
        append(VERSION).append('\n')
        append("PLANNING_GOAL=x\n")
        append("PLANNING_STEP_COUNT=1\n")
        append("PLANNING_STEP=x\n")
        append("REASONING_PREMISE_COUNT=1\n")
        append("REASONING_PREMISE=x\n")
        append("REASONING_ANALYSIS=x\n")
        append("REASONING_CONCLUSION=x\n")
        append("DECISION_OPTION_COUNT=1\n")
        append("DECISION_OPTION=x\n")
        append("DECISION_SELECTED_INDEX=0\n")
        append("DECISION_RATIONALE=x\n")
        append("RESULT_CONTENT=x\n")
        append("REFLECTION_CONTENT=x\n")
        append("LEARNING_PROPOSAL=x\n")
        append("END")
    }.length
}

class CognitiveStructuredResponseParser(
    private val budgets: CognitiveStructuredResponseBudgets
) {
    fun parse(output: String): CognitiveStructuredResponseParseResult = try {
        parseExact(output)
    } catch (failure: ParseRejected) {
        CognitiveStructuredResponseParseResult.Rejected(failure.reason)
    }

    private fun parseExact(output: String): CognitiveStructuredResponseParseResult.Parsed {
        if (output.length > budgets.maxOutputChars) {
            fail(CognitiveStructuredResponseFailure.OUTPUT_LIMIT_REJECTED)
        }
        if (output.length < CognitiveStructuredResponseProtocol.minimumEnvelopeChars) {
            fail(CognitiveStructuredResponseFailure.STRUCTURE_REJECTED)
        }

        val body = if (output.endsWith('\n')) output.dropLast(1) else output
        if (body.endsWith('\n')) {
            fail(CognitiveStructuredResponseFailure.TRAILING_DATA_REJECTED)
        }
        val lines = body.split('\n')
        val maximumLines = FIXED_RECORD_COUNT +
            budgets.maxPlanningSteps +
            budgets.maxReasoningPremises +
            budgets.maxDecisionOptions
        if (lines.size > maximumLines) {
            fail(CognitiveStructuredResponseFailure.STRUCTURE_REJECTED)
        }

        var index = 0
        fun nextLine(): String? = lines.getOrNull(index++)

        if (nextLine() != CognitiveStructuredResponseProtocol.VERSION) {
            fail(CognitiveStructuredResponseFailure.VERSION_REJECTED)
        }

        val planningGoal = decodeField(
            nextLine(),
            "PLANNING_GOAL",
            budgets.maxPlanningGoalChars
        )

        val planningStepCount = parseCount(
            nextLine(),
            "PLANNING_STEP_COUNT",
            budgets.maxPlanningSteps
        )
        val planningSteps = ArrayList<String>(planningStepCount)
        repeat(planningStepCount) {
            planningSteps += decodeField(
                nextLine(),
                "PLANNING_STEP",
                budgets.maxPlanningStepChars
            )
        }

        val reasoningPremiseCount = parseCount(
            nextLine(),
            "REASONING_PREMISE_COUNT",
            budgets.maxReasoningPremises
        )
        val reasoningPremises = ArrayList<String>(reasoningPremiseCount)
        repeat(reasoningPremiseCount) {
            reasoningPremises += decodeField(
                nextLine(),
                "REASONING_PREMISE",
                budgets.maxReasoningPremiseChars
            )
        }

        val reasoningAnalysis = decodeField(
            nextLine(),
            "REASONING_ANALYSIS",
            budgets.maxReasoningAnalysisChars
        )
        val reasoningConclusion = decodeField(
            nextLine(),
            "REASONING_CONCLUSION",
            budgets.maxReasoningConclusionChars
        )

        val decisionOptionCount = parseCount(
            nextLine(),
            "DECISION_OPTION_COUNT",
            budgets.maxDecisionOptions
        )
        val decisionOptions = ArrayList<String>(decisionOptionCount)
        repeat(decisionOptionCount) {
            decisionOptions += decodeField(
                nextLine(),
                "DECISION_OPTION",
                budgets.maxDecisionOptionChars
            )
        }

        val selectedIndex = parseUnsignedIntField(
            nextLine(),
            "DECISION_SELECTED_INDEX"
        )
        if (selectedIndex !in decisionOptions.indices) {
            fail(CognitiveStructuredResponseFailure.COUNT_REJECTED)
        }

        val decisionRationale = decodeField(
            nextLine(),
            "DECISION_RATIONALE",
            budgets.maxDecisionRationaleChars
        )
        val resultContent = decodeField(
            nextLine(),
            "RESULT_CONTENT",
            budgets.maxResultChars
        )
        val reflectionContent = decodeField(
            nextLine(),
            "REFLECTION_CONTENT",
            budgets.maxReflectionChars
        )
        val learningProposal = decodeField(
            nextLine(),
            "LEARNING_PROPOSAL",
            budgets.maxLearningProposalChars
        )

        if (nextLine() != "END") {
            fail(CognitiveStructuredResponseFailure.STRUCTURE_REJECTED)
        }
        if (index != lines.size) {
            fail(CognitiveStructuredResponseFailure.TRAILING_DATA_REJECTED)
        }

        return CognitiveStructuredResponseParseResult.Parsed(
            CognitiveStructuredResponse(
                planningGoal = planningGoal,
                planningSteps = planningSteps,
                reasoningPremises = reasoningPremises,
                reasoningAnalysis = reasoningAnalysis,
                reasoningConclusion = reasoningConclusion,
                decisionOptions = decisionOptions,
                selectedDecisionOptionIndex = selectedIndex,
                decisionRationale = decisionRationale,
                resultContent = resultContent,
                reflectionContent = reflectionContent,
                learningProposal = learningProposal
            )
        )
    }

    private fun parseCount(line: String?, key: String, maximum: Int): Int {
        val value = parseUnsignedIntField(line, key)
        if (value !in 1..maximum) {
            fail(CognitiveStructuredResponseFailure.COUNT_REJECTED)
        }
        return value
    }

    private fun parseUnsignedIntField(line: String?, key: String): Int {
        val raw = rawValue(line, key)
        if (raw.isEmpty() || raw.any { it !in '0'..'9' }) {
            fail(CognitiveStructuredResponseFailure.COUNT_REJECTED)
        }
        return raw.toIntOrNull()
            ?: fail(CognitiveStructuredResponseFailure.COUNT_REJECTED)
    }

    private fun decodeField(line: String?, key: String, maximum: Int): String {
        val raw = rawValue(line, key)
        val builder = StringBuilder(minOf(raw.length, maximum))
        var cursor = 0
        while (cursor < raw.length) {
            val current = raw[cursor]
            if (current == '\\') {
                if (cursor + 1 >= raw.length) {
                    fail(CognitiveStructuredResponseFailure.ESCAPE_REJECTED)
                }
                val decoded = when (raw[cursor + 1]) {
                    '\\' -> '\\'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    else -> fail(CognitiveStructuredResponseFailure.ESCAPE_REJECTED)
                }
                if (builder.length >= maximum) {
                    fail(CognitiveStructuredResponseFailure.FIELD_LIMIT_REJECTED)
                }
                builder.append(decoded)
                cursor += 2
                continue
            }

            if (current.code in 0..31 || current.code == 127) {
                fail(CognitiveStructuredResponseFailure.CONTROL_CHARACTER_REJECTED)
            }
            if (builder.length >= maximum) {
                fail(CognitiveStructuredResponseFailure.FIELD_LIMIT_REJECTED)
            }
            builder.append(current)
            cursor += 1
        }

        val value = builder.toString()
        if (value.isBlank()) {
            fail(CognitiveStructuredResponseFailure.STRUCTURE_REJECTED)
        }
        return value
    }

    private fun rawValue(line: String?, key: String): String {
        if (line == null) {
            fail(CognitiveStructuredResponseFailure.STRUCTURE_REJECTED)
        }
        val prefix = "$key="
        if (!line.startsWith(prefix)) {
            fail(CognitiveStructuredResponseFailure.STRUCTURE_REJECTED)
        }
        return line.substring(prefix.length)
    }

    private fun fail(reason: CognitiveStructuredResponseFailure): Nothing =
        throw ParseRejected(reason)

    private class ParseRejected(
        val reason: CognitiveStructuredResponseFailure
    ) : RuntimeException(null, null, false, false)

    private companion object {
        const val FIXED_RECORD_COUNT = 13
    }
}
