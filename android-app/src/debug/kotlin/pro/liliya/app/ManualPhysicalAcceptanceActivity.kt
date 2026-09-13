package pro.liliya.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import pro.liliya.android.llamacppengine.ManualPhysicalLlamaSession

/** Debug-only, user-driven physical ARM64 llama.cpp acceptance UI. */
class ManualPhysicalAcceptanceActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var selectModel: Button
    private lateinit var prepare: Button
    private lateinit var input: EditText
    private lateinit var send: Button
    private var selectedModel: File? = null
    private var session: ManualPhysicalLlamaSession? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        render("Выберите локальную модель GGUF", canPrepare = false, canChat = false)
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != MODEL_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return render("Не удалось получить выбранный файл", false, false)
        busy = true
        render("Импорт модели…", canPrepare = false, canChat = false)
        Thread({
            val result = runCatching {
                val directory = File(filesDir, "manual-physical-model").apply { mkdirs() }
                val target = File(directory, "selected-model.gguf")
                contentResolver.openInputStream(uri)?.use { source ->
                    target.outputStream().buffered().use { destination -> source.copyTo(destination) }
                } ?: error("Не удалось открыть выбранный файл")
                require(target.length() > 0L) { "Выбранный файл пуст" }
                target
            }
            runOnUiThread {
                busy = false
                result.onSuccess {
                    selectedModel = it
                    render("Модель импортирована (${it.length() / (1024 * 1024)} МБ)", true, false)
                }.onFailure {
                    render("Ошибка импорта: ${it.message ?: it.javaClass.simpleName}", false, false)
                }
            }
        }, "liliya-manual-model-import").start()
    }

    private fun chooseModel() {
        if (busy) return
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            },
            MODEL_REQUEST
        )
    }

    private fun prepareRuntime() {
        val model = selectedModel ?: return
        if (busy) return
        busy = true
        session?.close()
        session = null
        render("Загрузка GGUF настоящим ARM64 llama.cpp…", false, false)
        Thread({
            val result = ManualPhysicalLlamaSession.load(model)
            runOnUiThread {
                busy = false
                result.onSuccess {
                    session = it
                    render("READY — ARM64 модель загружена", false, true)
                }.onFailure {
                    render("Ошибка загрузки: ${it.message ?: it.javaClass.simpleName}", true, false)
                }
            }
        }, "liliya-manual-arm64-load").start()
    }

    private fun sendMessage() {
        val exactSession = session ?: return
        val message = input.text.toString().trim()
        if (message.isEmpty() || busy) return
        busy = true
        append("Вы: $message")
        input.text.clear()
        render("Liliya думает…", false, false)
        Thread({
            val result = exactSession.infer(message)
            runOnUiThread {
                busy = false
                result.onSuccess { append("Liliya: $it") }
                    .onFailure { append("Ошибка: ${it.message ?: it.javaClass.simpleName}") }
                render(if (result.isSuccess) "READY" else "Ошибка запроса", false, true)
            }
        }, "liliya-manual-arm64-infer").start()
    }

    private fun render(message: String, canPrepare: Boolean, canChat: Boolean) {
        status.text = message
        selectModel.isEnabled = !busy
        prepare.visibility = if (selectedModel != null && session == null) View.VISIBLE else View.GONE
        prepare.isEnabled = canPrepare && !busy
        input.isEnabled = canChat && !busy
        send.isEnabled = canChat && !busy
    }

    private fun append(message: String) {
        transcript.append(if (transcript.text.isEmpty()) message else "\n\n$message")
    }

    private fun buildContent(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(TextView(context).apply {
                text = "Liliya — физический ARM64 тест"
                textSize = 26f
                gravity = Gravity.CENTER
            })
            status = TextView(context).apply { textSize = 17f; setPadding(0, dp(24), 0, dp(16)) }
            addView(status)
            selectModel = Button(context).apply {
                text = "Выбрать локальную модель GGUF"
                setOnClickListener { chooseModel() }
            }
            addView(selectModel)
            prepare = Button(context).apply {
                text = "Подготовить запуск"
                visibility = View.GONE
                setOnClickListener { prepareRuntime() }
            }
            addView(prepare)
            transcript = TextView(context).apply { textSize = 16f }
            addView(ScrollView(context).apply { addView(transcript) }, LinearLayout.LayoutParams(-1, 0, 1f))
            input = EditText(context).apply { hint = "Сообщение"; maxLines = 4 }
            addView(input)
            send = Button(context).apply { text = "Отправить"; setOnClickListener { sendMessage() } }
            addView(send)
        }
    }

    private companion object {
        const val MODEL_REQUEST = 2101
    }
}
