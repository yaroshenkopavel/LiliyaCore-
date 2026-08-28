package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceLifecycleAppliedResultContractTest {
    @Test
    fun applied_results_report_exact_services_in_execution_order() {
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
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "applied-result",
            generator = CorrelationIdGenerator { "service-applied-result" }
        )

        val started = manager.startAll(context)
        val stopped = manager.stopAll(context)

        assertIs<ServiceLifecycleResult.Applied>(started)
        assertIs<ServiceLifecycleResult.Applied>(stopped)
        assertEquals(listOf("storage", "memory"), started.serviceIds)
        assertEquals(listOf("memory", "storage"), stopped.serviceIds)
    }
}
