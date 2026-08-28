package pro.liliya.core.events

fun interface EventSubscription {
    fun cancel(): Boolean
}
