package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceManagerStartResultAfterPartialStateContractTest {
    @Test
    fun retry_after_surviving_dependency_reports_only_newly_started_dependent() {
        var fail = true
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) { error("rollback failed") }
        })
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("memory", setOf("storage"))
            override fun start(context: LogContext) { if (fail) error("memory failed") }
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
            operation = "partial-result",
            generator = CorrelationIdGenerator { "service-partial-result" }
        )

        assertIs<ServiceLifecycleResult.Failed>(manager.startAll(context))
        fail = false
        val retry = manager.startAll(context)

        assertIs<ServiceLifecycleResult.Applied>(retry)
        assertEquals(listOf("memory"), retry.serviceIds)
    }
}
