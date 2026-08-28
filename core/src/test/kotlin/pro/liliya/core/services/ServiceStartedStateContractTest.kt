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

class ServiceStartedStateContractTest {
    @Test
    fun successful_start_and_stop_update_owned_state() {
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
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
            operation = "state",
            generator = CorrelationIdGenerator { "service-state" }
        )

        assertFalse(manager.isStarted("storage"))
        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        assertTrue(manager.isStarted("storage"))
        assertIs<ServiceLifecycleResult.Applied>(manager.stopAll(context))
        assertFalse(manager.isStarted("storage"))
    }
}
