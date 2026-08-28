package pro.liliya.core.logging

class InMemoryLogWriter : LogWriter {
    private val events = mutableListOf<LogEvent>()

    override fun write(event: LogEvent) {
        synchronized(events) {
            events += event
        }
    }

    fun snapshot(): List<LogEvent> =
        synchronized(events) {
            events.toList()
        }

    fun clear() {
        synchronized(events) {
            events.clear()
        }
    }
}
