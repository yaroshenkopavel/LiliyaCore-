package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceManagerStopOnlyStartedContractTest {
    @Test
    fun stop_does_not_invoke_service_that_was_registered_after_start_cycle() {
        val calls = mutableListOf<String>()
        fun service(id: String) = object : CoreService {
            override val descriptor = ServiceDescriptor(id)
            override fun start(context: LogContext) { calls += "start:$id" }
            override fun stop(context: LogContext) { calls += "stop:$id" }
        }
        val registry = ServiceRegistry()
        registry.register(service("storage"))
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "stop-only-started",
            generator = CorrelationIdGenerator { "service-stop-only-started" }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        registry.register(service("late"))
        assertIs<ServiceLifecycleResult.Applied>(manager.stopAll(context))

        assertEquals(listOf("start:storage", "stop:storage"), calls)
    }
}
