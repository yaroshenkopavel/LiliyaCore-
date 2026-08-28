package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceRollbackFailureContractTest {
    @Test
    fun rollback_failure_is_observable_and_keeps_service_owned() {
        val sink = InMemoryDiagnosticSink()
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) {
                error("rollback stop failed")
            }
        })
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("memory", setOf("storage"))
            override fun start(context: LogContext) {
                error("memory start failed")
            }
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
            operation = "rollback-failure",
            generator = CorrelationIdGenerator { "service-rollback-failure" }
        )

        assertIs<ServiceLifecycleResult.Failed>(manager.startAll(context))
        assertTrue(manager.isStarted("storage"))
        assertEquals(
            listOf("SERVICE_STARTED", "SERVICE_START_FAILED", "SERVICE_ROLLBACK_FAILED"),
            sink.snapshot().map { it.code }
        )
    }
}
