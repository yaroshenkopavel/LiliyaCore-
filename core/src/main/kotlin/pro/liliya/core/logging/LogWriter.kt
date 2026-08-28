package pro.liliya.core.logging

fun interface LogWriter {
    fun write(event: LogEvent)
}
