package pro.liliya.core.cognitive

class CognitiveModelRequestCompilerRequest(
    val inference: CognitiveInferenceRequest,
    val maxPromptChars: Int,
    val responseBudgets: CognitiveStructuredResponseBudgets =
        CognitiveStructuredResponseBudgets.from(CognitiveRuntimeLimits())
) {
    init {
        require(maxPromptChars > 0) { "cognitive model prompt budget must be positive" }
    }

    override fun toString(): String =
        "CognitiveModelRequestCompilerRequest(" +
            "turn=${inference.turn}, " +
            "input=<redacted>, " +
            "context=<redacted:${inference.context.items.size}>, " +
            "maxPromptChars=$maxPromptChars, " +
            "maxOutputChars=${inference.maxOutputChars}, " +
            "responseBudgets=$responseBudgets)"
}

class CognitiveCompiledModelRequest(
    val prompt: String
) {
    override fun toString(): String =
        "CognitiveCompiledModelRequest(prompt=<redacted:${prompt.length}>)"
}

enum class CognitiveModelRequestCompilerFailure {
    COMPILER_REJECTED,
    RESOURCE_LIMIT_REJECTED,
    PROVIDER_FAILED
}

sealed interface CognitiveModelRequestCompilerResult {
    data class Compiled(
        val request: CognitiveCompiledModelRequest
    ) : CognitiveModelRequestCompilerResult

    data class Rejected(
        val reason: CognitiveModelRequestCompilerFailure
    ) : CognitiveModelRequestCompilerResult
}

fun interface CognitiveModelRequestCompilerPort {
    fun compile(request: CognitiveModelRequestCompilerRequest): CognitiveModelRequestCompilerResult
}

/**
 * Platform-neutral deterministic text projection for the model-engine boundary.
 *
 * The compiler preserves the already-authoritative CognitiveContext order and intentionally projects
 * only source kind plus private content. Source IDs/generations remain Cognitive Runtime provenance and
 * are not required by the model prompt. The response instruction is structural only and is bounded as
 * part of the same prompt budget. The provider independently rechecks the final prompt bound.
 */
class DeterministicCognitiveModelRequestCompiler : CognitiveModelRequestCompilerPort {
    override fun compile(
        request: CognitiveModelRequestCompilerRequest
    ): CognitiveModelRequestCompilerResult {
        if (
            request.inference.maxOutputChars < CognitiveStructuredResponseProtocol.minimumEnvelopeChars ||
            request.inference.maxOutputChars > request.responseBudgets.maxOutputChars
        ) {
            return rejected(CognitiveModelRequestCompilerFailure.RESOURCE_LIMIT_REJECTED)
        }

        val builder = StringBuilder()
        if (!appendBounded(builder, HEADER, request.maxPromptChars)) {
            return rejected(CognitiveModelRequestCompilerFailure.RESOURCE_LIMIT_REJECTED)
        }
        if (!appendResponseInstruction(builder, request)) {
            return rejected(CognitiveModelRequestCompilerFailure.RESOURCE_LIMIT_REJECTED)
        }
        if (!appendBounded(builder, "\nINPUT\n", request.maxPromptChars) ||
            !appendBounded(builder, request.inference.input.text, request.maxPromptChars) ||
            !appendBounded(builder, "\nCONTEXT", request.maxPromptChars)
        ) {
            return rejected(CognitiveModelRequestCompilerFailure.RESOURCE_LIMIT_REJECTED)
        }

        request.inference.context.items.forEachIndexed { index, item ->
            val prefix = "\n${index + 1}:${sourceKind(item.source)}\n"
            if (!appendBounded(builder, prefix, request.maxPromptChars) ||
                !appendBounded(builder, item.content, request.maxPromptChars)
            ) {
                return rejected(CognitiveModelRequestCompilerFailure.RESOURCE_LIMIT_REJECTED)
            }
        }

        val prompt = builder.toString()
        if (prompt.isBlank()) {
            return rejected(CognitiveModelRequestCompilerFailure.COMPILER_REJECTED)
        }
        return CognitiveModelRequestCompilerResult.Compiled(
            CognitiveCompiledModelRequest(prompt)
        )
    }

