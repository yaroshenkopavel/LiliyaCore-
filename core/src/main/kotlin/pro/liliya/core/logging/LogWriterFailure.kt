package pro.liliya.core.logging

data class LogWriterFailure(
    val writerType: String,
    val eventSequence: Long,
    val marker: String,
    val throwableType: String,
    val throwableMessage: String?
)
