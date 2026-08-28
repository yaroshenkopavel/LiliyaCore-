package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceDiagnosticsContractTest {
    @Test
    fun lifecycle_diagnostics_preserve_correlation_context() {
        val sink = InMemoryDiagnosticSink()
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        })
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(sink)
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "diagnostics",
            generator = CorrelationIdGenerator { "service-correlation" }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))

        val event = sink.snapshot().single { it.code == "SERVICE_STARTED" }
        assertEquals("service-correlation", event.context.correlationId)
        assertEquals("storage", event.metadata["serviceId"])
    }
}
