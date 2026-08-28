package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceLifecycleSequentialContextsContractTest {
    @Test
    fun separate_lifecycle_cycles_keep_their_own_correlation_ids() {
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

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context("cycle-1-start")))
        assertIs<ServiceLifecycleResult.Applied>(manager.stopAll(context("cycle-1-stop")))
        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context("cycle-2-start")))

        assertEquals(
            listOf("cycle-1-start", "cycle-1-stop", "cycle-2-start"),
            sink.snapshot().map { it.context.correlationId }
        )
    }
}
