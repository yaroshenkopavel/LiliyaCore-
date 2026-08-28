package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceStartSuccessDiagnosticsContractTest {
    @Test
    fun successful_start_is_observable_with_service_identity_and_correlation() {
        val sink = InMemoryDiagnosticSink()
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("model")
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
            operation = "start-success",
            generator = CorrelationIdGenerator { "service-start-success" }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))

        val event = sink.snapshot().single { it.code == "SERVICE_STARTED" }
        assertEquals("model", event.metadata["serviceId"])
        assertEquals("service-start-success", event.context.correlationId)
    }
}
