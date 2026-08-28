package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceManagerRegistryVisibilityContractTest {
    @Test
    fun service_registered_between_cycles_is_visible_to_next_start() {
        val registry = ServiceRegistry()
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "registry-visibility",
            generator = CorrelationIdGenerator { "service-registry-visibility" }
        )
        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        })

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        assertTrue(manager.isStarted("storage"))
    }
}
