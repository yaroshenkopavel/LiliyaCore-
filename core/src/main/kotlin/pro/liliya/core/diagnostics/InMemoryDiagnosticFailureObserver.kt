package pro.liliya.core.diagnostics

class InMemoryDiagnosticFailureObserver {
    private val failures = mutableListOf<DiagnosticFailure>()

    @Synchronized
    fun record(failure: DiagnosticFailure) {
        failures += failure
    }

    @Synchronized
    fun snapshot(): List<DiagnosticFailure> = failures.toList()

    @Synchronized
    fun clear() {
        failures.clear()
    }
}
