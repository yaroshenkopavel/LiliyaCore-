package pro.liliya.core.logging

object LoggerFactory {
    @Volatile
    private var bootstrapWriter = BootstrapLogWriter()

    @Volatile
    private var writer: LogWriter = bootstrapWriter

    fun installWriter(newWriter: LogWriter) {
        bootstrapWriter.install(newWriter)
        writer = newWriter
    }

    fun create(context: LogContext): Logger =
        StructuredLogger(
            context = context,
            writer = writer
        )

    internal fun resetForTest() {
        val freshBootstrapWriter = BootstrapLogWriter()
        bootstrapWriter = freshBootstrapWriter
        writer = freshBootstrapWriter
    }
}
