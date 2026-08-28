package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ServiceRegistrationDuringStopContractTest {
    @Test
    fun service_registered_during_stop_is_not_part_of_current_stop_cycle() {
        val registry = ServiceRegistry()
        val late = object : CoreService {
            override val descriptor = ServiceDescriptor("late")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("bootstrap")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) { registry.register(late) }
        })
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "stop-registration-boundary",
            generator = CorrelationIdGenerator { "service-stop-registration" }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        assertIs<ServiceLifecycleResult.Applied>(manager.stopAll(context))
        assertFalse(manager.isStarted("late"))
    }
}
