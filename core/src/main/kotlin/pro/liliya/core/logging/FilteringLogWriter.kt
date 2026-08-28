package pro.liliya.core.logging

class FilteringLogWriter(
    private val minimumLevel: LogLevel,
    private val delegate: LogWriter
) : LogWriter {

    override fun write(event: LogEvent) {
        if (event.level.ordinal >= minimumLevel.ordinal) {
            delegate.write(event)
        }
    }
}
