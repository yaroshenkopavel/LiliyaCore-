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

class ServiceStartAfterPartialStopFailureContractTest {
    @Test
    fun start_after_partial_stop_restarts_only_released_dependents() {
        var storageStopFails = true
        val calls = mutableListOf<String>()
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) { calls += "start:storage" }
            override fun stop(context: LogContext) {
                calls += "stop:storage"
                if (storageStopFails) error("storage busy")
            }
        })
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("memory", setOf("storage"))
            override fun start(context: LogContext) { calls += "start:memory" }
            override fun stop(context: LogContext) { calls += "stop:memory" }
        })
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "partial-stop-reconcile",
            generator = CorrelationIdGenerator { "service-partial-stop-reconcile" }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        assertIs<ServiceLifecycleResult.Failed>(manager.stopAll(context))
        assertTrue(manager.isStarted("storage"))
        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))

        assertEquals(
            listOf("start:storage", "start:memory", "stop:memory", "stop:storage", "start:memory"),
            calls
        )
    }
}
