package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceLifecycleNoServicesDiagnosticsContractTest {
    @Test
    fun empty_start_and_stop_emit_no_fake_service_events() {
        val sink = InMemoryDiagnosticSink()
        val manager = ServiceManager(
            registry = ServiceRegistry(),
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(sink)
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "empty-diagnostics",
            generator = CorrelationIdGenerator { "service-empty-diagnostics" }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        assertIs<ServiceLifecycleResult.Applied>(manager.stopAll(context))
        assertTrue(sink.snapshot().isEmpty())
    }
}
