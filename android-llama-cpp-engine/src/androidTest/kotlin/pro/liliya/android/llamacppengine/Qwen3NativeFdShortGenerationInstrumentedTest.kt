package pro.liliya.android.llamacppengine

import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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
        val staged = File(context.cacheDir, "qwen3-1.7b-physical-once.gguf")
        require(context.cacheDir.usableSpace > 1_600_000_000L) {
            "QWEN3_INSUFFICIENT_STAGING_SPACE"
        }
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
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            assertEquals("d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5", sha)
            println("QWEN3_LOCAL_STAGE_SHA256=PASS bytes=$copied")
            val path = staged.absolutePath
            val started = System.nanoTime()
            val loaded = LlamaCppNativeBridge.nativeLoad(
                path.toByteArray(Charsets.UTF_8), policy.contextTokens,
                policy.maxPromptTokens, policy.maxGeneratedTokens, policy.batchTokens,
                policy.microBatchTokens, policy.threadCount, policy.maxPromptUtf8Bytes,
                policy.maxOutputUtf8Bytes, policy.useMmap, policy.promptFormatPolicy.nativeCode,
                byteArrayOf()
            )
            check(loaded > 0L) {
                "QWEN3_PRIVATE_STAGE_LOAD_FAILED: $loaded"
            }
            println("QWEN3_NATIVE_FD_LOAD=PASS ms=" +
                ((System.nanoTime() - started) / 1_000_000))
            try {
                val result = LlamaCppNativeBridge.nativeInfer(
                    loaded,
                    "Ответь одним словом: привет".toByteArray(Charsets.UTF_8),
                    128
                )
                check(result.isNotEmpty() && result[0] == LlamaCppNativeBridge.INFER_OK) {
                    "QWEN3_INFERENCE_FAILED: $result"
                }
                assertTrue(result.size > 1)
                println("QWEN3_REAL_GENERATION=PASS output=" + result.copyOfRange(1, result.size).toString(Charsets.UTF_8).take(128))
            } finally {
                val closed = LlamaCppNativeBridge.nativeClose(loaded)
                println("QWEN3_NATIVE_CLOSE=" + closed)
            }
        } finally {
            val removed = staged.delete()
            println("QWEN3_TEMP_STAGE_REMOVED=$removed")
        }
    }
}
