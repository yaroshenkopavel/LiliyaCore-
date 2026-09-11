package pro.liliya.core.cognitive

/**
 * Deterministic GBNF projection of the authoritative structured-response budgets.
 *
 * The grammar is intentionally stricter than the text protocol in one place: every value must
 * start with one printable non-space character. The parser already rejects blank values, so this
 * removes a class of model outputs that could never be materialized successfully while preserving
 * the accepted protocol language used by Cognitive Runtime.
 */
object CognitiveStructuredResponseGrammar {
    private const val MAX_GRAMMAR_CHARS = 64 * 1024

    fun compile(budgets: CognitiveStructuredResponseBudgets): String? {
        val grammar = buildString {
            append("root ::= \"")
            append(CognitiveStructuredResponseProtocol.VERSION)
            append("\\n\" \"PLANNING_GOAL=\" planning-goal \"\\n\" ")
            append("planning-block reasoning-block ")
            append("\"REASONING_ANALYSIS=\" reasoning-analysis \"\\n\" ")
            append("\"REASONING_CONCLUSION=\" reasoning-conclusion \"\\n\" ")
            append("decision-block ")
            append("\"DECISION_RATIONALE=\" decision-rationale \"\\n\" ")
            append("\"RESULT_CONTENT=\" result-content \"\\n\" ")
            append("\"REFLECTION_CONTENT=\" reflection-content \"\\n\" ")
            append("\"LEARNING_PROPOSAL=\" learning-proposal \"\\nEND\" (\"\\n\")?\n")

            appendValueRule("planning-goal", budgets.maxPlanningGoalChars)
            appendValueRule("planning-step", budgets.maxPlanningStepChars)
            appendValueRule("reasoning-premise", budgets.maxReasoningPremiseChars)
            appendValueRule("reasoning-analysis", budgets.maxReasoningAnalysisChars)
            appendValueRule("reasoning-conclusion", budgets.maxReasoningConclusionChars)
            appendValueRule("decision-option", budgets.maxDecisionOptionChars)
            appendValueRule("decision-rationale", budgets.maxDecisionRationaleChars)
            appendValueRule("result-content", budgets.maxResultChars)
            appendValueRule("reflection-content", budgets.maxReflectionChars)
            appendValueRule("learning-proposal", budgets.maxLearningProposalChars)

            append("planning-step-line ::= \"PLANNING_STEP=\" planning-step \"\\n\"\n")
            append("reasoning-premise-line ::= \"REASONING_PREMISE=\" reasoning-premise \"\\n\"\n")
            append("decision-option-line ::= \"DECISION_OPTION=\" decision-option \"\\n\"\n")

            appendCountedBlock(
                rule = "planning-block",
                variantPrefix = "planning-count",
                countKey = "PLANNING_STEP_COUNT",
                itemRule = "planning-step-line",
                maximum = budgets.maxPlanningSteps
            )
            appendCountedBlock(
                rule = "reasoning-block",
                variantPrefix = "reasoning-count",
                countKey = "REASONING_PREMISE_COUNT",
                itemRule = "reasoning-premise-line",
                maximum = budgets.maxReasoningPremises
            )
            appendDecisionBlock(budgets.maxDecisionOptions)

            append("value-start ::= [\\x21-\\x5B\\x5D-\\x7E\\u0080-\\uFFFF] | escaped-char\n")
            append("value-char ::= [\\x20-\\x5B\\x5D-\\x7E\\u0080-\\uFFFF] | escaped-char\n")
            append("escaped-char ::= \"\\\\\\\\\" | \"\\\\n\" | \"\\\\r\" | \"\\\\t\"\n")
        }
        return grammar.takeIf { it.length <= MAX_GRAMMAR_CHARS }
    }

    private fun StringBuilder.appendValueRule(rule: String, maximum: Int) {
        append(rule)
        append(" ::= value-start")
        if (maximum > 1) {
            append(" value-char{0,")
            append(maximum - 1)
            append('}')
        }
        append('\n')
    }

    private fun StringBuilder.appendCountedBlock(
        rule: String,
        variantPrefix: String,
        countKey: String,
        itemRule: String,
        maximum: Int
    ) {
        append(rule)
        append(" ::= ")
        for (count in 1..maximum) {
            if (count > 1) append(" | ")
            append(variantPrefix)
            append('-')
            append(count)
        }
        append('\n')

        for (count in 1..maximum) {
            append(variantPrefix)
            append('-')
            append(count)
            append(" ::= \"")
            append(countKey)
            append('=')
            append(count)
            append("\\n\" ")
            append(itemRule)
            append('{')
            append(count)
            append("}\n")
        }
    }

    private fun StringBuilder.appendDecisionBlock(maximum: Int) {
        append("decision-block ::= ")
        for (count in 1..maximum) {
            if (count > 1) append(" | ")
            append("decision-count-")
            append(count)
        }
        append('\n')

        for (count in 1..maximum) {
            append("decision-count-")
            append(count)
            append(" ::= \"DECISION_OPTION_COUNT=")
            append(count)
            append("\\n\" decision-option-line{")
            append(count)
            append("} \"DECISION_SELECTED_INDEX=\" (")
            for (index in 0 until count) {
                if (index > 0) append(" | ")
                append('"')
                append(index)
                append('"')
            }
            append(") \"\\n\"\n")
        }
    }
}
