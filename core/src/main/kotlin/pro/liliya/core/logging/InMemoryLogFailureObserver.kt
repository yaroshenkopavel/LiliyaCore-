package pro.liliya.core.logging

import java.util.concurrent.CopyOnWriteArrayList

class InMemoryLogFailureObserver {
    private val failures = CopyOnWriteArrayList<LogWriterFailure>()

    fun record(failure: LogWriterFailure) {
        failures += failure
    }

    fun snapshot(): List<LogWriterFailure> = failures.toList()

    fun clear() {
        failures.clear()
    }
}
