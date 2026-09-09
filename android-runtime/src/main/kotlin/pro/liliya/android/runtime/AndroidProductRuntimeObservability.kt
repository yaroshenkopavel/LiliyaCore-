package pro.liliya.android.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import pro.liliya.core.diagnostics.DiagnosticEvent
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.DiagnosticSink
import pro.liliya.core.logging.LogEvent
import pro.liliya.core.logging.LogWriter
import pro.liliya.core.logging.LoggerFactory
import pro.liliya.core.observability.LoggerProvider

/**
 * Bounded production observability ownership for the Android product runtime.
 *
 * Runtime Observability != Logcat.
 * Runtime Observability != Network Telemetry.
 * Runtime Observability != Test In-Memory Storage.
 */
class AndroidProductRuntimeObservability private constructor(
    private val writer: AndroidProductRuntimeBoundedEventFileWriter
) {
    internal val diagnostics: DiagnosticRecorder =
        DiagnosticRecorder(DiagnosticSink(writer::writeDiagnostic))

    internal val loggerProvider: LoggerProvider =
        LoggerProvider(LoggerFactory::create)

    fun installLoggerWriter() {
        LoggerFactory.installWriter(LogWriter(writer::writeLog))
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 2L * 1024L * 1024L

        fun create(
            root: File,
            maxBytes: Long = DEFAULT_MAX_BYTES
        ): AndroidProductRuntimeObservability =
            AndroidProductRuntimeObservability(
                AndroidProductRuntimeBoundedEventFileWriter(
                    current = File(root, "liliya-events.log"),
                    previous = File(root, "liliya-events.previous.log"),
                    maxBytes = maxBytes
                )
            )
    }
}

internal class AndroidProductRuntimeBoundedEventFileWriter(
    private val current: File,
    private val previous: File,
    private val maxBytes: Long
) {
    init {
        require(maxBytes > 0L)
    }

    @Synchronized
    fun writeLog(event: LogEvent) {
        append(
            listOf(
                "log",
                event.timestampMillis.toString(),
                event.sequence.toString(),
                event.level.name,
                event.marker,
                event.message,
                event.context.module,
                event.context.component,
                event.context.operation,
                event.context.correlationId.orEmpty(),
                event.context.parentCorrelationId.orEmpty(),
                event.threadName,
                event.throwableType.orEmpty(),
                event.throwableMessage.orEmpty(),
                metadata(event.metadata)
            )
        )
    }

    @Synchronized
    fun writeDiagnostic(event: DiagnosticEvent) {
        append(
            listOf(
                "diagnostic",
                event.timestampMillis.toString(),
                event.sequence.toString(),
                event.severity.name,
                event.code,
                event.message,
                event.context.module,
                event.context.component,
                event.context.operation,
                event.context.correlationId.orEmpty(),
                event.context.parentCorrelationId.orEmpty(),
                event.throwableType.orEmpty(),
                event.throwableMessage.orEmpty(),
                metadata(event.metadata)
            )
        )
    }

    private fun append(fields: List<String>) {
        val line = fields.joinToString(separator = "\t", postfix = "\n", transform = ::escape)
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > maxBytes) return

        val parent = current.parentFile ?: return
        if (!parent.exists() && !parent.mkdirs()) return
        if (!parent.isDirectory) return

        if (current.exists() && current.length() + bytes.size > maxBytes) {
            rotate()
        }

        try {
            current.appendBytes(bytes)
        } catch (_: Exception) {
            // Observability must not become product-runtime failure authority.
        }
    }

    private fun rotate() {
        previous.delete()
        if (!current.exists()) return
        try {
            Files.move(
                current.toPath(),
                previous.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            current.delete()
        }
    }

    private fun metadata(values: Map<String, String>): String =
        values.toSortedMap().entries.joinToString(separator = ",") {
            escape(it.key) + "=" + escape(it.value)
        }

    private fun escape(value: String): String =
        buildString(value.length) {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(ch)
                }
            }
        }
}
