package pro.liliya.core.logging

class CompositeLogWriter(
    private val writers: List<LogWriter>
) : LogWriter {

    override fun write(event: LogEvent) {
        var firstFailure: Throwable? = null

        writers.forEach { writer ->
            try {
                writer.write(event)
            } catch (error: Throwable) {
                if (firstFailure == null) {
                    firstFailure = error
                }
            }
        }

        firstFailure?.let { throw LogWriterException(it) }
    }
}

class LogWriterException(
    cause: Throwable
) : RuntimeException("One or more log writers failed", cause)
