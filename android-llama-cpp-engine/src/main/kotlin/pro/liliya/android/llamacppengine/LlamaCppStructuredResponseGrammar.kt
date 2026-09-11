package pro.liliya.android.llamacppengine

import pro.liliya.core.cognitive.CognitiveStructuredResponseBudgets
import pro.liliya.core.cognitive.CognitiveStructuredResponseProtocol

/**
 * Deterministic GBNF projection of the Core structured-response contract.
 *
 * The grammar constrains the exact field order, count-dependent repeated records and selected
 * decision index before greedy sampling chooses a token. Field repetitions are bounded in decoded
 * UTF-16 code units for the BMP, matching the Core parser's String-length accounting. Astral code
 * points are intentionally excluded at this boundary because one code point would consume two
 * Kotlin chars and could otherwise bypass the Core field budgets.
 */
internal object LlamaCppStructuredResponseGrammar {
    const val MAX_GRAMMAR_UTF8_BYTES: Int = 65_536

    fun compile(budgets: CognitiveStructuredResponseBudgets): String {
        val grammar = buildString {
            append("root ::= ")
            append(literal(CognitiveStructuredResponseProtocol.VERSION + "\n"))
            append(" ")
            append(literal("PLANNING_GOAL="))
            append(" planning-goal ")
            append(literal("\n"))
            append(" planning-block reasoning-block ")
            append(literal("REASONING_ANALYSIS="))
            append(" reasoning-analysis ")
            append(literal("\nREASONING_CONCLUSION="))
            append(" reasoning-conclusion ")
            append(literal("\n"))
            append(" decision-block ")
            append(literal("DECISION_RATIONALE="))
            append(" decision-rationale ")
            append(literal("\nRESULT_CONTENT="))
            append(" result-content ")
            append(literal("\nREFLECTION_CONTENT="))
            append(" reflection-content ")
            append(literal("\nLEARNING_PROPOSAL="))
            append(" learning-proposal ")
            append(literal("\nEND"))
            append(" ")
            append(literal("\n"))
            append("?\n")

            appendFieldRule("planning-goal", budgets.maxPlanningGoalChars)
            appendFieldRule("planning-step", budgets.maxPlanningStepChars)
            appendFieldRule("reasoning-premise", budgets.maxReasoningPremiseChars)
            appendFieldRule("reasoning-analysis", budgets.maxReasoningAnalysisChars)
            appendFieldRule("reasoning-conclusion", budgets.maxReasoningConclusionChars)
            appendFieldRule("decision-option", budgets.maxDecisionOptionChars)
            appendFieldRule("decision-rationale", budgets.maxDecisionRationaleChars)
            appendFieldRule("result-content", budgets.maxResultChars)
            appendFieldRule("reflection-content", budgets.maxReflectionChars)
            appendFieldRule("learning-proposal", budgets.maxLearningProposalChars)

            append("planning-step-line ::= ")
            append(literal("PLANNING_STEP="))
            append(" planning-step ")
            append(literal("\n"))
            append("\n")
            appendCountBlock(
                ruleName = "planning-block",
                countKey = "PLANNING_STEP_COUNT",
                repeatedRule = "planning-step-line",
                maximum = budgets.maxPlanningSteps
            )

            append("reasoning-premise-line ::= ")
            append(literal("REASONING_PREMISE="))
            append(" reasoning-premise ")
            append(literal("\n"))
            append("\n")
            appendCountBlock(
                ruleName = "reasoning-block",
                countKey = "REASONING_PREMISE_COUNT",
                repeatedRule = "reasoning-premise-line",
                maximum = budgets.maxReasoningPremises
            )

            append("decision-option-line ::= ")
            append(literal("DECISION_OPTION="))
            append(" decision-option ")
            append(literal("\n"))
            append("\n")
            appendDecisionBlock(budgets.maxDecisionOptions)

            // One grammar value unit decodes to exactly one Kotlin UTF-16 char.
            append("value-start ::= [\\x21-\\x5B\\x5D-\\x7E\\u0080-\\uFFFF] | ")
            append(literal("\\\\"))
            append("\n")
            append("value-char ::= [\\x20-\\x5B\\x5D-\\x7E\\u0080-\\uFFFF] | escaped-char\n")
            append("escaped-char ::= ")
            append(literal("\\\\"))
            append(" | ")
            append(literal("\\n"))
            append(" | ")
            append(literal("\\r"))
            append(" | ")
            append(literal("\\t"))
            append("\n")
        }

        require(grammar.toByteArray(Charsets.UTF_8).size <= MAX_GRAMMAR_UTF8_BYTES) {
            "llama.cpp structured-response grammar exceeds native bound"
        }
        return grammar
    }

    private fun StringBuilder.appendFieldRule(name: String, maximum: Int) {
        append(name).append(" ::= value-start")
        if (maximum > 1) {
            append(" value-char{0,").append(maximum - 1).append("}")
        }
        append("\n")
    }

    private fun StringBuilder.appendCountBlock(
        ruleName: String,
        countKey: String,
        repeatedRule: String,
        maximum: Int
    ) {
        append(ruleName).append(" ::= ")
        for (count in 1..maximum) {
            if (count > 1) append(" | ")
            append(literal("$countKey=$count\n"))
            append(" ").append(repeatedRule).append("{").append(count).append("}")
        }
        append("\n")
    }

    private fun StringBuilder.appendDecisionBlock(maximum: Int) {
        append("decision-block ::= ")
        for (count in 1..maximum) {
            if (count > 1) append(" | ")
            append(literal("DECISION_OPTION_COUNT=$count\n"))
            append(" decision-option-line{").append(count).append("} ")
            append(literal("DECISION_SELECTED_INDEX="))
            append(" (")
            for (index in 0 until count) {
                if (index > 0) append(" | ")
                append(literal(index.toString()))
            }
            append(") ").append(literal("\n"))
        }
        append("\n")
    }

    private fun literal(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}
