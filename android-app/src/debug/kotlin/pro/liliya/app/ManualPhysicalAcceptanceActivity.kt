package pro.liliya.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Manual physical-device acceptance launcher for the temporary debug APK only.
 *
 * This is deliberately located under src/debug and must never be copied into main/release source.
 * It does not bypass Product Auth: it creates a temporary LPAUTH1 artifact, imports it through the
 * production importer and encrypted store, installs only an explicit test first-run configuration,
 * and then hands control to the real provisioning Activity.
 */
class ManualPhysicalAcceptanceActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            text = "Подготовка физического теста Liliya…"
            setPadding(32, 32, 32, 32)
        }
        setContentView(status)

        Thread({ prepareAndOpenRealApp() }, "liliya-manual-physical-bootstrap").start()
    }

    private fun prepareAndOpenRealApp() {
        val application = applicationContext as LiliyaApplication
        val store = ProductionAndroidProductAuthEncryptedStore.create(application)
        val artifact = File(cacheDir, "manual-physical-product-auth.lpauth1")
        val secret = "manual-physical-arm64-liliya-fixture".encodeToByteArray()

        try {
            store.deleteForTests()
            ProductionAndroidFirstRunConfigurationOwner.clearForTests()
            artifact.delete()

            FileOutputStream(artifact, false).use { output ->
                output.write("LPAUTH1\n".encodeToByteArray())
                output.write(secret)
                output.flush()
                output.fd.sync()
            }

            val imported = ProductionAndroidProductAuthCredentialImport.import(
                openInput = { FileInputStream(artifact) },
                provision = store::provision
            )
            check(imported === ProductionAndroidProductAuthImportResult.Imported) {
                "Product Auth import result: $imported"
            }

            val installed = application.configureFirstRunAcquisition(
                ProductionAndroidFirstRunConfiguration(
                    licenseAcquisition = ProductionAndroidFirstRunLicenseAcquisitionPort {
                        error("manual physical bootstrap must not acquire a License")
                    },
                    productInput = ProductionAndroidFirstRunProductInputPort { _, _ ->
                        error("manual physical bootstrap must not assemble product input")
                    }
                )
            )
            check(installed) { "First-run test configuration was not installed" }

            runOnUiThread {
                status.text = "Product Auth готов. Открываю настоящий интерфейс Liliya…"
                startActivity(Intent(this, LiliyaProvisioningActivity::class.java))
                finish()
            }
        } catch (error: Throwable) {
            runOnUiThread {
                status.text = buildString {
                    append("Физический тест не подготовлен.\n\n")
                    append(error::class.java.simpleName)
                    val message = error.message
                    if (!message.isNullOrBlank()) {
                        append(": ")
                        append(message)
                    }
                }
            }
        } finally {
            artifact.delete()
            secret.fill(0)
        }
    }
}
