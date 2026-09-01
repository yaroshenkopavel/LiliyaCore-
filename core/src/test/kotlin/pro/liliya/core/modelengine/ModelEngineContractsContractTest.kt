package pro.liliya.core.modelengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelEngineContractsContractTest {
    @Test
    fun structural_ids_are_bounded_and_engine_handle_is_redacted() {
        assertEquals("fake-engine", ModelEngineBackendId("fake-engine").toString())
        assertFailsWith<IllegalArgumentException> { ModelEngineBackendId(" ") }
        assertFailsWith<IllegalArgumentException> { ModelEngineBackendId("b".repeat(129)) }

        val handle = ModelEngineHandleId("private-native-handle")
        assertEquals("ModelEngineHandleId([redacted])", handle.toString())
        assertFalse(handle.toString().contains("private-native-handle"))
        assertFailsWith<IllegalArgumentException> { ModelEngineHandleId("h".repeat(513)) }
    }

    @Test
    fun inference_request_and_success_render_private_content_as_redacted() {
        val request = ModelEngineInferenceRequest(
            prompt = "private prompt payload",
            maxOutputChars = 123
        )
        val requestRendered = request.toString()
        assertFalse(requestRendered.contains("private prompt payload"))
        assertTrue(requestRendered.contains("redacted"))
        assertTrue(requestRendered.contains("maxOutputChars=123"))

        val success = ModelEngineInferenceResult.Succeeded("private model output")
        val successRendered = success.toString()
        assertFalse(successRendered.contains("private model output"))
        assertTrue(successRendered.contains("redacted"))
    }

    @Test
    fun engine_success_can_be_malformed_so_provider_can_independently_validate_it() {
        val success = ModelEngineInferenceResult.Succeeded("")
        assertEquals("", success.output)
    }

    @Test
    fun engine_session_contract_exposes_only_structural_identity_inference_and_close() {
        var inferCalls = 0
        var closeCalls = 0
        val ownership = object : ModelEngineSessionOwnership {
            override val backendId = ModelEngineBackendId("fake-engine")
            override val handleId = ModelEngineHandleId("opaque-handle")

            override fun infer(request: ModelEngineInferenceRequest): ModelEngineInferenceResult {
                inferCalls += 1
                return ModelEngineInferenceResult.Rejected(ModelEngineInferenceFailure.REQUEST_REJECTED)
            }

            override fun close(): ModelEngineCloseResult {
                closeCalls += 1
                return ModelEngineCloseResult.Closed
            }
        }

        assertIs<ModelEngineInferenceResult.Rejected>(
            ownership.infer(ModelEngineInferenceRequest("private", 32))
        )
        assertIs<ModelEngineCloseResult.Closed>(ownership.close())
        assertEquals(1, inferCalls)
        assertEquals(1, closeCalls)
    }
}
