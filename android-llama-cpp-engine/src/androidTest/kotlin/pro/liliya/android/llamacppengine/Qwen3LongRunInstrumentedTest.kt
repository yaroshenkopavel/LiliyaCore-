package pro.liliya.android.llamacppengine

import android.net.Uri
import android.os.BatteryManager
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

/** Isolated bounded 20-call stress probe. PSS is process total PSS, not native heap. */
@RunWith(AndroidJUnit4::class)
class Qwen3LongRunInstrumentedTest {
    @Test fun twenty_bounded_inferences_with_pss() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        fun report(key: String, value: Any) {
            inst.sendStatus(0, Bundle().apply { putString(key, value.toString()) })
        }
        fun pssKb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
        fun batteryTempTenths(): Int {
            val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            return intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        }
        val source = context.getSharedPreferences("qwen3-local-source-test-v1", 0)
            .getString("selected-content-uri", null)
            ?: error("Select model using isolated SAF picker")
        val uri = Uri.parse(source)
        require(uri.scheme == "content")
        assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
        require(context.cacheDir.usableSpace > 1_600_000_000L) { "QWEN3_STRESS_INSUFFICIENT_SPACE" }
        val staged = File(context.cacheDir, "qwen3-stress-staged.gguf")
        val policy = LlamaCppEnginePolicy(
            contextTokens = 256, maxPromptTokens = 128, maxGeneratedTokens = 16,
            batchTokens = 64, microBatchTokens = 32, threadCount = 2,
            maxPromptChars = 256, maxPromptUtf8Bytes = 1024,
            maxOutputChars = 512, maxOutputUtf8Bytes = 2048,
            useMmap = true,
            promptFormatPolicy = LlamaCppPromptFormatPolicy.MODEL_DEFAULT_CHAT_TEMPLATE
        )
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
            report("QWEN3_STRESS_STAGE_SHA256", "PASS")
            val loadStart = System.nanoTime()
            val handle = LlamaCppNativeBridge.nativeLoad(
                staged.absolutePath.toByteArray(Charsets.UTF_8),
                policy.contextTokens, policy.maxPromptTokens, policy.maxGeneratedTokens,
                policy.batchTokens, policy.microBatchTokens, policy.threadCount,
                policy.maxPromptUtf8Bytes, policy.maxOutputUtf8Bytes, policy.useMmap,
                policy.promptFormatPolicy.nativeCode, byteArrayOf()
            )
            report("QWEN3_STRESS_LOAD_MS", (System.nanoTime() - loadStart) / 1_000_000)
            check(handle > 0L) { "QWEN3_STRESS_LOAD_FAILED: $handle" }
            try {
                val baseline = pssKb()
                report("QWEN3_STRESS_PSS_BASELINE_KB", baseline)
                report("QWEN3_STRESS_TOKEN_CAP", policy.maxGeneratedTokens)
                val prompts = arrayOf("Ответь коротко: привет", "Назови один цвет")
                for (i in 1..20) {
                    val temp = batteryTempTenths()
                    report("QWEN3_STRESS_TEMP_${i}_TENTHS_C", temp)
                    check(temp in 1..399) { "QWEN3_STRESS_TEMP_UNSAFE_OR_UNKNOWN: $temp" }
                    val start = System.nanoTime()
                    val result = LlamaCppNativeBridge.nativeInfer(
                        handle, prompts[(i - 1) % prompts.size].toByteArray(Charsets.UTF_8), 512
                    )
                    val elapsed = (System.nanoTime() - start) / 1_000_000
                    check(result.isNotEmpty() && result[0] == LlamaCppNativeBridge.INFER_OK && result.size > 1) {
                        "QWEN3_STRESS_INFER_FAILED_$i"
                    }
                    report("QWEN3_STRESS_INFER_${i}_MS", elapsed)
                    report("QWEN3_STRESS_OUTPUT_${i}_BYTES", result.size - 1)
                    val output = result.copyOfRange(1, result.size)
                    val outputSha = MessageDigest.getInstance("SHA-256").digest(output)
                        .joinToString("") { "%02x".format(it) }
                    report("QWEN3_STRESS_OUTPUT_${i}_SHA256", outputSha)
                    if (i <= 2) {
                        report("QWEN3_STRESS_OUTPUT_${i}_BASE64",
                            android.util.Base64.encodeToString(output, android.util.Base64.NO_WRAP))
                    }
                    report("QWEN3_STRESS_PSS_${i}_KB", pssKb())
                }
                report("QWEN3_STRESS_COMPLETED_CALLS", 20)
            } finally {
                report("QWEN3_STRESS_CLOSE_RESULT", LlamaCppNativeBridge.nativeClose(handle))
            }
        } finally {
            report("QWEN3_STRESS_STAGE_REMOVED", staged.delete())
        }
    }
}
