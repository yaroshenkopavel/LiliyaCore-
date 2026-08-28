package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceLifecycleReentryContractTest {
    @Test
    fun released_service_can_start_again_in_a_new_cycle() {
        val calls = mutableListOf<String>()
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) { calls += "start" }
            override fun stop(context: LogContext) { calls += "stop" }
        })
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "reentry",
            generator = CorrelationIdGenerator { "service-reentry" }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        assertIs<ServiceLifecycleResult.Applied>(manager.stopAll(context))
        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))

        assertEquals(listOf("start", "stop", "start"), calls)
    }
}
