package pro.liliya.core.events

import java.util.concurrent.atomic.AtomicLong

object GlobalEventSequence {
    private val value = AtomicLong(0)

    fun next(): Long = value.incrementAndGet()

    internal fun resetForTest() {
        value.set(0)
    }
}
