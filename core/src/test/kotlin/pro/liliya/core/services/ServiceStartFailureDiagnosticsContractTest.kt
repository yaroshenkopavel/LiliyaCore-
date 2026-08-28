package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceStartFailureDiagnosticsContractTest {
    @Test
    fun start_failure_preserves_exception_and_correlation() {
        val sink = InMemoryDiagnosticSink()
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("model")
            override fun start(context: LogContext) { throw IllegalStateException("model unavailable") }
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
            operation = "start-failure",
            generator = CorrelationIdGenerator { "service-start-failure" }
        )

        assertIs<ServiceLifecycleResult.Failed>(manager.startAll(context))

        val event = sink.snapshot().single { it.code == "SERVICE_START_FAILED" }
        assertEquals("service-start-failure", event.context.correlationId)
        assertEquals("java.lang.IllegalStateException", event.exception?.type)
        assertEquals("model unavailable", event.exception?.message)
    }
}
