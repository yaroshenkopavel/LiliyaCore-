package pro.liliya.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Minimal launcher-side provisioning surface for Product Auth.
 *
 * This Activity owns only the picker and status UI. URI/plaintext credential ownership never
 * survives the Activity result callback: the selected URI is handed directly to the
 * Application-scoped import task, while the bounded importer and encrypted store own the secret
 * path. Runtime/chat ownership stays in [LiliyaActivity].
 */
class LiliyaProvisioningActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var selectCredential: Button
    private var stateSaved = false

    private val app: LiliyaApplication
        get() = application as LiliyaApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        restoreImportState()
    }

    override fun onStart() {
        super.onStart()
        val restoredAfterSavedState = stateSaved
        stateSaved = false
        if (restoredAfterSavedState) restoreImportState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        stateSaved = true
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Legacy activity result API is intentionally bounded to this provisioning host.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PRODUCT_AUTH_DOCUMENT_REQUEST) return
        if (resultCode != RESULT_OK) {
            renderReadyForImport("Импорт доступа продукта отменён")
            return
        }
        val uri = data?.data
        if (uri == null) {
            renderReadyForImport("Не удалось получить файл доступа продукта")
            return
        }

        renderImportInFlight()
        when (
            app.requestProductAuthCredentialImport(
                uri = uri,
                listener = ::deliverImportCompletion
            )
        ) {
            is ProductionAndroidProductAuthImportTaskRequestResult.Started -> Unit
            ProductionAndroidProductAuthImportTaskRequestResult.Busy -> restoreImportState()
        }
    }

    @Suppress("DEPRECATION")
    internal fun deliverProductAuthSelectionResultForTests(resultCode: Int, data: Intent?) {
        onActivityResult(PRODUCT_AUTH_DOCUMENT_REQUEST, resultCode, data)
    }

    private fun buildContent(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(32), dp(20), dp(20))

            addView(TextView(this@LiliyaProvisioningActivity).apply {
                text = "Liliya — подготовка доступа"
                textSize = 22f
                gravity = Gravity.CENTER_HORIZONTAL
            })

            status = TextView(this@LiliyaProvisioningActivity).apply {
                textSize = 14f
                setPadding(0, dp(20), 0, dp(16))
            }
            addView(status)

            selectCredential = Button(this@LiliyaProvisioningActivity).apply {
                text = "Импортировать доступ продукта"
                setOnClickListener { launchProductAuthPicker() }
            }
            addView(selectCredential)
        }
    }

    private fun restoreImportState() {
        if (app.hasProductAuthCredential()) {
            openRuntimeHost()
            return
        }
        when (val snapshot = app.observeProductAuthCredentialImport(::deliverImportCompletion)) {
            ProductionAndroidProductAuthImportTaskSnapshot.Idle ->
                renderReadyForImport("Требуется доступ продукта")
            is ProductionAndroidProductAuthImportTaskSnapshot.InFlight -> renderImportInFlight()
            is ProductionAndroidProductAuthImportTaskSnapshot.Completed -> deliverImportCompletion(snapshot)
        }
    }

    private fun deliverImportCompletion(
        completed: ProductionAndroidProductAuthImportTaskSnapshot.Completed
    ) {
        runOnUiThread {
            if (isFinishing || isDestroyed || isChangingConfigurations || stateSaved) return@runOnUiThread
            if (!app.consumeProductAuthCredentialImport(completed.requestId)) return@runOnUiThread
            when (completed.result) {
                ProductionAndroidProductAuthImportResult.Imported,
                ProductionAndroidProductAuthImportResult.AlreadyProvisioned -> openRuntimeHost()
                ProductionAndroidProductAuthImportResult.Rejected ->
                    renderReadyForImport("Файл доступа продукта отклонён")
                ProductionAndroidProductAuthImportResult.Failed ->
                    renderReadyForImport("Не удалось импортировать доступ продукта")
            }
        }
    }

    private fun renderReadyForImport(message: String) {
        status.text = message
        selectCredential.isEnabled = true
        selectCredential.visibility = View.VISIBLE
    }

    private fun renderImportInFlight() {
        status.text = "Импорт доступа продукта…"
        selectCredential.isEnabled = false
        selectCredential.visibility = View.VISIBLE
    }

    private fun launchProductAuthPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, PRODUCT_AUTH_DOCUMENT_REQUEST)
    }

    private fun openRuntimeHost() {
        startActivity(Intent(this, LiliyaActivity::class.java))
        finish()
    }

    private companion object {
        const val PRODUCT_AUTH_DOCUMENT_REQUEST = 1101
    }
}
