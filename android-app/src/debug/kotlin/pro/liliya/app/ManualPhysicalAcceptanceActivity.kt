package pro.liliya.app

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import pro.liliya.android.llamacppengine.ManualPhysicalLlamaSession

/** Debug-only, user-driven physical ARM64 llama.cpp acceptance UI. */
class ManualPhysicalAcceptanceActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var selectModel: Button
    private lateinit var prepare: Button
    private lateinit var exportEvidence: Button
    private lateinit var input: EditText
    private lateinit var send: Button
    private var selectedModel: File? = null
    private var selectedModelSha256: String? = null
    private var session: ManualPhysicalLlamaSession? = null
    private var busy = false
    private val evidence = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        recordEvidence("schema=liliya-manual-physical-evidence-v1")
        recordEvidence("source_sha=${BuildConfig.MANUAL_PHYSICAL_GIT_SHA}")
        recordEvidence("sdk=${Build.VERSION.SDK_INT}")
        recordEvidence("supported_abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
        recordEvidence("device=${Build.MANUFACTURER}/${Build.MODEL}")
        recordEvidence("prompt_format_policy=MODEL_DEFAULT_CHAT_TEMPLATE")
        render("Выберите локальную модель GGUF", canPrepare = false, canChat = false)
    }

    override fun onDestroy() {
        recordEvidence("SESSION_DESTROY")
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
        recordEvidence("MODEL_IMPORT_START")
        Thread({
            val result = runCatching {
                val directory = File(filesDir, "manual-physical-model").apply { mkdirs() }
                val target = File(directory, "selected-model.gguf")
                val digest = MessageDigest.getInstance("SHA-256")
                contentResolver.openInputStream(uri)?.use { source ->
                    target.outputStream().buffered().use { destination ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                            destination.write(buffer, 0, count)
                        }
                    }
                } ?: error("Не удалось открыть выбранный файл")
                require(target.length() > 0L) { "Выбранный файл пуст" }
                target to digest.digest().joinToString("") { "%02x".format(it) }
            }
            runOnUiThread {
                busy = false
                result.onSuccess { (file, sha256) ->
                    selectedModel = file
                    selectedModelSha256 = sha256
                    recordEvidence("MODEL_IMPORTED bytes=${file.length()} sha256=$sha256")
                    render("Модель импортирована (${file.length() / (1024 * 1024)} МБ)", true, false)
                }.onFailure {
                    recordEvidence("MODEL_IMPORT_FAILED type=${it.javaClass.simpleName}")
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
        recordEvidence("MODEL_LOAD_START bytes=${model.length()} sha256=${selectedModelSha256 ?: "unknown"}")
        render("Загрузка GGUF настоящим ARM64 llama.cpp…", false, false)
        Thread({
            val result = ManualPhysicalLlamaSession.load(model)
            runOnUiThread {
                busy = false
                result.onSuccess {
                    session = it
                    recordEvidence("READY")
                    render("READY — ARM64 модель загружена", false, true)
                }.onFailure {
                    recordEvidence("MODEL_LOAD_FAILED type=${it.javaClass.simpleName}")
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
        recordEvidence("INFER_START prompt_chars=${message.length}")
        render("Liliya думает…", false, false)
        Thread({
            val result = exactSession.infer(message)
            runOnUiThread {
                busy = false
                result.onSuccess {
                    recordEvidence("INFER_SUCCESS output_chars=${it.length}")
                    append("Liliya: $it")
                }.onFailure {
                    recordEvidence("INFER_FAILED type=${it.javaClass.simpleName}")
                    append("Ошибка: ${it.message ?: it.javaClass.simpleName}")
                }
                render(if (result.isSuccess) "READY" else "Ошибка запроса", false, true)
            }
        }, "liliya-manual-arm64-infer").start()
    }

    private fun exportEvidence() {
        if (busy) return
        recordEvidence("EVIDENCE_EXPORT_REQUEST")
        val fileName = "liliya-physical-evidence-${System.currentTimeMillis()}.txt"
        val payload = synchronized(evidence) { evidence.joinToString(separator = "\n", postfix = "\n") }
        val result = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Не удалось создать evidence-файл")
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(payload) }
                ?: error("Не удалось записать evidence-файл")
            fileName
        }
        result.onSuccess {
            append("Evidence сохранён: Download/$it")
        }.onFailure {
            append("Ошибка evidence: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun render(message: String, canPrepare: Boolean, canChat: Boolean) {
        status.text = message
        selectModel.isEnabled = !busy
        prepare.visibility = if (selectedModel != null && session == null) View.VISIBLE else View.GONE
        prepare.isEnabled = canPrepare && !busy
        exportEvidence.isEnabled = !busy
        input.isEnabled = canChat && !busy
        send.isEnabled = canChat && !busy
    }

    private fun append(message: String) {
        transcript.append(if (transcript.text.isEmpty()) message else "\n\n$message")
    }

    private fun recordEvidence(event: String) {
        synchronized(evidence) {
            evidence += "${Instant.now()} $event"
        }
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
            exportEvidence = Button(context).apply {
                text = "Сохранить evidence в Download"
                setOnClickListener { exportEvidence() }
            }
            addView(exportEvidence)
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
