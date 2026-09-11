package pro.liliya.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import pro.liliya.android.runtime.ProductChatResult

class LiliyaActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var conversation: ProductConversationTranscript
    private var requestInFlight = false

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var transcriptScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var selectModel: Button

    private val app: LiliyaApplication
        get() = application as LiliyaApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conversation = restoreConversation(savedInstanceState)
        val restoredDraft = restoreInputDraft(savedInstanceState)
        setContentView(buildContent())
        if (restoredDraft.isNotEmpty()) {
            input.setText(restoredDraft)
            input.setSelection(restoredDraft.length)
        }
        renderStartupOutcome(app.startApplicationRuntime())
        revealLatestTranscriptTurn()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val snapshot = conversation.snapshotWithinBudget(
            maxEntries = TRANSCRIPT_SAVED_STATE_MAX_ENTRIES,
            maxUtf8Bytes = TRANSCRIPT_SAVED_STATE_MAX_UTF8_BYTES
        )
        outState.putStringArrayList(
            TRANSCRIPT_SPEAKERS_STATE,
            ArrayList(snapshot.speakers)
        )
        outState.putStringArrayList(
            TRANSCRIPT_MESSAGES_STATE,
            ArrayList(snapshot.messages)
        )
        if (!requestInFlight && ::input.isInitialized) {
            val draft = ProductConversationDraftState.snapshotWithinBudget(
                draft = input.text?.toString().orEmpty(),
                maxUtf8Bytes = INPUT_DRAFT_SAVED_STATE_MAX_UTF8_BYTES
            )
            if (draft.isNotEmpty()) {
                outState.putString(INPUT_DRAFT_STATE, draft)
            }
        }
        super.onSaveInstanceState(outState)
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
                        status.text = "Модель выбрана. Требуются остальные параметры запуска"
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

        transcript = TextView(this).apply {
            textSize = 16f
            text = conversation.render()
        }
        transcriptScroll = ScrollView(this).apply {
            addView(transcript)
        }
        root.addView(
            transcriptScroll,
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

    private fun restoreConversation(state: Bundle?): ProductConversationTranscript {
        if (state == null) return ProductConversationTranscript()
        val speakers = state.getStringArrayList(TRANSCRIPT_SPEAKERS_STATE)
            ?: return ProductConversationTranscript()
        val messages = state.getStringArrayList(TRANSCRIPT_MESSAGES_STATE)
            ?: return ProductConversationTranscript()
        return ProductConversationTranscript.restore(
            ProductConversationTranscriptSnapshot(
                speakers = speakers,
                messages = messages
            )
        )
    }

    private fun restoreInputDraft(state: Bundle?): String =
        ProductConversationDraftState.restoreWithinBudget(
            savedDraft = state?.getString(INPUT_DRAFT_STATE),
            maxUtf8Bytes = INPUT_DRAFT_SAVED_STATE_MAX_UTF8_BYTES
        )

    private fun renderConversationAndRevealLatest() {
        transcript.text = conversation.render()
        revealLatestTranscriptTurn()
    }

    private fun revealLatestTranscriptTurn() {
        transcriptScroll.post {
            if (!isFinishing && !isDestroyed) {
                transcriptScroll.fullScroll(View.FOCUS_DOWN)
            }
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
                input.isEnabled = false
                send.isEnabled = false
            }
            is ProductionAndroidAppStartupOutcome.ProvisioningRejected -> {
                status.text = "Подготовка запуска отклонена"
                selectModel.visibility = View.GONE
                input.isEnabled = false
                send.isEnabled = false
            }
        }
    }

    private fun renderState(state: ProductionAndroidAppRuntimeState) {
        status.text = when (state) {
            ProductionAndroidAppRuntimeState.CONFIGURATION_REQUIRED ->
                if (ProductionAndroidLocalModelSelection.current() == null) {
                    "Требуется доверенная конфигурация запуска"
                } else {
                    "Модель выбрана. Требуются остальные параметры запуска"
                }
            ProductionAndroidAppRuntimeState.STARTING -> "Запуск…"
            ProductionAndroidAppRuntimeState.READY -> "Готова"
            ProductionAndroidAppRuntimeState.FAILED -> "Запуск отклонён"
            ProductionAndroidAppRuntimeState.CLOSED -> "Остановлена"
        }
        val ready = state == ProductionAndroidAppRuntimeState.READY
        selectModel.visibility =
            if (state == ProductionAndroidAppRuntimeState.CONFIGURATION_REQUIRED) View.VISIBLE
            else View.GONE
        input.isEnabled = ready
        send.isEnabled = ready
    }

    private fun submit() {
        val message = input.text?.toString()?.trim().orEmpty()
        if (message.isBlank() || !send.isEnabled) return

        conversation.appendUser(message)
        renderConversationAndRevealLatest()
        requestInFlight = true
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
                requestInFlight = false
                when (result) {
                    is ProductChatResult.Completed -> {
                        conversation.appendLiliya(result.reply)
                        renderConversationAndRevealLatest()
                        input.text?.clear()
                        status.text = "Готова"
                    }
                    is ProductChatResult.Rejected -> {
                        if (conversation.rollbackLastUser(message)) {
                            renderConversationAndRevealLatest()
                        }
                        status.text = "Запрос отклонён: ${result.reason.name}"
                    }
                    null -> {
                        if (conversation.rollbackLastUser(message)) {
                            renderConversationAndRevealLatest()
                        }
                        status.text = "Внутренняя ошибка"
                    }
                }
                val ready = app.runtimeOwner.state() == ProductionAndroidAppRuntimeState.READY
                input.isEnabled = ready
                send.isEnabled = ready
            }
        }
    }

    private companion object {
        const val LOCAL_MODEL_DOCUMENT_REQUEST = 1001
        const val TRANSCRIPT_SPEAKERS_STATE = "liliya.transcript.speakers"
        const val TRANSCRIPT_MESSAGES_STATE = "liliya.transcript.messages"
        const val INPUT_DRAFT_STATE = "liliya.input.draft"
        const val TRANSCRIPT_SAVED_STATE_MAX_ENTRIES = 64
        const val TRANSCRIPT_SAVED_STATE_MAX_UTF8_BYTES = 48 * 1024
        const val INPUT_DRAFT_SAVED_STATE_MAX_UTF8_BYTES = 8 * 1024
    }
}