    private fun appendResponseInstruction(
        builder: StringBuilder,
        request: CognitiveModelRequestCompilerRequest
    ): Boolean {
        val budgets = request.responseBudgets
        val lines = listOf(
            "",
            "RESPONSE_PROTOCOL",
            "RESPONSE_VERSION=${CognitiveStructuredResponseProtocol.VERSION}",
            "RULE=OUTPUT_ONLY_PROTOCOL_ENVELOPE_NO_PROSE",
            "RULE=ALL_FIELDS_REQUIRED_IN_EXACT_ORDER",
            "RULE=LIST_RECORDS_REPEAT_EXACTLY_DECLARED_COUNT",
            "RULE=RAW_C0_CONTROL_CHARACTERS_FORBIDDEN_IN_VALUES",
            "RULE=ESCAPES_ONLY_BACKSLASH_NEWLINE_CARRIAGE_RETURN_TAB",
            "ESCAPE_BACKSLASH=\\\\",
            "ESCAPE_NEWLINE=\\n",
            "ESCAPE_CARRIAGE_RETURN=\\r",
            "ESCAPE_TAB=\\t",
            "RULE=OTHER_ESCAPES_FORBIDDEN",
            "RULE=OPTIONAL_SINGLE_TERMINAL_LF_AFTER_END",
            "LIMIT_ENGINE_OUTPUT_CHARS=${request.inference.maxOutputChars}",
            "LIMIT_PROTOCOL_OUTPUT_CHARS=${budgets.maxOutputChars}",
            "LIMIT_PLANNING_GOAL_CHARS=${budgets.maxPlanningGoalChars}",
            "LIMIT_PLANNING_STEPS=${budgets.maxPlanningSteps}",
            "LIMIT_PLANNING_STEP_CHARS=${budgets.maxPlanningStepChars}",
            "LIMIT_REASONING_PREMISES=${budgets.maxReasoningPremises}",
            "LIMIT_REASONING_PREMISE_CHARS=${budgets.maxReasoningPremiseChars}",
            "LIMIT_REASONING_ANALYSIS_CHARS=${budgets.maxReasoningAnalysisChars}",
            "LIMIT_REASONING_CONCLUSION_CHARS=${budgets.maxReasoningConclusionChars}",
            "LIMIT_DECISION_OPTIONS=${budgets.maxDecisionOptions}",
            "LIMIT_DECISION_OPTION_CHARS=${budgets.maxDecisionOptionChars}",
            "LIMIT_DECISION_RATIONALE_CHARS=${budgets.maxDecisionRationaleChars}",
            "LIMIT_RESULT_CHARS=${budgets.maxResultChars}",
            "LIMIT_REFLECTION_CHARS=${budgets.maxReflectionChars}",
            "LIMIT_LEARNING_PROPOSAL_CHARS=${budgets.maxLearningProposalChars}",
            "SCHEMA",
            "PLANNING_GOAL=<escaped>",
            "PLANNING_STEP_COUNT=<int 1..${budgets.maxPlanningSteps}>",
            "PLANNING_STEP=<escaped> repeat exactly PLANNING_STEP_COUNT times",
            "REASONING_PREMISE_COUNT=<int 1..${budgets.maxReasoningPremises}>",
            "REASONING_PREMISE=<escaped> repeat exactly REASONING_PREMISE_COUNT times",
            "REASONING_ANALYSIS=<escaped>",
            "REASONING_CONCLUSION=<escaped>",
            "DECISION_OPTION_COUNT=<int 1..${budgets.maxDecisionOptions}>",
            "DECISION_OPTION=<escaped> repeat exactly DECISION_OPTION_COUNT times",
            "DECISION_SELECTED_INDEX=<int 0..DECISION_OPTION_COUNT-1>",
            "DECISION_RATIONALE=<escaped>",
            "RESULT_CONTENT=<escaped>",
            "REFLECTION_CONTENT=<escaped>",
            "LEARNING_PROPOSAL=<escaped>",
            "END"
        )
        for (line in lines) {
            if (!appendBounded(builder, "\n$line", request.maxPromptChars)) return false
        }
        return true
    }

    private fun rejected(
        failure: CognitiveModelRequestCompilerFailure
    ): CognitiveModelRequestCompilerResult.Rejected =
        CognitiveModelRequestCompilerResult.Rejected(failure)

    private fun appendBounded(
        builder: StringBuilder,
        value: String,
        maximum: Int
    ): Boolean {
        if (value.length > maximum - builder.length) return false
        builder.append(value)
        return true
    }

    private fun sourceKind(source: CognitiveContextSourceReference): String = when (source) {
        is CognitiveContextSourceReference.Memory -> "MEMORY"
        is CognitiveContextSourceReference.Knowledge -> "KNOWLEDGE"
        is CognitiveContextSourceReference.Self -> "SELF"
        is CognitiveContextSourceReference.Personality -> "PERSONALITY"
    }

    private companion object {
        const val HEADER = "LILIYA_COGNITIVE_REQUEST_V1"
    }
}
