package pro.liliya.core.diagnostics

import java.util.concurrent.CopyOnWriteArrayList

class InMemoryDiagnosticSink : DiagnosticSink {
    private val events = CopyOnWriteArrayList<DiagnosticEvent>()

    override fun record(event: DiagnosticEvent) {
        events += event
    }

    fun snapshot(): List<DiagnosticEvent> = events.toList()

    fun clear() {
        events.clear()
    }
}
