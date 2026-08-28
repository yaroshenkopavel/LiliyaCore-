package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceLifecycleDifferentContextsContractTest {
    @Test
    fun start_and_stop_diagnostics_preserve_their_respective_contexts() {
        val sink = InMemoryDiagnosticSink()
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        })
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(sink)
        )
        fun context(id: String) = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = id,
            generator = CorrelationIdGenerator { id }
        )

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context("start-context")))
        assertIs<ServiceLifecycleResult.Applied>(manager.stopAll(context("stop-context")))

        assertEquals("start-context", sink.snapshot().first { it.code == "SERVICE_STARTED" }.context.correlationId)
        assertEquals("stop-context", sink.snapshot().first { it.code == "SERVICE_STOPPED" }.context.correlationId)
    }
}
