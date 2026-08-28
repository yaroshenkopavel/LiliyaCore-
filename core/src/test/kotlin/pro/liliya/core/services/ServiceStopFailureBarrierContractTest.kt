package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceStopFailureBarrierContractTest {
    @Test
    fun stop_failure_prevents_later_dependencies_from_stopping() {
        val calls = mutableListOf<String>()
        val registry = ServiceRegistry()
        fun service(id: String, dependencies: Set<String> = emptySet(), failStop: Boolean = false) =
            object : CoreService {
                override val descriptor = ServiceDescriptor(id, dependencies)
                override fun start(context: LogContext) { calls += "start:$id" }
                override fun stop(context: LogContext) {
                    calls += "stop:$id"
                    if (failStop) error("failed:$id")
                }
            }
        registry.register(service("storage"))
        registry.register(service("memory", setOf("storage"), failStop = true))
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "stop-barrier",
            generator = CorrelationIdGenerator { "service-stop-barrier" }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        assertIs<ServiceLifecycleResult.Failed>(manager.stopAll(context))
        assertEquals(listOf("start:storage", "start:memory", "stop:memory"), calls)
    }
}
