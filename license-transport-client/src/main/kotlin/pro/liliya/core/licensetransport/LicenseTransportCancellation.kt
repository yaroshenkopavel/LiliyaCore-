package pro.liliya.core.licensetransport

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class LicenseTransportCancellation {
    private val cancelled = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun isCancelled(): Boolean = cancelled.get()

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return
        }

        listeners.forEach { listener ->
            runCatching(listener)
        }
        listeners.clear()
    }

    internal fun register(listener: () -> Unit): AutoCloseable {
        if (cancelled.get()) {
            runCatching(listener)
            return AutoCloseable { }
        }

        listeners += listener

        if (cancelled.get() && listeners.remove(listener)) {
            runCatching(listener)
        }

        return AutoCloseable {
            listeners.remove(listener)
        }
    }
}
