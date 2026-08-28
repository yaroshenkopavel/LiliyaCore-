package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceLifecycleRejectionDiagnosticsContractTest {
    @Test
    fun unresolved_graph_rejection_is_observable_with_correlation() {
        val sink = InMemoryDiagnosticSink()
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("memory", setOf("storage"))
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
            operation = "rejection",
            generator = CorrelationIdGenerator { "service-rejection" }
        )

        assertIs<ServiceLifecycleResult.Rejected>(manager.startAll(context))

        val event = sink.snapshot().single { it.code == "SERVICE_LIFECYCLE_REJECTED" }
        assertEquals("service-rejection", event.context.correlationId)
        assertEquals("missing dependency storage for memory", event.message)
    }
}
