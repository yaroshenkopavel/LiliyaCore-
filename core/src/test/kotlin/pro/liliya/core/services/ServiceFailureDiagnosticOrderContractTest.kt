package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceFailureDiagnosticOrderContractTest {
    @Test
    fun start_failure_is_recorded_before_rollback_events() {
        val sink = InMemoryDiagnosticSink()
        val registry = ServiceRegistry()
        fun service(id: String, dependencies: Set<String> = emptySet(), fail: Boolean = false) =
            object : CoreService {
                override val descriptor = ServiceDescriptor(id, dependencies)
                override fun start(context: LogContext) { if (fail) error("failed:$id") }
                override fun stop(context: LogContext) = Unit
            }
        registry.register(service("storage"))
        registry.register(service("memory", setOf("storage"), fail = true))
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(sink)
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "failure-diagnostic-order",
            generator = CorrelationIdGenerator { "service-failure-diagnostic-order" }
        )

        assertIs<ServiceLifecycleResult.Failed>(manager.startAll(context))
        assertEquals(
            listOf("SERVICE_STARTED", "SERVICE_START_FAILED", "SERVICE_START_ROLLED_BACK"),
            sink.snapshot().map { it.code }
        )
    }
}
