package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceLifecycleFailureRecoveryContractTest {
    @Test
    fun failed_service_can_be_started_on_a_later_retry_when_failure_condition_clears() {
        var fail = true
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("model")
            override fun start(context: LogContext) {
                if (fail) error("model unavailable")
            }
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
            operation = "retry-after-failure",
            generator = CorrelationIdGenerator { "service-retry-after-failure" }
        )

        assertIs<ServiceLifecycleResult.Failed>(manager.startAll(context))
        fail = false
        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        assertTrue(manager.isStarted("model"))
    }
}
