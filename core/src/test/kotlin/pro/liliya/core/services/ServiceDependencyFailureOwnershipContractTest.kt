package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceDependencyFailureOwnershipContractTest {
    @Test
    fun dependent_stop_failure_does_not_release_its_dependency() {
        val calls = mutableListOf<String>()
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) { calls += "start:storage" }
            override fun stop(context: LogContext) { calls += "stop:storage" }
        })
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("memory", setOf("storage"))
            override fun start(context: LogContext) { calls += "start:memory" }
            override fun stop(context: LogContext) {
                calls += "stop:memory"
                error("memory stop failed")
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
            operation = "dependency-stop-failure",
            generator = CorrelationIdGenerator { "service-dependency-stop" }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        assertIs<ServiceLifecycleResult.Failed>(manager.stopAll(context))

        assertEquals(listOf("start:storage", "start:memory", "stop:memory"), calls)
        assertTrue(manager.isStarted("memory"))
        assertTrue(manager.isStarted("storage"))
    }
}
