package pro.liliya.core.diagnostics

import java.util.concurrent.atomic.AtomicLong

internal object GlobalDiagnosticSequence {
    private val value = AtomicLong(0)

    fun next(): Long = value.incrementAndGet()

    internal fun resetForTest() {
        value.set(0)
    }
}
