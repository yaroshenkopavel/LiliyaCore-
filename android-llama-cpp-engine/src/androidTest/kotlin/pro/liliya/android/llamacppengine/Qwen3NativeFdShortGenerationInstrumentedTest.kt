package pro.liliya.android.llamacppengine

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded full-size Qwen3 physical inference from consented Android fd. */
@RunWith(AndroidJUnit4::class)
class Qwen3NativeFdShortGenerationInstrumentedTest {
    @Test
    fun four_token_generation_from_granted_qwen3_fd() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = context.getSharedPreferences("qwen3-local-source-test-v1", 0)
            .getString("selected-content-uri", null)
            ?: error("Select Qwen3 GGUF through the host Android Documents picker")
        val uri = Uri.parse(source)
        require(uri.scheme == "content")
        assertTrue(context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        })
        val policy = LlamaCppEnginePolicy(
            contextTokens = 256,
            maxPromptTokens = 128,
            maxGeneratedTokens = 4,
            batchTokens = 64,
            microBatchTokens = 32,
            threadCount = 2,
            maxPromptChars = 256,
            maxPromptUtf8Bytes = 1024,
            maxOutputChars = 128,
            maxOutputUtf8Bytes = 512,
            useMmap = true,
            promptFormatPolicy = LlamaCppPromptFormatPolicy.MODEL_DEFAULT_CHAT_TEMPLATE
        )
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
            assertEquals(1_282_439_264L, fd.statSize)
            val path = "/proc/self/fd/${fd.fd}"
            val started = System.nanoTime()
            val loaded = JniLlamaCppNativeSessionPort.load(path, policy)
            check(loaded is LlamaCppNativeLoadResult.Loaded) {
                "QWEN3_NATIVE_FD_LOAD_FAILED: $loaded"
            }
            println("QWEN3_NATIVE_FD_LOAD=PASS ms=" +
                ((System.nanoTime() - started) / 1_000_000))
            try {
                val result = JniLlamaCppNativeSessionPort.infer(
                    loaded.nativeSessionId,
                    "Ответь одним словом: привет".toByteArray(Charsets.UTF_8),
                    128
                )
                check(result is LlamaCppNativeInferenceResult.Succeeded) {
                    "QWEN3_INFERENCE_FAILED: $result"
                }
                assertTrue(result.output.isNotEmpty())
                println("QWEN3_REAL_GENERATION=PASS output=" + result.output.take(128))
            } finally {
                val closed = JniLlamaCppNativeSessionPort.close(loaded.nativeSessionId)
                println("QWEN3_NATIVE_CLOSE=" + closed)
            }
        }
    }
}
