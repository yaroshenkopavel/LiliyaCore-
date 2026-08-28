package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceDiagnosticsOrderContractTest {
    @Test
    fun lifecycle_diagnostics_follow_execution_order() {
        val sink = InMemoryDiagnosticSink()
        val registry = ServiceRegistry()
        fun service(id: String, dependencies: Set<String> = emptySet()) = object : CoreService {
            override val descriptor = ServiceDescriptor(id, dependencies)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        registry.register(service("memory", setOf("storage")))
        registry.register(service("storage"))
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(sink)
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "diagnostic-order",
            generator = CorrelationIdGenerator { "service-diagnostic-order" }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        assertIs<ServiceLifecycleResult.Applied>(manager.stopAll(context))

        assertEquals(
            listOf(
                "SERVICE_STARTED:storage",
                "SERVICE_STARTED:memory",
                "SERVICE_STOPPED:memory",
                "SERVICE_STOPPED:storage"
            ),
            sink.snapshot().map { "${it.code}:${it.metadata["serviceId"]}" }
        )
    }
}
