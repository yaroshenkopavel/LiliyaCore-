package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ServiceStopDependencyGraphContractTest {
    @Test
    fun successful_stop_releases_every_service_in_resolved_graph() {
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
            operation = "graph-release",
            generator = CorrelationIdGenerator { "service-graph-release" }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        assertIs<ServiceLifecycleResult.Applied>(manager.stopAll(context))
        assertFalse(manager.isStarted("storage"))
        assertFalse(manager.isStarted("memory"))
        assertFalse(manager.isStarted("assistant"))
    }
}
