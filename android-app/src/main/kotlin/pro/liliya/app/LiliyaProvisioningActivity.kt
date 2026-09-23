package pro.liliya.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
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
    private lateinit var activationCode: EditText
    private lateinit var activate: Button
    private lateinit var selectCredential: Button
    private var activationInFlight = false
    private var stateSaved = false

    private val app: LiliyaApplication
        get() = application as LiliyaApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val content = buildContent()
        setContentView(content)
        content.requestApplyInsets()
        restoreProvisioningState()
    }

    override fun onStart() {
        super.onStart()
        val restoredAfterSavedState = stateSaved
        stateSaved = false
        if (restoredAfterSavedState) restoreProvisioningState()
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

        val horizontalPadding = dp(20)
        val topPadding = dp(32)
        val bottomPadding = dp(20)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                val systemBars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    bars.top to bars.bottom
                } else {
                    insets.systemWindowInsetTop to insets.systemWindowInsetBottom
                }
                view.setPadding(
                    horizontalPadding,
                    topPadding + systemBars.first,
                    horizontalPadding,
                    bottomPadding + systemBars.second
                )
                insets
            }

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

            activationCode = EditText(this@LiliyaProvisioningActivity).apply {
                hint = "Код активации"
                isSingleLine = true
            }
            addView(activationCode)

            activate = Button(this@LiliyaProvisioningActivity).apply {
                text = "Активировать"
                setOnClickListener { requestActivation() }
            }
            addView(activate)

            selectCredential = Button(this@LiliyaProvisioningActivity).apply {
                text = "Старый способ: импортировать доступ"
                setOnClickListener { launchProductAuthPicker() }
            }
            addView(selectCredential)
        }
    }

    private fun restoreProvisioningState() {
        if (app.hasFirstRunAcquisitionConfiguration()) {
            openRuntimeHost()
            return
        }

        when (val snapshot = app.observeActivation(::deliverActivationCompletion)) {
            ProductionAndroidActivationTaskSnapshot.Idle -> {
                activationInFlight = false
                when (app.restorePendingActivationConfiguration()) {
                    ProductionAndroidActivationResult.Activated,
                    ProductionAndroidActivationResult.AlreadyActivated -> {
                        openRuntimeHost()
                        return
                    }
                    ProductionAndroidActivationResult.ProductProfileRequired -> {
                        renderReadyForActivation("Требуется профиль продукта")
                        return
                    }
                    else -> Unit
                }
            }
            is ProductionAndroidActivationTaskSnapshot.InFlight -> {
                activationInFlight = true
                renderActivationInFlight()
                return
            }
            is ProductionAndroidActivationTaskSnapshot.Completed -> {
                activationInFlight = true
                renderActivationInFlight()
                deliverActivationCompletion(snapshot)
                return
            }
        }

        restoreImportState()
    }

    private fun requestActivation() {
        if (activationInFlight) return
        val code = activationCode.text?.toString()?.trim().orEmpty()
        if (code.isBlank()) {
            renderReadyForActivation("Введите код активации")
            return
        }
        activationInFlight = true
        renderActivationInFlight()
        when (app.requestActivation(code, ::deliverActivationCompletion)) {
            is ProductionAndroidActivationTaskRequestResult.Started -> {
                activationCode.text?.clear()
            }
            ProductionAndroidActivationTaskRequestResult.Busy -> restoreProvisioningState()
        }
    }

    private fun deliverActivationCompletion(
        completed: ProductionAndroidActivationTaskSnapshot.Completed
    ) {
        runOnUiThread {
            if (isFinishing || isDestroyed || isChangingConfigurations || stateSaved) return@runOnUiThread
            if (!app.consumeActivation(completed.requestId)) return@runOnUiThread
            activationInFlight = false
            when (val result = completed.result) {
                ProductionAndroidActivationResult.Activated,
                ProductionAndroidActivationResult.AlreadyActivated -> openRuntimeHost()
                ProductionAndroidActivationResult.ProductProfileRequired ->
                    renderReadyForActivation("Требуется профиль продукта")
                is ProductionAndroidActivationResult.ServiceRejected ->
                    renderReadyForActivation("Код активации отклонён")
                is ProductionAndroidActivationResult.TransportFailed ->
                    renderReadyForActivation("Не удалось связаться с сервером лицензии")
                ProductionAndroidActivationResult.Failed ->
                    renderReadyForActivation("Не удалось выполнить активацию")
            }
        }
    }

    private fun renderReadyForActivation(message: String) {
        status.text = message
        activationCode.visibility = View.VISIBLE
        activationCode.isEnabled = true
        activate.visibility = View.VISIBLE
        activate.isEnabled = true
        selectCredential.visibility = View.VISIBLE
        selectCredential.isEnabled = true
    }

    private fun renderActivationInFlight() {
        status.text = "Активация…"
        activationCode.isEnabled = false
        activate.isEnabled = false
        selectCredential.isEnabled = false
    }

    private fun restoreImportState() {
        if (app.hasProductAuthCredential()) {
            openRuntimeIfProductConfigured()
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
                ProductionAndroidProductAuthImportResult.AlreadyProvisioned -> openRuntimeIfProductConfigured()
                ProductionAndroidProductAuthImportResult.Rejected ->
                    renderReadyForImport("Файл доступа продукта отклонён")
                ProductionAndroidProductAuthImportResult.Failed ->
                    renderReadyForImport("Не удалось импортировать доступ продукта")
            }
        }
    }

    private fun openRuntimeIfProductConfigured() {
        if (app.hasFirstRunAcquisitionConfiguration()) {
            openRuntimeHost()
            return
        }
        when (app.configureInstalledAuthenticatedFirstRunProduct()) {
            ProductionAndroidFirstRunDeploymentBootstrapResult.Installed,
            ProductionAndroidFirstRunDeploymentBootstrapResult.AlreadyConfigured -> openRuntimeHost()
            ProductionAndroidFirstRunDeploymentBootstrapResult.ProductProfileRequired -> {
                status.text = "Требуется профиль продукта"
                activationCode.visibility = View.VISIBLE
                activationCode.isEnabled = false
                activate.visibility = View.VISIBLE
                activate.isEnabled = false
                selectCredential.visibility = View.GONE
                selectCredential.isEnabled = false
            }
            ProductionAndroidFirstRunDeploymentBootstrapResult.Failed -> {
                status.text = "Не удалось настроить профиль продукта"
                activationCode.visibility = View.VISIBLE
                activationCode.isEnabled = false
                activate.visibility = View.VISIBLE
                activate.isEnabled = false
                selectCredential.visibility = View.GONE
                selectCredential.isEnabled = false
            }
        }
    }

    private fun renderReadyForImport(message: String) {
        status.text = message
        activationCode.visibility = View.VISIBLE
        activationCode.isEnabled = true
        activate.visibility = View.VISIBLE
        activate.isEnabled = true
        selectCredential.isEnabled = true
        selectCredential.visibility = View.VISIBLE
    }

    private fun renderImportInFlight() {
        status.text = "Импорт доступа продукта…"
        activationCode.isEnabled = false
        activate.isEnabled = false
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
