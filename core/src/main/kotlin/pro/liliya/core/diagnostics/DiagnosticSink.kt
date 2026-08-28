package pro.liliya.core.diagnostics

fun interface DiagnosticSink {
    fun record(event: DiagnosticEvent)
}
