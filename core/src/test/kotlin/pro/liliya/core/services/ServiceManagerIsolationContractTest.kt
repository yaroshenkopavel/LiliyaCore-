package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceManagerIsolationContractTest {
    @Test
    fun managers_keep_started_ownership_isolated() {
        fun service(id: String) = object : CoreService {
            override val descriptor = ServiceDescriptor(id)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val firstRegistry = ServiceRegistry().also { it.register(service("storage")) }
        val secondRegistry = ServiceRegistry().also { it.register(service("storage")) }
        fun manager(registry: ServiceRegistry) = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val first = manager(firstRegistry)
        val second = manager(secondRegistry)
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "isolation",
            generator = CorrelationIdGenerator { "service-isolation" }
        )

        assertIs<ServiceLifecycleResult.Applied>(first.startAll(context))
        assertTrue(first.isStarted("storage"))
        assertFalse(second.isStarted("storage"))
    }
}
