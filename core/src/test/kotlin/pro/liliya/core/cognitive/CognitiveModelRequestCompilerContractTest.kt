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
    private fun inferenceRequest(
        contextItems: List<CognitiveContextItem> = emptyList()
    ): CognitiveInferenceRequest {
        val turn = CognitiveTurnReference(
            id = CognitiveTurnId("private-turn-id"),
            generation = CognitiveTurnGeneration(1)
        )
        return CognitiveInferenceRequest(
            turn = turn,
            input = CognitiveInput("private input"),
            context = CognitiveContextSnapshot(turn, contextItems),
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
                    maxPromptChars = 4096
                )
            )
        )

        val prompt = result.request.prompt
        assertTrue(prompt.startsWith("LILIYA_COGNITIVE_REQUEST_V1\nINPUT\nprivate input\nCONTEXT"))
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
                    maxPromptChars = 8
                )
            )
        )
        assertEquals(
            CognitiveModelRequestCompilerFailure.RESOURCE_LIMIT_REJECTED,
            result.reason
        )
    }
}
