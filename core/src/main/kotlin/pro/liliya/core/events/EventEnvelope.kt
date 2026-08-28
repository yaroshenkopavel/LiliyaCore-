package pro.liliya.core.events

data class EventEnvelope(
    val sequence: Long,
    val timestampMillis: Long,
    val event: CoreEvent,
    val metadata: Map<String, String>
)
