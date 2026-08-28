package pro.liliya.core.logging

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class BootstrapLogWriter(
    private val capacity: Int = 256
) : LogWriter {

    init {
        require(capacity > 0) { "capacity must be greater than zero" }
    }

    private val lock = ReentrantLock()
    private val buffered = ArrayDeque<LogEvent>(capacity)
    private var delegate: LogWriter? = null

    override fun write(event: LogEvent) {
        val target = lock.withLock {
            val installed = delegate
            if (installed == null) {
                if (buffered.size == capacity) {
                    buffered.removeFirst()
                }
                buffered.addLast(event)
                null
            } else {
                installed
            }
        }

        target?.write(event)
    }

    fun install(delegate: LogWriter) {
        val pending = lock.withLock {
            this.delegate = delegate
            val snapshot = buffered.toList()
            buffered.clear()
            snapshot
        }

        pending.forEach(delegate::write)
    }

    fun bufferedEvents(): List<LogEvent> =
        lock.withLock { buffered.toList() }
}
