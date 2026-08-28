package pro.liliya.core.diagnostics

class SafeDiagnosticSink(
    private val delegate: DiagnosticSink,
    private val onFailure: (DiagnosticFailure) -> Unit
) : DiagnosticSink {

    override fun record(event: DiagnosticEvent) {
        try {
            delegate.record(event)
        } catch (error: Throwable) {
            onFailure(
                DiagnosticFailure(
                    sequence = event.sequence,
                    code = event.code,
                    severity = event.severity,
                    sinkType = delegate::class.java.name,
                    throwableType = error::class.java.name,
                    throwableMessage = error.message
                )
            )
        }
    }
}
