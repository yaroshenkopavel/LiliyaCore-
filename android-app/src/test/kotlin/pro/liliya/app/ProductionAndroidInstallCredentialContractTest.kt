package pro.liliya.app

import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class ProductionAndroidInstallCredentialContractTest {
    @Test
    fun generated_material_is_bounded_random_and_redacted() {
        val generator = ProductionAndroidInstallCredentialGenerator(SecureRandom())
        val first = generator.generate()
        val second = generator.generate()
        try {
            assertTrue(first.installId.matches(Regex("^liliya-[0-9a-f]{32}$")))
            assertEquals(32, first.installSecret.size)
            assertNotEquals(first.installId, second.installId)
            assertFalse(first.installSecret.contentEquals(second.installSecret))
            assertTrue("<redacted>" in first.toString())
            assertFalse(first.toString().contains(first.installId))
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun material_owns_secret_copy_and_zeroizes_on_close() {
        val secret = ByteArray(32) { it.toByte() }
        val material = ProductionAndroidInstallCredentialMaterial(
            installId = "liliya-00112233445566778899aabbccddeeff",
            installSecret = secret.copyOf()
        )

        val copy = material.copySecret()
        copy[0] = 99
        assertEquals(0, material.copySecret()[0].toInt())

        material.close()
        assertTrue(material.installSecret.all { it == 0.toByte() })
        secret.fill(0)
        copy.fill(0)
    }
}
