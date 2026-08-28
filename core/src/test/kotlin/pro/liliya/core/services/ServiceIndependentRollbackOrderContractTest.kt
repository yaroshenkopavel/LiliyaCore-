package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceIndependentRollbackOrderContractTest {
    @Test
    fun independent_started_services_roll_back_in_reverse_deterministic_order() {
        val calls = mutableListOf<String>()
        val registry = ServiceRegistry()
        fun service(id: String, dependencies: Set<String> = emptySet(), fail: Boolean = false) =
            object : CoreService {
                override val descriptor = ServiceDescriptor(id, dependencies)
                override fun start(context: LogContext) {
                    calls += "start:$id"
                    if (fail) error("failed:$id")
                }
                override fun stop(context: LogContext) { calls += "stop:$id" }
            }
        registry.register(service("beta"))
        registry.register(service("alpha"))
        registry.register(service("zeta", setOf("alpha", "beta"), fail = true))
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "independent-rollback",
            generator = CorrelationIdGenerator { "service-independent-rollback" }
        )

        assertIs<ServiceLifecycleResult.Failed>(manager.startAll(context))
        assertEquals(
            listOf("start:alpha", "start:beta", "start:zeta", "stop:beta", "stop:alpha"),
            calls
        )
    }
}
