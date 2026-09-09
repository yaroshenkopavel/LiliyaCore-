package pro.liliya.android.runtime

import java.io.File
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import pro.liliya.core.diagnostics.DiagnosticEvent
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogEvent
import pro.liliya.core.logging.LogLevel

class AndroidProductRuntimeObservabilityContractTest {
    private val roots = mutableListOf<File>()

    @After
    fun cleanup() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun exact_log_and_diagnostic_events_are_written_with_escaped_lines() {
        val root = Files.createTempDirectory("liliya-runtime-observability").toFile()
            .also(roots::add)
        val current = File(root, "current.log")
        val writer = AndroidProductRuntimeBoundedEventFileWriter(
            current = current,
            previous = File(root, "previous.log"),
            maxBytes = 4096
        )
        val context = LogContext("RUNTIME", "Host", "startup")

        writer.writeLog(
            LogEvent(
                timestampMillis = 1,
                sequence = 1,
                level = LogLevel.INFO,
                context = context,
                marker = "READY",
                message = "line\nvalue",
                threadName = "test"
            )
        )
        writer.writeDiagnostic(
            DiagnosticEvent(
                timestampMillis = 2,
                sequence = 2,
                severity = DiagnosticSeverity.WARNING,
                code = "BOUNDARY",
                message = "diagnostic",
                context = context
            )
        )

        val text = current.readText()
        assertTrue("log\t1\t1\tINFO\tREADY\tline\\nvalue" in text)
        assertTrue("diagnostic\t2\t2\tWARNING\tBOUNDARY" in text)
    }

    @Test
    fun rotation_retains_only_current_and_previous_within_bound() {
        val root = Files.createTempDirectory("liliya-runtime-observability-rotate").toFile()
            .also(roots::add)
        val current = File(root, "current.log")
        val previous = File(root, "previous.log")
        val writer = AndroidProductRuntimeBoundedEventFileWriter(
            current = current,
            previous = previous,
            maxBytes = 180
        )
        val context = LogContext("RUNTIME", "Host", "startup")

        repeat(20) { index ->
            writer.writeLog(
                LogEvent(
                    timestampMillis = index.toLong(),
                    sequence = index.toLong() + 1,
                    level = LogLevel.INFO,
                    context = context,
                    marker = "EVENT",
                    message = "payload-$index-xxxxxxxxxxxxxxxx",
                    threadName = "test"
                )
            )
        }

        assertTrue(current.exists())
        assertTrue(current.length() <= 180)
        assertTrue(previous.exists())
        assertTrue(previous.length() <= 180)
    }

    @Test
    fun oversized_event_is_dropped_without_violating_bound() {
        val root = Files.createTempDirectory("liliya-runtime-observability-large").toFile()
            .also(roots::add)
        val current = File(root, "current.log")
        val writer = AndroidProductRuntimeBoundedEventFileWriter(
            current = current,
            previous = File(root, "previous.log"),
            maxBytes = 64
        )

        writer.writeLog(
            LogEvent(
                timestampMillis = 1,
                sequence = 1,
                level = LogLevel.INFO,
                context = LogContext("RUNTIME", "Host", "startup"),
                marker = "EVENT",
                message = "x".repeat(1024),
                threadName = "test"
            )
        )

        assertFalse(current.exists())
    }
}
