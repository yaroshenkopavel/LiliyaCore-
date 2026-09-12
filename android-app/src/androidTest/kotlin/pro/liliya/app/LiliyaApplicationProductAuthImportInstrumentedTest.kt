package pro.liliya.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiliyaApplicationProductAuthImportInstrumentedTest {
    private val application = ApplicationProvider.getApplicationContext<LiliyaApplication>()
    private val store = ProductionAndroidProductAuthEncryptedStore.create(application)

    @After
    fun cleanup() {
        store.deleteForTests()
        File(application.cacheDir, FIXTURE_NAME).delete()
    }

    @Test
    fun application_import_bridge_persists_exact_credential_in_dedicated_store() {
        store.deleteForTests()
        val expected = "device-provisioned-product-auth-secret".encodeToByteArray()
        val fixture = File(application.cacheDir, FIXTURE_NAME)
        fixture.writeBytes("LPAUTH1\n".encodeToByteArray() + expected)

        val result = application.importProductAuthCredential(Uri.fromFile(fixture))

        assertIs<ProductionAndroidProductAuthImportResult.Imported>(result)
        val reopened = ProductionAndroidProductAuthEncryptedStore.create(application).openSecret()
        try {
            assertContentEquals(expected, reopened)
        } finally {
            reopened.fill(0)
            expected.fill(0)
        }
    }

    companion object {
        private const val FIXTURE_NAME = "product-auth-import-fixture.lpauth"
    }
}
