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

class ServiceManagerStartedLookupExactContractTest {
    @Test
    fun started_lookup_is_case_sensitive_and_exact() {
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("memory")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        })
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "started-lookup",
            generator = CorrelationIdGenerator { "service-started-lookup" }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        assertTrue(manager.isStarted("memory"))
        assertFalse(manager.isStarted("Memory"))
    }
}
