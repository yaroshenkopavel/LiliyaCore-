package pro.liliya.core.observability

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.Logger

fun interface LoggerProvider {
    fun create(context: LogContext): Logger
}

class CoreObservability(
    private val loggerProvider: LoggerProvider,
    private val diagnostics: DiagnosticRecorder
) {
    fun record(
        severity: DiagnosticSeverity,
        code: String,
        message: String,
        context: LogContext,
        metadata: Map<String, String> = emptyMap(),
        throwable: Throwable? = null
    ) {
        val enrichedContext = context.copy(
            metadata = (context.metadata + metadata).toMap()
        )
        val logger = loggerProvider.create(enrichedContext)

        when (severity) {
            DiagnosticSeverity.INFO -> logger.info(code, message)
            DiagnosticSeverity.WARNING -> logger.warn(code, message)
            DiagnosticSeverity.ERROR -> logger.error(code, message, throwable)
            DiagnosticSeverity.CRITICAL -> logger.fatal(code, message, throwable)
        }

        diagnostics.record(
            severity = severity,
            code = code,
            message = message,
            context = enrichedContext,
            throwable = throwable
        )
    }
}
