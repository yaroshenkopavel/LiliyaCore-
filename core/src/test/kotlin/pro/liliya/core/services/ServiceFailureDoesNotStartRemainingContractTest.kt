package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceFailureDoesNotStartRemainingContractTest {
    @Test
    fun start_failure_prevents_later_services_from_starting() {
        val calls = mutableListOf<String>()
        val registry = ServiceRegistry()
        fun service(id: String, fail: Boolean = false) = object : CoreService {
            override val descriptor = ServiceDescriptor(id)
            override fun start(context: LogContext) {
                calls += "start:$id"
                if (fail) error("failed:$id")
            }
            override fun stop(context: LogContext) { calls += "stop:$id" }
        }
        registry.register(service("alpha", fail = true))
        registry.register(service("beta"))
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "failure-barrier",
            generator = CorrelationIdGenerator { "service-failure-barrier" }
        )

        assertIs<ServiceLifecycleResult.Failed>(manager.startAll(context))
        assertEquals(listOf("start:alpha"), calls)
    }
}
