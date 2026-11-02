package pro.liliya.android.semanticprovider.host

import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Non-destructive source-access gate for a separately provisioned, explicitly granted
 * Android content URI. This does not stage, install, copy, or load model weights.
 *
 * Invocation requires -e qwen3LocalUri content://... and a readable URI grant to the
 * instrumentation target. A filesystem path or file:// URI is intentionally rejected:
 * access to shared Downloads under scoped storage must not be assumed.
 */
@RunWith(AndroidJUnit4::class)
class Qwen3LocalSourcePreflightInstrumentedTest {
    @Test
    fun authorized_local_gguf_matches_exact_candidate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val rawUri = InstrumentationRegistry.getArguments().getString("qwen3LocalUri")
            ?: error("missing qwen3LocalUri: must supply a granted content:// URI")
        val uri = Uri.parse(rawUri)
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) {
            "qwen3LocalUri must be a granted content:// URI"
        }

        val context = instrumentation.targetContext
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val magic = ByteArray(4)
        context.contentResolver.openInputStream(uri)?.use { input ->
            assertEquals("GGUF source must contain a 4-byte magic", 4, input.read(magic))
            assertTrue("GGUF magic mismatch", magic.contentEquals(byteArrayOf(71, 71, 85, 70)))
            digest.update(magic)
            count = 4
            val buffer = ByteArray(4 * 1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                count += n.toLong()
                require(count <= EXPECTED_BYTES) { "GGUF exceeds expected bytes" }
                digest.update(buffer, 0, n)
            }
        } ?: error("cannot open granted GGUF content URI")

        assertEquals("GGUF exact size mismatch", EXPECTED_BYTES, count)
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        assertEquals("GGUF SHA256 mismatch", EXPECTED_SHA256, actual)

        val evidence = Bundle()
        evidence.putString("qwen3LocalSource", "EXACT_SOURCE_VERIFIED")
        evidence.putString("qwen3Bytes", count.toString())
        evidence.putString("qwen3Sha256", actual)
        instrumentation.sendStatus(2, evidence)
        println("QWEN3_LOCAL_SOURCE_PREFLIGHT=PASS bytes=" + count)
    }

    private companion object {
        const val EXPECTED_BYTES = 1_282_439_264L
        const val EXPECTED_SHA256 =
            "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"
    }
}
