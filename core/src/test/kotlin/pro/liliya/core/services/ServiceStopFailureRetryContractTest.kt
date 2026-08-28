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

class ServiceStopFailureRetryContractTest {
    @Test
    fun service_that_failed_to_stop_can_be_retried_later() {
        var fail = true
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) {
                if (fail) error("storage busy")
            }
        })
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "stop-retry",
            generator = CorrelationIdGenerator { "service-stop-retry" }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        assertIs<ServiceLifecycleResult.Failed>(manager.stopAll(context))
        assertTrue(manager.isStarted("storage"))
        fail = false
        assertIs<ServiceLifecycleResult.Applied>(manager.stopAll(context))
        assertFalse(manager.isStarted("storage"))
    }
}
