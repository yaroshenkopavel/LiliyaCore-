package pro.liliya.android.llamacppengine

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.modelengine.ModelEngineCloseResult
import pro.liliya.core.modelengine.ModelEngineInferenceRequest
import pro.liliya.core.modelengine.ModelEngineInferenceResult
import pro.liliya.core.modelengine.ModelEngineLoadFailure
import pro.liliya.core.modelengine.ModelEngineLoadResult

class AndroidLlamaCppPhysicalEngineLoaderContractTest {

    @Test
    fun validated_source_success_returns_opaque_session_and_preserves_native_identity_internally() {
        val privatePath = "/data/user/0/pro.liliya/private/sealed/model.gguf"
        val nativeSessionId = 987654321L
        val native = FakeNativePort().apply {
            loadResult = LlamaCppNativeLoadResult.Loaded(nativeSessionId)
            inferResult = LlamaCppNativeInferenceResult.Succeeded("answer")
        }
        val loader = AndroidLlamaCppPhysicalEngineLoader(policy(), native)

        val loaded = assertIs<ModelEngineLoadResult.Loaded>(
            loader.loadValidatedPhysicalSource(File(privatePath))
        )

        assertEquals(privatePath, native.loadedPath)
        assertEquals(policy(), native.loadedPolicy)
        assertFalse(loaded.ownership.handleId.value.contains(privatePath))
        assertFalse(loaded.ownership.handleId.value.contains(nativeSessionId.toString()))
        assertFalse(loaded.ownership.handleId.toString().contains(privatePath))
        assertFalse(loaded.ownership.handleId.toString().contains(nativeSessionId.toString()))

        val inferred = assertIs<ModelEngineInferenceResult.Succeeded>(
            loaded.ownership.infer(
                ModelEngineInferenceRequest("private prompt", maxOutputChars = 32)
            )
        )
        assertEquals("answer", inferred.output)
        assertEquals(nativeSessionId, native.lastInferSessionId)

        assertIs<ModelEngineCloseResult.Closed>(loaded.ownership.close())
        assertEquals(nativeSessionId, native.lastCloseSessionId)
    }

    @Test
    fun native_structural_load_rejections_are_preserved_exactly() {
        for (reason in ModelEngineLoadFailure.entries) {
            val native = FakeNativePort().apply {
                loadResult = LlamaCppNativeLoadResult.Rejected(reason)
            }
            val rejected = assertIs<ModelEngineLoadResult.Rejected>(
                AndroidLlamaCppPhysicalEngineLoader(policy(), native)
                    .loadValidatedPhysicalSource(File("/private/model.gguf"))
            )
            assertEquals(reason, rejected.reason)
        }
    }

    @Test
    fun provider_exception_and_invalid_native_session_id_fail_closed_without_private_message() {
        val privateMessage = "/data/user/0/private/model.gguf secret"
        val throwing = FakeNativePort().apply {
            loadThrowable = IllegalStateException(privateMessage)
        }
        val thrown = assertIs<ModelEngineLoadResult.Rejected>(
            AndroidLlamaCppPhysicalEngineLoader(policy(), throwing)
                .loadValidatedPhysicalSource(File("/data/user/0/private/model.gguf"))
        )
        assertEquals(ModelEngineLoadFailure.PROVIDER_FAILED, thrown.reason)
        assertFalse(thrown.toString().contains(privateMessage))

        for (invalidId in listOf(0L, -1L, Long.MIN_VALUE)) {
            val invalid = FakeNativePort().apply {
                loadResult = LlamaCppNativeLoadResult.Loaded(invalidId)
            }
            val rejected = assertIs<ModelEngineLoadResult.Rejected>(
                AndroidLlamaCppPhysicalEngineLoader(policy(), invalid)
                    .loadValidatedPhysicalSource(File("/private/model.gguf"))
            )
            assertEquals(ModelEngineLoadFailure.PROVIDER_FAILED, rejected.reason)
        }
    }

    private fun policy(): LlamaCppEnginePolicy = LlamaCppEnginePolicy(
        contextTokens = 64,
        maxPromptTokens = 32,
        maxGeneratedTokens = 16,
        batchTokens = 16,
        microBatchTokens = 8,
        threadCount = 2,
        useMmap = true
    )

    private class FakeNativePort : LlamaCppNativeSessionPort {
        var loadResult: LlamaCppNativeLoadResult =
            LlamaCppNativeLoadResult.Rejected(ModelEngineLoadFailure.LOAD_REJECTED)
        var inferResult: LlamaCppNativeInferenceResult =
            LlamaCppNativeInferenceResult.Succeeded("ok")
        var loadThrowable: Throwable? = null
        var loadedPath: String? = null
        var loadedPolicy: LlamaCppEnginePolicy? = null
        var lastInferSessionId: Long? = null
        var lastCloseSessionId: Long? = null

        override fun load(
            sourcePath: String,
            policy: LlamaCppEnginePolicy
        ): LlamaCppNativeLoadResult {
            loadedPath = sourcePath
            loadedPolicy = policy
            loadThrowable?.let { throw it }
            return loadResult
        }

        override fun infer(
            nativeSessionId: Long,
            prompt: String,
            maxOutputChars: Int
        ): LlamaCppNativeInferenceResult {
            lastInferSessionId = nativeSessionId
            return inferResult
        }

        override fun close(nativeSessionId: Long): LlamaCppNativeCloseResult {
            lastCloseSessionId = nativeSessionId
            return LlamaCppNativeCloseResult.Closed
        }
    }
}
