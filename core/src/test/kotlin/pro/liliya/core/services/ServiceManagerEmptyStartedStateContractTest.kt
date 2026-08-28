package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import kotlin.test.Test
import kotlin.test.assertFalse

class ServiceManagerEmptyStartedStateContractTest {
    @Test
    fun unknown_service_is_never_reported_as_started() {
        val manager = ServiceManager(
            registry = ServiceRegistry(),
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )

        assertFalse(manager.isStarted("missing"))
    }
}
