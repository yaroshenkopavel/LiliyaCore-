package pro.liliya.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.RandomAccessFile
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionAndroidProductAuthEncryptedStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val alias = "pro.liliya.product-auth.test.${System.nanoTime()}"
    private val store = ProductionAndroidProductAuthEncryptedStore.create(context, alias)

    @After
    fun cleanup() {
        store.deleteForTests()
    }

    @Test
    fun dedicated_keystore_store_reopens_exact_secret_and_rejects_tamper() {
        val secret = "instrumented-product-auth-secret".encodeToByteArray()
        assertIs<ProductionAndroidProductAuthProvisionResult.Provisioned>(store.provision(secret))
        assertIs<ProductionAndroidProductAuthProvisionResult.AlreadyProvisioned>(
            store.provision("replacement-must-not-win".encodeToByteArray())
        )

        val file = store.publishedFileForTest()
        val published = file.readBytes()
        try {
            assertFalse(published.containsContiguous(secret))
        } finally {
            published.fill(0)
        }

        val reopened = ProductionAndroidProductAuthEncryptedStore.create(context, alias)
        val opened = reopened.openSecret()
        try {
            assertContentEquals(secret, opened)
        } finally {
            opened.fill(0)
            secret.fill(0)
        }

        RandomAccessFile(file, "rw").use { random ->
            random.seek(file.length() - 1L)
            val original = random.readByte()
            random.seek(file.length() - 1L)
            random.writeByte(original.toInt() xor 0x01)
            random.fd.sync()
        }

        assertFailsWith<IllegalStateException> { reopened.openSecret() }
    }

    private fun ByteArray.containsContiguous(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        for (start in 0..size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }
}
