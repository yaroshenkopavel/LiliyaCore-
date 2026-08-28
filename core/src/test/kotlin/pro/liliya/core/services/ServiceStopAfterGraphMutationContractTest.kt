package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceStopAfterGraphMutationContractTest {
    @Test
    fun unresolved_service_added_after_start_does_not_release_running_owner() {
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
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "graph-mutation",
            generator = CorrelationIdGenerator { "service-graph-mutation" }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        registry.register(service("memory", setOf("missing")))
        assertIs<ServiceLifecycleResult.Rejected>(manager.stopAll(context))
        assertTrue(manager.isStarted("storage"))
    }
}
