package pro.liliya.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import pro.liliya.android.runtime.ProductChatResult

class LiliyaActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var input: EditText
    private lateinit var send: Button

    private val app: LiliyaApplication
        get() = application as LiliyaApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        renderState(app.startRuntime())
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
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

    private fun renderState(state: ProductionAndroidAppRuntimeState) {
        status.text = when (state) {
            ProductionAndroidAppRuntimeState.CONFIGURATION_REQUIRED ->
                "Требуется доверенная конфигурация запуска"
            ProductionAndroidAppRuntimeState.STARTING -> "Запуск…"
            ProductionAndroidAppRuntimeState.READY -> "Готова"
            ProductionAndroidAppRuntimeState.FAILED -> "Запуск отклонён"
            ProductionAndroidAppRuntimeState.CLOSED -> "Остановлена"
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
}
