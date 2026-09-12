package pro.liliya.app

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class ProductionAndroidProductAuthCredentialImportContractTest {
    @Test
    fun exact_versioned_artifact_is_provisioned_once_and_plaintext_transfer_is_zeroized() {
        val artifact = "LPAUTH1\nexact-product-auth-secret".encodeToByteArray()
        lateinit var transferred: ByteArray

        val result = ProductionAndroidProductAuthCredentialImport.import(
            openInput = { ByteArrayInputStream(artifact) },
            provision = { secret ->
                transferred = secret
                assertContentEquals("exact-product-auth-secret".encodeToByteArray(), secret)
                ProductionAndroidProductAuthProvisionResult.Provisioned
            }
        )

        assertIs<ProductionAndroidProductAuthImportResult.Imported>(result)
        assertTrue(transferred.all { it == 0.toByte() })
        assertContentEquals("LPAUTH1\nexact-product-auth-secret".encodeToByteArray(), artifact)
    }

    @Test
    fun malformed_or_oversized_artifacts_fail_before_provisioning() {
        var calls = 0
        val malformed = ProductionAndroidProductAuthCredentialImport.import(
            openInput = { ByteArrayInputStream("WRONG\nsecret".encodeToByteArray()) },
            provision = {
                calls += 1
                ProductionAndroidProductAuthProvisionResult.Provisioned
            }
        )
        val oversized = ProductionAndroidProductAuthCredentialImport.import(
            openInput = {
                ByteArrayInputStream("LPAUTH1\n".encodeToByteArray() + ByteArray(4097) { 'x'.code.toByte() })
            },
            provision = {
                calls += 1
                ProductionAndroidProductAuthProvisionResult.Provisioned
            }
        )

        assertIs<ProductionAndroidProductAuthImportResult.Rejected>(malformed)
        assertIs<ProductionAndroidProductAuthImportResult.Rejected>(oversized)
        assertEquals(0, calls)
    }

    @Test
    fun zero_progress_bulk_read_falls_back_to_single_byte_progress() {
        val bytes = "LPAUTH1\nzero-progress-secret".encodeToByteArray()
        var bulkCalls = 0
        val input = object : InputStream() {
            private var index = 0

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                bulkCalls += 1
                if (bulkCalls == 1) return 0
                if (index >= bytes.size) return -1
                val count = minOf(length, bytes.size - index)
                bytes.copyInto(buffer, offset, index, index + count)
                index += count
                return count
            }

            override fun read(): Int =
                if (index >= bytes.size) -1 else bytes[index++].toInt() and 0xFF
        }

        val result = ProductionAndroidProductAuthCredentialImport.import(
            openInput = { input },
            provision = { ProductionAndroidProductAuthProvisionResult.Provisioned }
        )

        assertIs<ProductionAndroidProductAuthImportResult.Imported>(result)
        assertTrue(bulkCalls >= 2)
    }

    @Test
    fun provisioning_result_is_preserved_without_retry() {
        var calls = 0
        val result = ProductionAndroidProductAuthCredentialImport.import(
            openInput = { ByteArrayInputStream("LPAUTH1\nsecret".encodeToByteArray()) },
            provision = {
                calls += 1
                ProductionAndroidProductAuthProvisionResult.AlreadyProvisioned
            }
        )

        assertIs<ProductionAndroidProductAuthImportResult.AlreadyProvisioned>(result)
        assertEquals(1, calls)
    }
}
