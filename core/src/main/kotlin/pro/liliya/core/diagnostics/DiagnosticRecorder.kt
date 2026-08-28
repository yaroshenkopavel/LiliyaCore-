package pro.liliya.core.diagnostics

import pro.liliya.core.logging.LogContext

class DiagnosticRecorder(
    private val sink: DiagnosticSink,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun record(
        severity: DiagnosticSeverity,
        code: String,
        message: String,
        context: LogContext,
        metadata: Map<String, String> = emptyMap(),
        throwable: Throwable? = null
    ) {
        sink.record(
            DiagnosticEvent(
                timestampMillis = clock(),
                sequence = GlobalDiagnosticSequence.next(),
                severity = severity,
                code = code,
                message = message,
                context = context,
                metadata = (context.metadata + metadata).toMap(),
                throwableType = throwable?.javaClass?.name,
                throwableMessage = throwable?.message
            )
        )
    }
}
