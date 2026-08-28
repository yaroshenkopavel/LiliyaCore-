package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class ServiceFailureResultContractTest {
    @Test
    fun failed_result_identifies_service_and_preserves_original_cause() {
        val failure = IllegalArgumentException("broken model")
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("model")
            override fun start(context: LogContext) { throw failure }
            override fun stop(context: LogContext) = Unit
        })
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "failure-result",
            generator = CorrelationIdGenerator { "service-failure-result" }
        )

        val result = manager.startAll(context)

        assertIs<ServiceLifecycleResult.Failed>(result)
        assertEquals("model", result.serviceId)
        assertSame(failure, result.cause)
    }
}
