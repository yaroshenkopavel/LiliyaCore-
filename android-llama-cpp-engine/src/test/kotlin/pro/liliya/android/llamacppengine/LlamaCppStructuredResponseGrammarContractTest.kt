package pro.liliya.android.llamacppengine

import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import pro.liliya.core.cognitive.CognitiveStructuredResponseBudgets

class LlamaCppStructuredResponseGrammarContractTest {
    @Test
    fun grammar_projects_exact_order_count_bounds_and_selected_index_relationship() {
        val grammar = LlamaCppStructuredResponseGrammar.compile(budgets())

        assertTrue(grammar.startsWith("root ::= \"LILIYA_COGNITIVE_RESPONSE_V1\\n\""))
        assertContains(grammar, "\"PLANNING_STEP_COUNT=1\\n\" planning-step-line{1}")
        assertContains(grammar, "\"PLANNING_STEP_COUNT=2\\n\" planning-step-line{2}")
        assertContains(grammar, "\"REASONING_PREMISE_COUNT=2\\n\" reasoning-premise-line{2}")
        assertContains(
            grammar,
            "\"DECISION_OPTION_COUNT=2\\n\" decision-option-line{2} " +
                "\"DECISION_SELECTED_INDEX=\" (\"0\" | \"1\") \"\\n\""
        )
        assertContains(grammar, "planning-goal ::= value-start value-char{0,4}")
        assertContains(grammar, "result-content ::= value-start value-char{0,10}")
        assertContains(grammar, "\"\\nEND\" \"\\n\"?")
        assertTrue(grammar.toByteArray().size <= LlamaCppStructuredResponseGrammar.MAX_GRAMMAR_UTF8_BYTES)
    }

    @Test
    fun grammar_never_permits_raw_c0_backslash_or_astral_value_units() {
        val grammar = LlamaCppStructuredResponseGrammar.compile(budgets())

        assertContains(grammar, "value-char ::= [\\x20-\\x5B\\x5D-\\x7E\\u0080-\\uFFFF] | escaped-char")
        assertContains(
            grammar,
            """escaped-char ::= "\\\\" | "\\n" | "\\r" | "\\t""""
        )
        assertFalse(grammar.contains("\\U00010000"))
    }

    private fun budgets() = CognitiveStructuredResponseBudgets(
        maxOutputChars = 512,
        maxPlanningGoalChars = 5,
        maxPlanningSteps = 2,
        maxPlanningStepChars = 6,
        maxReasoningPremises = 2,
        maxReasoningPremiseChars = 7,
        maxReasoningAnalysisChars = 8,
        maxReasoningConclusionChars = 9,
        maxDecisionOptions = 2,
        maxDecisionOptionChars = 10,
        maxDecisionRationaleChars = 10,
        maxResultChars = 11,
        maxReflectionChars = 12,
        maxLearningProposalChars = 13
    )
}
