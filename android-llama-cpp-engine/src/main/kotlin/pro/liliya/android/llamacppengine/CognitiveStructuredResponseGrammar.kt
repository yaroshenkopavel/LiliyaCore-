package pro.liliya.android.llamacppengine

import pro.liliya.core.cognitive.CognitiveRuntimeLimits

/**
 * Projects the authoritative Cognitive Runtime response budgets into a llama.cpp GBNF grammar.
 *
 * The grammar intentionally permits a strict subset of protocol value text (printable characters
 * excluding raw backslash/control characters). This keeps native constrained decoding deterministic
 * while every produced envelope remains valid for the Core structured materializer.
 */
internal object CognitiveStructuredResponseGrammar {
    const val ROOT_RULE = "root"
    const val MAX_GRAMMAR_UTF8_BYTES = 262_144

    fun create(limits: CognitiveRuntimeLimits): String {
        val lines = mutableListOf<String>()
        lines += "root ::= " + listOf(
            literal("LILIYA_COGNITIVE_RESPONSE_V1\n"),
            literal("PLANNING_GOAL="), "planning-goal", literal("\n"),
            "planning-block",
            "reasoning-block",
            literal("REASONING_ANALYSIS="), "reasoning-analysis", literal("\n"),
            literal("REASONING_CONCLUSION="), "reasoning-conclusion", literal("\n"),
            "decision-block",
            literal("DECISION_RATIONALE="), "decision-rationale", literal("\n"),
            literal("RESULT_CONTENT="), "result-content", literal("\n"),
            literal("REFLECTION_CONTENT="), "reflection-content", literal("\n"),
            literal("LEARNING_PROPOSAL="), "learning-proposal", literal("\nEND"),
            "terminal-lf"
        ).joinToString(" ")
        lines += "terminal-lf ::= \"\" | ${literal("\n")}" 
        lines += boundedValue("planning-goal", limits.maxPlanningGoalChars)
        lines += boundedValue("planning-step", limits.maxPlanningStepChars)
        lines += boundedValue("reasoning-premise", limits.maxReasoningPremiseChars)
        lines += boundedValue("reasoning-analysis", limits.maxReasoningAnalysisChars)
        lines += boundedValue("reasoning-conclusion", limits.maxReasoningConclusionChars)
        lines += boundedValue("decision-option", limits.maxDecisionOptionChars)
        lines += boundedValue("decision-rationale", limits.maxDecisionRationaleChars)
        lines += boundedValue("result-content", limits.maxResultChars)
        lines += boundedValue("reflection-content", limits.maxReflectionChars)
        lines += boundedValue("learning-proposal", limits.maxLearningProposalChars)
        lines += "value-char ::= [\\x20-\\x5B\\x5D-\\x7E\\u0080-\\uFFFF]"
        lines += "planning-step-line ::= ${literal("PLANNING_STEP=")} planning-step ${literal("\n")}" 
        lines += "reasoning-premise-line ::= ${literal("REASONING_PREMISE=")} reasoning-premise ${literal("\n")}" 
        lines += "decision-option-line ::= ${literal("DECISION_OPTION=")} decision-option ${literal("\n")}" 
        lines += countedBlock(
            ruleName = "planning-block",
            countPrefix = "PLANNING_STEP_COUNT=",
            maxCount = limits.maxPlanningSteps,
            repeatedRule = "planning-step-line"
        )
        lines += countedBlock(
            ruleName = "reasoning-block",
            countPrefix = "REASONING_PREMISE_COUNT=",
            maxCount = limits.maxReasoningPremises,
            repeatedRule = "reasoning-premise-line"
        )
        lines += decisionBlock(limits.maxDecisionOptions)

        val grammar = lines.joinToString("\n")
        require(grammar.toByteArray(Charsets.UTF_8).size <= MAX_GRAMMAR_UTF8_BYTES) {
            "cognitive response grammar exceeds native byte ceiling"
        }
        return grammar
    }

    private fun boundedValue(ruleName: String, maximum: Int): String {
        require(maximum > 0)
        return "$ruleName ::= value-char{1,$maximum}"
    }

    private fun countedBlock(
        ruleName: String,
        countPrefix: String,
        maxCount: Int,
        repeatedRule: String
    ): String {
        require(maxCount > 0)
        val alternatives = (1..maxCount).map { count ->
            buildString {
                append(literal("$countPrefix$count\n"))
                repeat(count) {
                    append(' ')
                    append(repeatedRule)
                }
            }
        }
        return "$ruleName ::= " + alternatives.joinToString(" | ")
    }

    private fun decisionBlock(maxCount: Int): String {
        require(maxCount > 0)
        val alternatives = (1..maxCount).map { count ->
            buildString {
                append(literal("DECISION_OPTION_COUNT=$count\n"))
                repeat(count) {
                    append(" decision-option-line")
                }
                append(' ')
                append(literal("DECISION_SELECTED_INDEX="))
                append(" (")
                append((0 until count).joinToString(" | ") { literal(it.toString()) })
                append(") ")
                append(literal("\n"))
            }
        }
        return "decision-block ::= " + alternatives.joinToString(" | ")
    }

    private fun literal(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }
}
