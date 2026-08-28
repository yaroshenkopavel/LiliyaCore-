package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceLifecycleStopRejectDiagnosticsContractTest {
    @Test
    fun unresolved_graph_on_stop_is_observable_without_releasing_started_service() {
        val sink = InMemoryDiagnosticSink()
        val registry = ServiceRegistry()
        fun service(id: String, dependencies: Set<String> = emptySet()) = object : CoreService {
            override val descriptor = ServiceDescriptor(id, dependencies)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        registry.register(service("storage"))
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(sink)
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "stop-reject-diagnostics",
            generator = CorrelationIdGenerator { "service-stop-reject-diagnostics" }
        )
        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        registry.register(service("memory", setOf("missing")))

        assertIs<ServiceLifecycleResult.Rejected>(manager.stopAll(context))
        assertEquals(
            listOf("SERVICE_STARTED", "SERVICE_LIFECYCLE_REJECTED"),
            sink.snapshot().map { it.code }
        )
    }
}
