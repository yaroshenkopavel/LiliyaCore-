package pro.liliya.core.logging

class StructuredLogger(
    private val context: LogContext,
    private val writer: LogWriter,
    private val clock: () -> Long = System::currentTimeMillis
) : Logger {

    override fun trace(marker: String, message: String) =
        write(LogLevel.TRACE, marker, message, null)

    override fun debug(marker: String, message: String) =
        write(LogLevel.DEBUG, marker, message, null)

    override fun info(marker: String, message: String) =
        write(LogLevel.INFO, marker, message, null)

    override fun warn(marker: String, message: String) =
        write(LogLevel.WARN, marker, message, null)

    override fun error(marker: String, message: String, throwable: Throwable?) =
        write(LogLevel.ERROR, marker, message, throwable)

    override fun fatal(marker: String, message: String, throwable: Throwable?) =
        write(LogLevel.FATAL, marker, message, throwable)

    private fun write(
        level: LogLevel,
        marker: String,
        message: String,
        throwable: Throwable?
    ) {
        writer.write(
            LogEvent(
                timestampMillis = clock(),
                sequence = GlobalLogSequence.next(),
                level = level,
                context = context,
                marker = marker,
                message = message,
                threadName = Thread.currentThread().name,
                throwableType = throwable?.javaClass?.name,
                throwableMessage = throwable?.message
            )
        )
    }
}
