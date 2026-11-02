package pro.liliya.android.semanticprovider.host

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests whether the consent-granted real Qwen3 model is backed by an ordinary,
 * seekable descriptor usable inside the *same process* by native mmap.
 * Does not load weights, generate, copy, or change the user's model.
 */
@RunWith(AndroidJUnit4::class)
class Qwen3LocalFdBridgeInstrumentedTest {
    @Test
    fun existing_qwen3_grant_exposes_seekable_descriptor_for_native_model_load() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val value = context.getSharedPreferences("qwen3-local-source-test-v1", 0)
            .getString("selected-content-uri", null)
            ?: error("No selected GGUF; retain and use prior Android document grant")
        val uri = Uri.parse(value)
        require(uri.scheme == "content")
        assertTrue(context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        })
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            assertEquals(1_282_439_264L, pfd.statSize)
            val path = "/proc/self/fd/${pfd.fd}"
            assertTrue(File(path).exists())
            ParcelFileDescriptor.dup(pfd.fileDescriptor).use { probe ->
                val header = ByteArray(4)
                val n = android.system.Os.pread(
                    probe.fileDescriptor, header, 0, 4, 0L
                )
                assertEquals(4, n)
                assertTrue(header.contentEquals(byteArrayOf(71, 71, 85, 70)))
            }
            println("QWEN3_SAF_FD_NATIVE_PATH_READY=PASS size=${pfd.statSize}")
        }
    }
}
