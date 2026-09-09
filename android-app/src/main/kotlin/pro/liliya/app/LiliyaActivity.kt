package pro.liliya.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunKeyChoice
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunKeySecurity
import pro.liliya.android.runtime.ProductChatResult

class LiliyaActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var selectModel: Button
    private lateinit var keyControls: LinearLayout
    private lateinit var restoreDekId: EditText
    private lateinit var restoreDekGeneration: EditText

    private val app: LiliyaApplication
        get() = application as LiliyaApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        renderStartupOutcome(app.startApplicationRuntime())
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Legacy activity result API is intentionally bounded to this minimal host.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != LOCAL_MODEL_DOCUMENT_REQUEST) return
        if (resultCode != RESULT_OK) {
            status.text = "Выбор модели отменён"
            return
        }

        val uri = data?.data
        if (uri == null) {
            status.text = "Не удалось получить выбранную модель"
            return
        }

        selectModel.isEnabled = false
        status.text = "Импорт модели…"

        worker.execute {
            val result = ProductionAndroidLocalModelSelection.importSelected(
                directory = File(filesDir, "models"),
                openInput = { contentResolver.openInputStream(uri) }
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                selectModel.isEnabled = true
                when (result) {
                    is ProductionAndroidLocalModelSelectionResult.Selected -> {
                        selectModel.text = "Выбрать другую модель"
                        refreshConfigurationStatus()
                    }
                    ProductionAndroidLocalModelSelectionResult.EmptyDocument ->
                        status.text = "Выбранный файл модели пуст"
                    ProductionAndroidLocalModelSelectionResult.Failed ->
                        status.text = "Не удалось импортировать выбранную модель"
                }
            }
        }
    }

    private fun buildContent(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        root.addView(TextView(this).apply {
            text = "Liliya"
            textSize = 24f
            gravity = Gravity.CENTER_HORIZONTAL
        })

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(status)

        selectModel = Button(this).apply {
            text = if (ProductionAndroidLocalModelSelection.current() == null) {
                "Выбрать локальную модель"
            } else {
                "Выбрать другую модель"
            }
            visibility = View.GONE
            setOnClickListener { launchLocalModelPicker() }
        }
        root.addView(selectModel)

        keyControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        keyControls.addView(TextView(this).apply {
            text = "Ключ памяти: выберите способ явно"
        })

        keyControls.addView(Button(this).apply {
            text = "Создать: StrongBox"
            setOnClickListener {
                selectCreateOnceKey(AndroidProductRuntimeFirstRunKeySecurity.STRONGBOX)
            }
        })
        keyControls.addView(Button(this).apply {
            text = "Создать: защищённая среда (TEE)"
            setOnClickListener {
                selectCreateOnceKey(
                    AndroidProductRuntimeFirstRunKeySecurity.TRUSTED_ENVIRONMENT
                )
            }
        })
        keyControls.addView(Button(this).apply {
            text = "Создать: программный уровень"
            setOnClickListener {
                selectCreateOnceKey(AndroidProductRuntimeFirstRunKeySecurity.SOFTWARE)
            }
        })

        restoreDekId = EditText(this).apply {
            hint = "Exact DEK id для восстановления"
            setSingleLine(true)
        }
        keyControls.addView(restoreDekId)

        restoreDekGeneration = EditText(this).apply {
            hint = "Exact DEK generation"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        keyControls.addView(restoreDekGeneration)

        keyControls.addView(Button(this).apply {
            text = "Восстановить exact DEK"
            setOnClickListener { selectRestoreExactKey() }
        })

        root.addView(keyControls)

        transcript = TextView(this).apply {
            textSize = 16f
            text = ""
        }
        root.addView(
            ScrollView(this).apply { addView(transcript) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        input = EditText(this).apply {
            hint = "Сообщение"
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(false)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submit()
                    true
                } else {
                    false
                }
            }
        }
        root.addView(input)

        send = Button(this).apply {
            text = "Отправить"
            setOnClickListener { submit() }
        }
        root.addView(send)

        return root
    }

    private fun launchLocalModelPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, LOCAL_MODEL_DOCUMENT_REQUEST)
    }

    private fun selectCreateOnceKey(
        security: AndroidProductRuntimeFirstRunKeySecurity
    ) {
        val identity = UUID.randomUUID().toString()
        val result = ProductionAndroidFirstRunKeySelection.select(
            AndroidProductRuntimeFirstRunKeyChoice.CreateOnce(
                dekId = "first-run-$identity",
                protectorId = "first-run-protector-$identity",
                protectorGeneration = 1L,
                security = security
            )
        )
        renderKeySelectionResult(result)
    }

    private fun selectRestoreExactKey() {
        val dekId = restoreDekId.text?.toString()?.trim().orEmpty()
        val generation = restoreDekGeneration.text?.toString()?.trim()?.toLongOrNull()
        if (dekId.isBlank() || generation == null) {
            status.text = "Укажите exact DEK id и generation"
            return
        }

        val result = ProductionAndroidFirstRunKeySelection.select(
            AndroidProductRuntimeFirstRunKeyChoice.RestoreExact(
                dekId = dekId,
                dekGeneration = generation
            )
        )
        renderKeySelectionResult(result)
    }

    private fun renderKeySelectionResult(
        result: ProductionAndroidFirstRunKeySelectionResult
    ) {
        when (result) {
            is ProductionAndroidFirstRunKeySelectionResult.Selected ->
                refreshConfigurationStatus()
            ProductionAndroidFirstRunKeySelectionResult.AlreadySelected ->
                status.text = "Выбор ключа уже зафиксирован для этого запуска"
            ProductionAndroidFirstRunKeySelectionResult.Rejected ->
                status.text = "Выбор ключа отклонён"
        }
    }

    private fun refreshConfigurationStatus() {
        val modelSelected = ProductionAndroidLocalModelSelection.current() != null
        val keySelected = ProductionAndroidFirstRunKeySelection.current() != null
        status.text = when {
            !modelSelected && !keySelected ->
                "Требуется доверенная конфигурация запуска"
            modelSelected && !keySelected ->
                "Модель выбрана. Требуется явный выбор ключа памяти"
            !modelSelected && keySelected ->
                "Ключ памяти выбран. Требуется локальная модель"
            else ->
                "Модель и ключ выбраны. Требуются остальные параметры запуска"
        }
        keyControls.isEnabled = !keySelected
        for (index in 0 until keyControls.childCount) {
            keyControls.getChildAt(index).isEnabled = !keySelected
        }
    }

    private fun renderStartupOutcome(outcome: ProductionAndroidAppStartupOutcome) {
        when (outcome) {
            ProductionAndroidAppStartupOutcome.ConfigurationRequired ->
                renderState(ProductionAndroidAppRuntimeState.CONFIGURATION_REQUIRED)
            is ProductionAndroidAppStartupOutcome.Runtime -> renderState(outcome.state)
            is ProductionAndroidAppStartupOutcome.SourceRejected -> {
                status.text = "Конфигурация запуска отклонена"
                selectModel.visibility = View.GONE
                keyControls.visibility = View.GONE
                input.isEnabled = false
                send.isEnabled = false
            }
            is ProductionAndroidAppStartupOutcome.ProvisioningRejected -> {
                status.text = "Подготовка запуска отклонена"
                selectModel.visibility = View.GONE
                keyControls.visibility = View.GONE
                input.isEnabled = false
                send.isEnabled = false
            }
        }
    }

    private fun renderState(state: ProductionAndroidAppRuntimeState) {
        status.text = when (state) {
            ProductionAndroidAppRuntimeState.CONFIGURATION_REQUIRED -> ""
            ProductionAndroidAppRuntimeState.STARTING -> "Запуск…"
            ProductionAndroidAppRuntimeState.READY -> "Готова"
            ProductionAndroidAppRuntimeState.FAILED -> "Запуск отклонён"
            ProductionAndroidAppRuntimeState.CLOSED -> "Остановлена"
        }

        val configurationRequired =
            state == ProductionAndroidAppRuntimeState.CONFIGURATION_REQUIRED
        selectModel.visibility = if (configurationRequired) View.VISIBLE else View.GONE
        keyControls.visibility = if (configurationRequired) View.VISIBLE else View.GONE
        if (configurationRequired) {
            refreshConfigurationStatus()
        }

        val ready = state == ProductionAndroidAppRuntimeState.READY
        input.isEnabled = ready
        send.isEnabled = ready
    }

    private fun submit() {
        val message = input.text?.toString()?.trim().orEmpty()
        if (message.isBlank() || !send.isEnabled) return

        input.isEnabled = false
        send.isEnabled = false
        status.text = "Думаю…"

        worker.execute {
            val result = try {
                app.runtimeOwner.send(message)
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is ProductChatResult.Completed -> {
                        transcript.text = result.reply
                        input.text?.clear()
                        status.text = "Готова"
                    }
                    is ProductChatResult.Rejected -> {
                        status.text = "Запрос отклонён: ${result.reason.name}"
                    }
                    null -> status.text = "Внутренняя ошибка"
                }
                val ready = app.runtimeOwner.state() == ProductionAndroidAppRuntimeState.READY
                input.isEnabled = ready
                send.isEnabled = ready
            }
        }
    }

    private companion object {
        const val LOCAL_MODEL_DOCUMENT_REQUEST = 1001
    }
}
