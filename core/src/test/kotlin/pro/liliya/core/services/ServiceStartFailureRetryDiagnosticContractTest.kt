package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceStartFailureRetryDiagnosticContractTest {
    @Test
    fun failed_then_successful_start_records_failure_before_later_success() {
        var fail = true
        val sink = InMemoryDiagnosticSink()
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("model")
            override fun start(context: LogContext) { if (fail) error("offline") }
            override fun stop(context: LogContext) = Unit
        })
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(sink)
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "start-retry-diagnostics",
            generator = CorrelationIdGenerator { "service-start-retry-diagnostics" }
        )

        assertIs<ServiceLifecycleResult.Failed>(manager.startAll(context))
        fail = false
        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))

        assertEquals(listOf("SERVICE_START_FAILED", "SERVICE_STARTED"), sink.snapshot().map { it.code })
    }
}
