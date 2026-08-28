package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ServiceStartCycleSnapshotContractTest {
    @Test
    fun newly_registered_service_does_not_gain_started_ownership_in_failed_cycle() {
        val registry = ServiceRegistry()
        val later = object : CoreService {
            override val descriptor = ServiceDescriptor("later")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("alpha")
            override fun start(context: LogContext) { registry.register(later) }
            override fun stop(context: LogContext) = Unit
        })
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("beta")
            override fun start(context: LogContext) { throw IllegalStateException("beta unavailable") }
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
            operation = "start-cycle-snapshot",
            generator = CorrelationIdGenerator { "service-start-cycle-snapshot" }
        )

        assertIs<ServiceLifecycleResult.Failed>(manager.startAll(context))
        assertFalse(manager.isStarted("later"))
    }
}
