package pro.liliya.core.logging

data class LogEvent(
    val timestampMillis: Long,
    val sequence: Long,
    val level: LogLevel,
    val context: LogContext,
    val marker: String,
    val message: String,
    val threadName: String,
    val throwableType: String? = null,
    val throwableMessage: String? = null,
    val metadata: Map<String, String> = context.metadata.toMap()
)
