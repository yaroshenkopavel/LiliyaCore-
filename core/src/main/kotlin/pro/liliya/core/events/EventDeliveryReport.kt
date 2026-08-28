package pro.liliya.core.events

data class EventDeliveryReport(
    val delivered: Int,
    val failed: Int
) {
    val attempted: Int
        get() = delivered + failed
}
