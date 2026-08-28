package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceEmptyGraphContractTest {
    @Test
    fun empty_registry_is_a_valid_lifecycle_graph() {
        val manager = ServiceManager(
            registry = ServiceRegistry(),
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "empty",
            generator = CorrelationIdGenerator { "service-empty" }
        )

        val start = manager.startAll(context)
        val stop = manager.stopAll(context)

        assertIs<ServiceLifecycleResult.Applied>(start)
        assertIs<ServiceLifecycleResult.Applied>(stop)
        assertTrue(start.serviceIds.isEmpty())
        assertTrue(stop.serviceIds.isEmpty())
    }
}
