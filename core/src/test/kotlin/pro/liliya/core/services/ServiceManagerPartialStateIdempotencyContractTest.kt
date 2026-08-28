package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceManagerPartialStateIdempotencyContractTest {
    @Test
    fun retry_after_failed_dependent_start_does_not_restart_surviving_owned_dependency() {
        var dependentFails = true
        val calls = mutableListOf<String>()
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) { calls += "start:storage" }
            override fun stop(context: LogContext) {
                calls += "stop:storage"
                error("rollback failed")
            }
        })
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("memory", setOf("storage"))
            override fun start(context: LogContext) {
                calls += "start:memory"
                if (dependentFails) error("memory unavailable")
            }
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
            operation = "partial-start-idempotency",
            generator = CorrelationIdGenerator { "service-partial-start-idempotency" }
        )

        assertIs<ServiceLifecycleResult.Failed>(manager.startAll(context))
        dependentFails = false
        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context))

        assertEquals(listOf("start:storage", "start:memory", "stop:storage", "start:memory"), calls)
    }
}
