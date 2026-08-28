package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceManagerStopOrderIndependentRegistrationContractTest {
    @Test
    fun dependency_order_controls_stop_even_when_services_registered_in_reverse() {
        val calls = mutableListOf<String>()
        fun service(id: String, dependencies: Set<String> = emptySet()) = object : CoreService {
            override val descriptor = ServiceDescriptor(id, dependencies)
            override fun start(context: LogContext) { calls += "start:$id" }
            override fun stop(context: LogContext) { calls += "stop:$id" }
        }
        val registry = ServiceRegistry()
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
            operation = "registration-order",
            generator = CorrelationIdGenerator { "service-registration-order" }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        assertIs<ServiceLifecycleResult.Applied>(manager.stopAll(context))
        assertEquals(listOf("start:storage", "start:memory", "stop:memory", "stop:storage"), calls)
    }
}
