package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceRollbackFailureDiagnosticsContractTest {
    @Test
    fun rollback_failure_preserves_exception_service_identity_and_correlation() {
        val sink = InMemoryDiagnosticSink()
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) { error("storage rollback failed") }
        })
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("memory", setOf("storage"))
            override fun start(context: LogContext) { error("memory start failed") }
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
            operation = "rollback-failure-details",
            generator = CorrelationIdGenerator { "service-rollback-failure-details" }
        )

        assertIs<ServiceLifecycleResult.Failed>(manager.startAll(context))

        val event = sink.snapshot().single { it.code == "SERVICE_ROLLBACK_FAILED" }
        assertEquals("storage", event.metadata["serviceId"])
        assertEquals("service-rollback-failure-details", event.context.correlationId)
        assertEquals("java.lang.IllegalStateException", event.exception?.type)
        assertEquals("storage rollback failed", event.exception?.message)
    }
}
