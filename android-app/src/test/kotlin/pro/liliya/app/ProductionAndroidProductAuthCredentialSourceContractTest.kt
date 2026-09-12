package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class ProductionAndroidProductAuthCredentialSourceContractTest {
    @Test
    fun each_attempt_opens_a_fresh_secret_and_zeroizes_the_transferred_plaintext_buffer() {
        var calls = 0
        val transferred = mutableListOf<ByteArray>()
        val source = ProductionAndroidProductAuthCredentialSource {
            calls += 1
            "attempt-secret-$calls".encodeToByteArray().also(transferred::add)
        }
        val factory = ProductionAndroidProductAuthCredentialAdapter.bearerFactory(source)

        val first = factory.create()
        val second = factory.create()
        try {
            assertEquals(2, calls)
            assertEquals(2, transferred.size)
            assertTrue(transferred.all { bytes -> bytes.all { it == 0.toByte() } })
            assertEquals("LicenseHttpBearerCredential(<redacted>)", first.toString())
            assertEquals("LicenseHttpBearerCredential(<redacted>)", second.toString())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun invalid_secret_still_zeroizes_the_transferred_plaintext_buffer() {
        lateinit var transferred: ByteArray
        val source = ProductionAndroidProductAuthCredentialSource {
            "invalid\nsecret".encodeToByteArray().also { transferred = it }
        }
        val factory = ProductionAndroidProductAuthCredentialAdapter.bearerFactory(source)

        assertFailsWith<IllegalArgumentException> { factory.create() }
        assertTrue(transferred.isNotEmpty())
        assertTrue(transferred.all { it == 0.toByte() })
    }

    @Test
    fun source_failure_is_not_reclassified_or_cached_by_the_adapter() {
        var calls = 0
        val source = ProductionAndroidProductAuthCredentialSource {
            calls += 1
            error("source unavailable")
        }
        val factory = ProductionAndroidProductAuthCredentialAdapter.bearerFactory(source)

        assertFailsWith<IllegalStateException> { factory.create() }
        assertFailsWith<IllegalStateException> { factory.create() }
        assertEquals(2, calls)
    }
}
