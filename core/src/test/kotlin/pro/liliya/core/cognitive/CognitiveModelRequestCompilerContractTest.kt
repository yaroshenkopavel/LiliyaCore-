package pro.liliya.core.cognitive

import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CognitiveModelRequestCompilerContractTest {
    private val budgets = CognitiveStructuredResponseBudgets.from(CognitiveRuntimeLimits())

    private fun inferenceRequest(
        contextItems: List<CognitiveContextItem> = emptyList(),
        maxOutputChars: Int = 4_096
    ): CognitiveInferenceRequest {
        val turn = CognitiveTurnReference(
            id = CognitiveTurnId("private-turn-id"),
            generation = CognitiveTurnGeneration(1)
        )
        return CognitiveInferenceRequest(
            turn = turn,
            input = CognitiveInput("private input"),
            context = CognitiveContextSnapshot(turn, contextItems),
            maxOutputChars = maxOutputChars
        )
    }

    @Test
    fun compiler_request_requires_positive_prompt_budget_and_redacts_private_content() {
        val inference = inferenceRequest()
        assertFailsWith<IllegalArgumentException> {
            CognitiveModelRequestCompilerRequest(inference, 0, budgets)
        }

        val request = CognitiveModelRequestCompilerRequest(
            inference = inference,
            maxPromptChars = 4_096,
            responseBudgets = budgets
        )
        val rendered = request.toString()
        assertFalse(rendered.contains("private input"))
        assertFalse(rendered.contains("private-turn-id"))
        assertTrue(rendered.contains("maxPromptChars=4096"))
        assertTrue(rendered.contains("maxOutputChars=4096"))
        assertTrue(rendered.contains("maxPlanningSteps=${budgets.maxPlanningSteps}"))
    }

    @Test
    fun compiled_request_redacts_prompt_and_can_be_malformed_for_independent_provider_validation() {
        val compiled = CognitiveCompiledModelRequest("")
        assertEquals("", compiled.prompt)
        assertFalse(compiled.toString().contains("private prompt"))
        assertTrue(compiled.toString().contains("redacted"))
    }

    @Test
    fun compiler_port_returns_structural_result_without_model_access() {
        val compiler = CognitiveModelRequestCompilerPort { request ->
            CognitiveModelRequestCompilerResult.Compiled(
                CognitiveCompiledModelRequest("compiled:${request.maxPromptChars}")
            )
        }

        val result = assertIs<CognitiveModelRequestCompilerResult.Compiled>(
            compiler.compile(
                CognitiveModelRequestCompilerRequest(
                    inference = inferenceRequest(),
                    maxPromptChars = 4_096,
                    responseBudgets = budgets
                )
            )
        )
        assertEquals("compiled:4096", result.request.prompt)
    }

    @Test
    fun deterministic_compiler_instructs_exact_v1_schema_and_numeric_budgets_before_private_input() {
        val compiler = DeterministicCognitiveModelRequestCompiler()
        val result = assertIs<CognitiveModelRequestCompilerResult.Compiled>(
            compiler.compile(
                CognitiveModelRequestCompilerRequest(
                    inference = inferenceRequest(),
                    maxPromptChars = 16_384,
                    responseBudgets = budgets
                )
            )
        )

        val prompt = result.request.prompt
        assertTrue(prompt.startsWith("LILIYA_COGNITIVE_REQUEST_V1\n\nRESPONSE_PROTOCOL"))
        assertTrue(prompt.contains("RESPONSE_VERSION=${CognitiveStructuredResponseProtocol.VERSION}"))
        assertTrue(prompt.contains("LIMIT_ENGINE_OUTPUT_CHARS=4096"))
        assertTrue(prompt.contains("LIMIT_PROTOCOL_OUTPUT_CHARS=${budgets.maxOutputChars}"))
        assertTrue(prompt.contains("LIMIT_PLANNING_STEPS=${budgets.maxPlanningSteps}"))
        assertTrue(prompt.contains("LIMIT_REASONING_PREMISES=${budgets.maxReasoningPremises}"))
        assertTrue(prompt.contains("LIMIT_DECISION_OPTIONS=${budgets.maxDecisionOptions}"))
        assertTrue(prompt.contains("LIMIT_RESULT_CHARS=${budgets.maxResultChars}"))
        assertTrue(prompt.contains("RULE=OUTPUT_ONLY_PROTOCOL_ENVELOPE_NO_PROSE"))
        assertTrue(prompt.contains("RULE=LIST_RECORDS_REPEAT_EXACTLY_DECLARED_COUNT"))
        assertTrue(prompt.contains("RULE=RAW_C0_CONTROL_CHARACTERS_FORBIDDEN_IN_VALUES"))
        assertTrue(prompt.contains("RULE=OPTIONAL_SINGLE_TERMINAL_LF_AFTER_END"))
        assertTrue(prompt.contains("ESCAPE_BACKSLASH=\\\\"))
        assertTrue(prompt.contains("ESCAPE_NEWLINE=\\n"))
        assertTrue(prompt.contains("ESCAPE_CARRIAGE_RETURN=\\r"))
        assertTrue(prompt.contains("ESCAPE_TAB=\\t"))

        val schemaFields = listOf(
            "PLANNING_GOAL=<escaped>",
            "PLANNING_STEP_COUNT=<int",
            "PLANNING_STEP=<escaped>",
            "REASONING_PREMISE_COUNT=<int",
            "REASONING_PREMISE=<escaped>",
            "REASONING_ANALYSIS=<escaped>",
            "REASONING_CONCLUSION=<escaped>",
            "DECISION_OPTION_COUNT=<int",
            "DECISION_OPTION=<escaped>",
            "DECISION_SELECTED_INDEX=<int",
            "DECISION_RATIONALE=<escaped>",
            "RESULT_CONTENT=<escaped>",
            "REFLECTION_CONTENT=<escaped>",
            "LEARNING_PROPOSAL=<escaped>",
            "\nEND"
        )
        var previous = -1
        schemaFields.forEach { marker ->
            val position = prompt.indexOf(marker)
            assertTrue(position > previous, "schema marker out of order: $marker")
            previous = position
        }
        assertTrue(prompt.indexOf("\nEND\nINPUT\n") > 0)
        assertTrue(prompt.indexOf("RESPONSE_PROTOCOL") < prompt.indexOf("private input"))
    }

    @Test
    fun deterministic_compiler_preserves_authoritative_context_order_without_projecting_source_ids() {
        val items = listOf(
            CognitiveContextItem(
                source = CognitiveContextSourceReference.Memory(
                    recordId = MemoryRecordId("private-memory-id"),
                    generation = MemoryGeneration(2)
                ),
                content = "memory evidence"
            ),
            CognitiveContextItem(
                source = CognitiveContextSourceReference.Knowledge(
                    itemId = KnowledgeItemId("private-knowledge-id"),
                    generation = KnowledgeGeneration(3)
                ),
                content = "knowledge evidence"
            )
        )
        val compiler = DeterministicCognitiveModelRequestCompiler()
        val result = assertIs<CognitiveModelRequestCompilerResult.Compiled>(
            compiler.compile(
                CognitiveModelRequestCompilerRequest(
                    inference = inferenceRequest(items),
                    maxPromptChars = 16_384,
                    responseBudgets = budgets
                )
            )
        )

        val prompt = result.request.prompt
        assertTrue(prompt.indexOf("memory evidence") < prompt.indexOf("knowledge evidence"))
        assertTrue(prompt.contains("1:MEMORY"))
        assertTrue(prompt.contains("2:KNOWLEDGE"))
        assertFalse(prompt.contains("private-memory-id"))
        assertFalse(prompt.contains("private-knowledge-id"))
    }

    @Test
    fun deterministic_compiler_rejects_prompt_over_bound_without_partial_success() {
        val compiler = DeterministicCognitiveModelRequestCompiler()
        val result = assertIs<CognitiveModelRequestCompilerResult.Rejected>(
            compiler.compile(
                CognitiveModelRequestCompilerRequest(
                    inference = inferenceRequest(),
                    maxPromptChars = 8,
                    responseBudgets = budgets
                )
            )
        )
        assertEquals(
            CognitiveModelRequestCompilerFailure.RESOURCE_LIMIT_REJECTED,
            result.reason
        )
    }

    @Test
    fun deterministic_compiler_rejects_output_budget_too_small_for_minimum_v1_envelope() {
        val compiler = DeterministicCognitiveModelRequestCompiler()
        val result = assertIs<CognitiveModelRequestCompilerResult.Rejected>(
            compiler.compile(
                CognitiveModelRequestCompilerRequest(
                    inference = inferenceRequest(
                        maxOutputChars = CognitiveStructuredResponseProtocol.minimumEnvelopeChars - 1
                    ),
                    maxPromptChars = 16_384,
                    responseBudgets = budgets
                )
            )
        )
        assertEquals(
            CognitiveModelRequestCompilerFailure.RESOURCE_LIMIT_REJECTED,
            result.reason
        )
    }

    @Test
    fun deterministic_compiler_rejects_requested_output_above_shared_protocol_budget() {
        val compiler = DeterministicCognitiveModelRequestCompiler()
        val result = assertIs<CognitiveModelRequestCompilerResult.Rejected>(
            compiler.compile(
                CognitiveModelRequestCompilerRequest(
                    inference = inferenceRequest(maxOutputChars = budgets.maxOutputChars + 1),
                    maxPromptChars = 16_384,
                    responseBudgets = budgets
                )
            )
        )
        assertEquals(
            CognitiveModelRequestCompilerFailure.RESOURCE_LIMIT_REJECTED,
            result.reason
        )
    }
}
