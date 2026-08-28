package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class ServiceLifecycleRollbackCauseContractTest {
    @Test
    fun rollback_failure_does_not_replace_primary_start_failure_result() {
        val primary = IllegalStateException("memory start failed")
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) { error("rollback failed") }
        })
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("memory", setOf("storage"))
            override fun start(context: LogContext) { throw primary }
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
            operation = "rollback-primary-cause",
            generator = CorrelationIdGenerator { "service-rollback-primary-cause" }
        )

        val result = manager.startAll(context)

        assertIs<ServiceLifecycleResult.Failed>(result)
        assertSame(primary, result.cause)
    }
}
