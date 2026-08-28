package pro.liliya.core.events

fun interface EventListener {
    fun onEvent(envelope: EventEnvelope)
}
