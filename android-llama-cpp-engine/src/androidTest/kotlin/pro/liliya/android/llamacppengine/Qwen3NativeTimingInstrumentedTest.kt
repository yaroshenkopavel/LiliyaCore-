package pro.liliya.android.llamacppengine

import android.net.Uri
import android.os.Bundle
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolated physical Qwen3 timing probe.
 *
 * Reports staging, nativeLoad and nativeInfer separately through Android
 * instrumentation status. A 4-token cap is NOT a measured token count.
 * No changes to the primary LiliyaCore app or its persisted model.
 */
@RunWith(AndroidJUnit4::class)
class Qwen3NativeTimingInstrumentedTest {
    @Test
    fun report_staging_load_and_inference_timing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = context.getSharedPreferences("qwen3-local-source-test-v1", 0)
            .getString("selected-content-uri", null)
            ?: error("Select Qwen3 GGUF through the isolated host picker")
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
        val staged = File(context.cacheDir, "qwen3-timing-once.gguf")
        require(context.cacheDir.usableSpace > 1_600_000_000L) {
            "QWEN3_INSUFFICIENT_STAGING_SPACE"
        }
        fun report(key: String, value: String) {
            val bundle = Bundle()
            bundle.putString(key, value)
            instrumentation.sendStatus(0, bundle)
        }
        val startStage = System.nanoTime()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            context.contentResolver.openInputStream(uri)!!.use { sourceStream ->
                FileOutputStream(staged).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val n = sourceStream.read(buffer)
                        if (n == -1) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        copied += n
                    }
                    output.fd.sync()
                }
            }
            assertEquals(1_282_439_264L, copied)
            assertEquals(
                "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5",
                digest.digest().joinToString("") { "%02x".format(it) }
            )
            report("QWEN3_STAGE_MS", ((System.nanoTime() - startStage) / 1_000_000).toString())
            report("QWEN3_STAGED_BYTES", copied.toString())
            val startLoad = System.nanoTime()
            val loaded = LlamaCppNativeBridge.nativeLoad(
                staged.absolutePath.toByteArray(Charsets.UTF_8),
                policy.contextTokens, policy.maxPromptTokens,
                policy.maxGeneratedTokens, policy.batchTokens,
                policy.microBatchTokens, policy.threadCount,
                policy.maxPromptUtf8Bytes, policy.maxOutputUtf8Bytes,
                policy.useMmap, policy.promptFormatPolicy.nativeCode,
                byteArrayOf()
            )
            report("QWEN3_LOAD_MS", ((System.nanoTime() - startLoad) / 1_000_000).toString())
            check(loaded > 0L) { "QWEN3_LOAD_FAILED: $loaded" }
            try {
                report("QWEN3_NATIVE_HEAP_BEFORE_INFER_BYTES", Debug.getNativeHeapAllocatedSize().toString())
                val startInfer = System.nanoTime()
                val result = LlamaCppNativeBridge.nativeInfer(
                    loaded,
                    "Ответь одним словом: привет".toByteArray(Charsets.UTF_8),
                    128
                )
                report("QWEN3_INFER_MS", ((System.nanoTime() - startInfer) / 1_000_000).toString())
                check(result.isNotEmpty() && result[0] == LlamaCppNativeBridge.INFER_OK) {
                    "QWEN3_INFERENCE_FAILED: $result"
                }
                assertTrue(result.size > 1)
                report("QWEN3_OUTPUT_UTF8_BYTES", (result.size - 1).toString())
                report("QWEN3_NATIVE_HEAP_AFTER_INFER_BYTES", Debug.getNativeHeapAllocatedSize().toString())
                report("QWEN3_MAX_GENERATED_TOKENS", policy.maxGeneratedTokens.toString())
            } finally {
                val closed = LlamaCppNativeBridge.nativeClose(loaded)
                report("QWEN3_CLOSE_RESULT", closed.toString())
            }
        } finally {
            report("QWEN3_STAGE_REMOVED", staged.delete().toString())
        }
    }
}
