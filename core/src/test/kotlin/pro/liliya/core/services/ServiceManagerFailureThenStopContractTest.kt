package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceManagerFailureThenStopContractTest {
    @Test
    fun failed_first_service_start_leaves_nothing_for_stop_to_release() {
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("model")
            override fun start(context: LogContext) { error("unavailable") }
            override fun stop(context: LogContext) = error("must not stop")
        })
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "failure-then-stop",
            generator = CorrelationIdGenerator { "service-failure-then-stop" }
        )

        assertIs<ServiceLifecycleResult.Failed>(manager.startAll(context))
        val stop = manager.stopAll(context)
        assertIs<ServiceLifecycleResult.Applied>(stop)
        assertTrue(stop.serviceIds.isEmpty())
    }
}
