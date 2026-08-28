package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceManagerStopAfterEmptyStartContractTest {
    @Test
    fun service_registered_after_empty_start_is_not_stopped_before_it_is_started() {
        val registry = ServiceRegistry()
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "empty-start-late-registration",
            generator = CorrelationIdGenerator { "service-empty-start-late" }
        )
        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = error("must not stop")
        })

        val stop = manager.stopAll(context)

        assertIs<ServiceLifecycleResult.Applied>(stop)
        assertTrue(stop.serviceIds.isEmpty())
    }
}
