package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Acceptance-only Product Auth import for the laptop-hosted #259 path.
 *
 * Secret bytes never arrive through instrumentation arguments or logs. A one-shot LPAUTH1 artifact
 * is staged into the target application private files directory by the deployment operator.
 * This test imports it through the production importer into the production encrypted Product Auth
 * store, then overwrites/deletes the one-shot artifact.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalLaptopProductAuthProvisioningInstrumentedTest {

    @Test
    fun one_shot_product_auth_artifact_is_imported_and_destroyed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application =
            instrumentation.targetContext.applicationContext as LiliyaApplication
        val staged = File(
            instrumentation.targetContext.filesDir,
            ARTIFACT_FILE
        )
        assertTrue(staged.isFile, "one-shot Product Auth artifact is not staged")
        val originalLength = staged.length()
        assertTrue(originalLength in 9..4104)

        val store = ProductionAndroidProductAuthEncryptedStore.create(application)
        val result = try {
            ProductionAndroidProductAuthCredentialImport.import(
                openInput = { staged.inputStream() },
                provision = store::provision
            )
        } finally {
            destroyOneShotArtifact(staged, originalLength)
        }

        assertIs<ProductionAndroidProductAuthImportResult.Imported>(result)
        assertTrue(!staged.exists(), "one-shot Product Auth artifact must be deleted")

        val probe = store.openSecret()
        try {
            assertTrue(probe.isNotEmpty())
        } finally {
            probe.fill(0)
        }

        println(
            "LILIYA_LAPTOP_PRODUCT_AUTH_PROVISIONING=" +
                "{\"encryptedStore\":true,\"oneShotArtifactDestroyed\":true}"
        )
    }

    private fun destroyOneShotArtifact(
        file: File,
        originalLength: Long
    ) {
        try {
            if (file.isFile && originalLength > 0L) {
                FileOutputStream(file, false).use { output ->
                    var remaining = originalLength
                    val zeros = ByteArray(512)
                    try {
                        while (remaining > 0L) {
                            val count = minOf(remaining, zeros.size.toLong()).toInt()
                            output.write(zeros, 0, count)
                            remaining -= count
                        }
                        output.fd.sync()
                    } finally {
                        zeros.fill(0)
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val ARTIFACT_FILE = "liliya-product-auth-once.lpauth"
    }
}
