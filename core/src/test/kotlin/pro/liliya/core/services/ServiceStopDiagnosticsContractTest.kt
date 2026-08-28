package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceStopDiagnosticsContractTest {
    @Test
    fun stop_failure_preserves_exception_and_correlation() {
        val sink = InMemoryDiagnosticSink()
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) { throw IllegalStateException("storage busy") }
        })
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(sink)
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "stop-diagnostics",
            generator = CorrelationIdGenerator { "service-stop-diagnostics" }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))
        assertIs<ServiceLifecycleResult.Failed>(manager.stopAll(context))

        val event = sink.snapshot().single { it.code == "SERVICE_STOP_FAILED" }
        assertEquals("service-stop-diagnostics", event.context.correlationId)
        assertEquals("java.lang.IllegalStateException", event.exception?.type)
        assertEquals("storage busy", event.exception?.message)
    }
}
