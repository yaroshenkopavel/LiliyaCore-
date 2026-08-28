package pro.liliya.core.logging

object LoggerFactory {
    @Volatile
    private var writer: LogWriter = InMemoryLogWriter()

    fun installWriter(newWriter: LogWriter) {
        writer = newWriter
    }

    fun create(context: LogContext): Logger =
        StructuredLogger(
            context = context,
            writer = writer
        )
}
