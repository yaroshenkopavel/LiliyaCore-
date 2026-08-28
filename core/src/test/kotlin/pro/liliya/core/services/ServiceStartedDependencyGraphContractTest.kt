package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceStartedDependencyGraphContractTest {
    @Test
    fun successful_start_owns_every_service_in_resolved_graph() {
        val registry = ServiceRegistry()
        fun service(id: String, dependencies: Set<String> = emptySet()) = object : CoreService {
            override val descriptor = ServiceDescriptor(id, dependencies)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        registry.register(service("storage"))
        registry.register(service("memory", setOf("storage")))
        registry.register(service("assistant", setOf("memory")))
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "graph-ownership",
            generator = CorrelationIdGenerator { "service-graph-ownership" }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        assertTrue(manager.isStarted("storage"))
        assertTrue(manager.isStarted("memory"))
        assertTrue(manager.isStarted("assistant"))
    }
}
