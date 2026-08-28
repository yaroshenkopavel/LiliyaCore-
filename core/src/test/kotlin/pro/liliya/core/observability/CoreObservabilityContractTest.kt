package pro.liliya.core.observability

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.LogContextPropagation
import pro.liliya.core.logging.LogLevel
import pro.liliya.core.logging.StructuredLogger
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreObservabilityContractTest {
    @Test
    fun one_observation_emits_correlated_log_and_diagnostic_with_same_metadata() {
        val logWriter = InMemoryLogWriter()
        val diagnosticSink = InMemoryDiagnosticSink()
        val observability = CoreObservability(
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logWriter, clock = { 100L }) },
            diagnostics = DiagnosticRecorder(diagnosticSink, clock = { 200L })
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Integration",
            operation = "observe",
            metadata = mapOf("scope" to "foundation"),
            generator = CorrelationIdGenerator { "integration-correlation" }
        )

        observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "FOUNDATION_WARNING",
            message = "foundation observation",
            context = context,
            metadata = mapOf("target" to "runtime")
        )

        val log = logWriter.snapshot().single()
        val diagnostic = diagnosticSink.snapshot().single()

        assertEquals(LogLevel.WARN, log.level)
        assertEquals("FOUNDATION_WARNING", log.marker)
        assertEquals("integration-correlation", log.context.correlationId)
        assertEquals("runtime", log.metadata["target"])
        assertEquals("foundation", log.metadata["scope"])

        assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity)
        assertEquals("FOUNDATION_WARNING", diagnostic.code)
        assertEquals("integration-correlation", diagnostic.context.correlationId)
        assertEquals("runtime", diagnostic.metadata["target"])
        assertEquals("foundation", diagnostic.metadata["scope"])
    }

    @Test
    fun error_observation_preserves_throwable_in_both_channels() {
        val logWriter = InMemoryLogWriter()
        val diagnosticSink = InMemoryDiagnosticSink()
        val observability = CoreObservability(
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logWriter) },
            diagnostics = DiagnosticRecorder(diagnosticSink)
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Integration",
            operation = "error",
            generator = CorrelationIdGenerator { "integration-error" }
        )
        val failure = IllegalStateException("broken")

        observability.record(
            severity = DiagnosticSeverity.ERROR,
            code = "FOUNDATION_ERROR",
            message = "foundation failed",
            context = context,
            throwable = failure
        )

        val log = logWriter.snapshot().single()
        val diagnostic = diagnosticSink.snapshot().single()
        assertEquals("java.lang.IllegalStateException", log.throwableType)
        assertEquals("broken", log.throwableMessage)
        assertEquals("java.lang.IllegalStateException", diagnostic.throwableType)
        assertEquals("broken", diagnostic.throwableMessage)
    }
}
