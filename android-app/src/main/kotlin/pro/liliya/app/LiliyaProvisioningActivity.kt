package pro.liliya.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Launcher-side fresh-install activation surface.
 *
 * The normal user path accepts only an Activation Code. The code remains process-memory input for
 * one Application-scoped activation task and is cleared from the visible field after success.
 * The legacy Product Auth picker remains hidden for bounded acceptance/backward-compatibility
 * coverage and is not part of fresh-install activation. Runtime/chat ownership stays in
 * [LiliyaActivity].
 */
class LiliyaProvisioningActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var activationCode: EditText
    private lateinit var activateProduct: Button
    private lateinit var selectCredential: Button
    private var stateSaved = false

    private val app: LiliyaApplication
        get() = application as LiliyaApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val content = buildContent()
        setContentView(content)
        content.requestApplyInsets()
        restoreActivationState()
    }

    override fun onStart() {
        super.onStart()
        val restoredAfterSavedState = stateSaved
        stateSaved = false
        if (restoredAfterSavedState) restoreActivationState()
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
                text = "Liliya — активация"
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
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                isSingleLine = true
            }
            addView(activationCode)

            activateProduct = Button(this@LiliyaProvisioningActivity).apply {
                text = "Активировать"
                setOnClickListener { beginActivation() }
            }
            addView(activateProduct)

            selectCredential = Button(this@LiliyaProvisioningActivity).apply {
                text = "Импортировать доступ продукта"
                visibility = View.GONE
                setOnClickListener { launchProductAuthPicker() }
            }
            addView(selectCredential)
        }
    }

    private fun beginActivation() {
        val code = activationCode.text?.toString()?.trim().orEmpty()
        if (code.isBlank()) {
            renderReadyForActivation("Введите код активации")
            return
        }

        renderActivationInFlight()
        when (
            app.requestActivation(
                activationCode = code,
                listener = ::deliverActivationCompletion
            )
        ) {
            is ProductionAndroidActivationTaskRequestResult.Started -> Unit
            ProductionAndroidActivationTaskRequestResult.Busy -> restoreActivationState()
        }
    }

    private fun restoreActivationState() {
        when (val snapshot = app.observeActivation(::deliverActivationCompletion)) {
            ProductionAndroidActivationTaskSnapshot.Idle ->
                renderReadyForActivation("Введите код активации")
            is ProductionAndroidActivationTaskSnapshot.InFlight ->
                renderActivationInFlight()
            is ProductionAndroidActivationTaskSnapshot.Completed ->
                deliverActivationCompletion(snapshot)
        }
    }

    private fun deliverActivationCompletion(
        completed: ProductionAndroidActivationTaskSnapshot.Completed
    ) {
        runOnUiThread {
            if (isFinishing || isDestroyed || isChangingConfigurations || stateSaved) {
                return@runOnUiThread
            }
            if (!app.consumeActivation(completed.requestId)) return@runOnUiThread

            when (val result = completed.result) {
                ProductionAndroidActivationResult.ProfileRequired ->
                    renderReadyForActivation("Профиль активации не настроен")

                is ProductionAndroidActivationResult.Rejected ->
                    renderReadyForActivation(
                        when (result.reason) {
                            "INVALID_CODE" -> "Код активации недействителен"
                            "EXPIRED_CODE" -> "Срок действия кода активации истёк"
                            "CODE_EXHAUSTED" -> "Код активации уже использован"
                            else -> "Код активации отклонён"
                        }
                    )

                is ProductionAndroidActivationResult.TransportFailed ->
                    renderReadyForActivation("Не удалось связаться с сервером активации")

                ProductionAndroidActivationResult.Failed ->
                    renderReadyForActivation("Не удалось выполнить активацию")

                is ProductionAndroidActivationResult.FirstRun -> {
                    when (result.result) {
                        is ProductionAndroidFirstRunAcquisitionResult.Installed,
                        ProductionAndroidFirstRunAcquisitionResult.AlreadyConfigured -> {
                            activationCode.setText("")
                            openRuntimeHost()
                        }

                        ProductionAndroidFirstRunAcquisitionResult.LocalModelRequired ->
                            renderReadyForActivation(
                                "Код принят. Для завершения требуется локальная модель"
                            )

                        ProductionAndroidFirstRunAcquisitionResult.HostConfigurationRequired ->
                            renderReadyForActivation("Требуется конфигурация продукта")

                        ProductionAndroidFirstRunAcquisitionResult.TrustVerificationRejected ->
                            renderReadyForActivation(
                                "Полученная лицензия не прошла проверку подписи"
                            )

                        is ProductionAndroidFirstRunAcquisitionResult.AuthorityRejected ->
                            renderReadyForActivation(
                                "Лицензия получена, но запуск не разрешён политикой"
                            )

                        is ProductionAndroidFirstRunAcquisitionResult.ProductInputRejected ->
                            renderReadyForActivation(
                                "Лицензия получена, но конфигурация продукта отклонена"
                            )

                        is ProductionAndroidFirstRunAcquisitionResult.LicenseServiceRejected,
                        is ProductionAndroidFirstRunAcquisitionResult.LicenseAcquisitionFailed,
                        ProductionAndroidFirstRunAcquisitionResult.Failed ->
                            renderReadyForActivation("Не удалось завершить активацию")
                    }
                }
            }
        }
    }

    private fun renderReadyForActivation(message: String) {
        status.text = message
        activationCode.isEnabled = true
        activationCode.visibility = View.VISIBLE
        activateProduct.isEnabled = true
        activateProduct.visibility = View.VISIBLE
        selectCredential.visibility = View.GONE
    }

    private fun renderActivationInFlight() {
        status.text = "Проверка кода и получение лицензии…"
        activationCode.isEnabled = false
        activateProduct.isEnabled = false
        selectCredential.visibility = View.GONE
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
                selectCredential.visibility = View.GONE
                selectCredential.isEnabled = false
            }
            ProductionAndroidFirstRunDeploymentBootstrapResult.Failed -> {
                status.text = "Не удалось настроить профиль продукта"
                selectCredential.visibility = View.GONE
                selectCredential.isEnabled = false
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
