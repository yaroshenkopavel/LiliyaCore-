package pro.liliya.core.logging

class SafeLogWriter(
    private val delegate: LogWriter,
    private val onFailure: (Throwable, LogEvent) -> Unit = { _, _ -> }
) : LogWriter {

    override fun write(event: LogEvent) {
        try {
            delegate.write(event)
        } catch (error: Throwable) {
            onFailure(error, event)
        }
    }
}
