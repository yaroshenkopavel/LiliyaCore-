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

class ServiceStopFailureResultContractTest {
    @Test
    fun stop_failure_result_identifies_service_and_original_cause() {
        val failure = IllegalStateException("busy")
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) { throw failure }
        })
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "stop-failure-result",
            generator = CorrelationIdGenerator { "service-stop-failure-result" }
        )
        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))

        val result = manager.stopAll(context)

        assertIs<ServiceLifecycleResult.Failed>(result)
        assertEquals("storage", result.serviceId)
        assertSame(failure, result.cause)
    }
}
