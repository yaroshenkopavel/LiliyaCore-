package pro.liliya.core.cognitive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CognitiveModelRequestCompilerContractTest {
    private fun inferenceRequest(): CognitiveInferenceRequest {
        val turn = CognitiveTurnReference(
            id = CognitiveTurnId("private-turn-id"),
            generation = CognitiveTurnGeneration(1)
        )
        return CognitiveInferenceRequest(
            turn = turn,
            input = CognitiveInput("private input"),
            context = CognitiveContextSnapshot(turn, emptyList()),
            maxOutputChars = 256
        )
    }

    @Test
    fun compiler_request_requires_positive_prompt_budget_and_redacts_private_content() {
        val inference = inferenceRequest()
        assertFailsWith<IllegalArgumentException> {
            CognitiveModelRequestCompilerRequest(inference, 0)
        }

        val request = CognitiveModelRequestCompilerRequest(
            inference = inference,
            maxPromptChars = 1024
        )
        val rendered = request.toString()
        assertFalse(rendered.contains("private input"))
        assertFalse(rendered.contains("private-turn-id"))
        assertTrue(rendered.contains("maxPromptChars=1024"))
        assertTrue(rendered.contains("maxOutputChars=256"))
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
                    maxPromptChars = 2048
                )
            )
        )
        assertEquals("compiled:2048", result.request.prompt)
    }
}
