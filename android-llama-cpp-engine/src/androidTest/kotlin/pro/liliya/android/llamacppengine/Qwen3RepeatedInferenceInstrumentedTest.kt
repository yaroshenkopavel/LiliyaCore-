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
 * Bounded isolated repeat-inference probe; reports latency and process PSS.
 * Output bytes and token cap are NOT a measured generated-token count.
 */
@RunWith(AndroidJUnit4::class)
class Qwen3RepeatedInferenceInstrumentedTest {
    @Test fun two_bounded_inferences_with_pss() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        fun report(key: String, value: Any) {
            inst.sendStatus(0, Bundle().apply { putString(key, value.toString()) })
        }
        val source = context.getSharedPreferences("qwen3-local-source-test-v1", 0)
            .getString("selected-content-uri", null)
            ?: error("Select Qwen3 GGUF using the isolated host picker")
        val uri = Uri.parse(source)
        require(uri.scheme == "content")
        assertTrue(context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        })
        val staged = File(context.cacheDir, "qwen3-repeated-once.gguf")
        require(context.cacheDir.usableSpace > 1_600_000_000L) { "QWEN3_INSUFFICIENT_STAGING_SPACE" }
        val policy = LlamaCppEnginePolicy(
            contextTokens = 256, maxPromptTokens = 128, maxGeneratedTokens = 16,
            batchTokens = 64, microBatchTokens = 32, threadCount = 2,
            maxPromptChars = 256, maxPromptUtf8Bytes = 1024,
            maxOutputChars = 512, maxOutputUtf8Bytes = 2048,
            useMmap = true,
            promptFormatPolicy = LlamaCppPromptFormatPolicy.MODEL_DEFAULT_CHAT_TEMPLATE
        )
        fun pssKb(): Int {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            return info.totalPss
        }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            context.contentResolver.openInputStream(uri)!!.use { input ->
                FileOutputStream(staged).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        copied += n
                    }
                    output.fd.sync()
                }
            }
            assertEquals(1_282_439_264L, copied)
            assertEquals("d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5",
                digest.digest().joinToString("") { "%02x".format(it) })
            report("QWEN3_REPEAT_STAGE_SHA256", "PASS")
            val loadStart = System.nanoTime()
            val handle = LlamaCppNativeBridge.nativeLoad(
                staged.absolutePath.toByteArray(Charsets.UTF_8),
                policy.contextTokens, policy.maxPromptTokens, policy.maxGeneratedTokens,
                policy.batchTokens, policy.microBatchTokens, policy.threadCount,
                policy.maxPromptUtf8Bytes, policy.maxOutputUtf8Bytes, policy.useMmap,
                policy.promptFormatPolicy.nativeCode, byteArrayOf()
            )
            report("QWEN3_REPEAT_LOAD_MS", (System.nanoTime() - loadStart) / 1_000_000)
            check(handle > 0L) { "QWEN3_REPEAT_LOAD_FAILED: $handle" }
            try {
                report("QWEN3_REPEAT_PSS_BEFORE_KB", pssKb())
                val prompts = arrayOf("Ответь коротко: привет", "Назови один цвет")
                for (index in prompts.indices) {
                    val start = System.nanoTime()
                    val result = LlamaCppNativeBridge.nativeInfer(
                        handle, prompts[index].toByteArray(Charsets.UTF_8), 512
                    )
                    val elapsed = (System.nanoTime() - start) / 1_000_000
                    check(result.isNotEmpty() && result[0] == LlamaCppNativeBridge.INFER_OK) {
                        "QWEN3_REPEAT_INFER_FAILED_${index + 1}: $result"
                    }
                    assertTrue(result.size > 1)
                    report("QWEN3_REPEAT_INFER_${index + 1}_MS", elapsed)
                    report("QWEN3_REPEAT_OUTPUT_${index + 1}_BYTES", result.size - 1)
                    report("QWEN3_REPEAT_PSS_AFTER_${index + 1}_KB", pssKb())
                }
                report("QWEN3_REPEAT_TOKEN_CAP", policy.maxGeneratedTokens)
            } finally {
                report("QWEN3_REPEAT_CLOSE_RESULT", LlamaCppNativeBridge.nativeClose(handle))
            }
        } finally {
            report("QWEN3_REPEAT_STAGE_REMOVED", staged.delete())
        }
    }
}
