package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceRegistrationDuringLifecycleContractTest {
    @Test
    fun service_registered_during_start_is_deferred_until_next_start_cycle() {
        val calls = mutableListOf<String>()
        val registry = ServiceRegistry()
        val late = object : CoreService {
            override val descriptor = ServiceDescriptor("late")
            override fun start(context: LogContext) { calls += "start:late" }
            override fun stop(context: LogContext) { calls += "stop:late" }
        }
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("bootstrap")
            override fun start(context: LogContext) {
                calls += "start:bootstrap"
                registry.register(late)
            }
            override fun stop(context: LogContext) { calls += "stop:bootstrap" }
        })
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "registration-boundary",
            generator = CorrelationIdGenerator { "service-registration-boundary" }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        assertEquals(listOf("start:bootstrap"), calls)
        assertTrue(manager.isStarted("bootstrap"))
        assertFalse(manager.isStarted("late"))

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        assertEquals(listOf("start:bootstrap", "start:late"), calls)
        assertTrue(manager.isStarted("late"))
    }
}
