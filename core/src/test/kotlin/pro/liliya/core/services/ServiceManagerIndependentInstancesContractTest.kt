package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceManagerIndependentInstancesContractTest {
    @Test
    fun identical_service_ids_in_separate_registries_execute_independently() {
        var firstStarts = 0
        var secondStarts = 0
        fun registry(onStart: () -> Unit) = ServiceRegistry().also { registry ->
            registry.register(object : CoreService {
                override val descriptor = ServiceDescriptor("storage")
                override fun start(context: LogContext) { onStart() }
                override fun stop(context: LogContext) = Unit
            })
        }
        fun manager(registry: ServiceRegistry) = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val first = manager(registry { firstStarts++ })
        val second = manager(registry { secondStarts++ })
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "instance-isolation",
            generator = CorrelationIdGenerator { "service-instance-isolation" }
        )

        assertIs<ServiceLifecycleResult.Applied>(first.startAll(context))
        assertIs<ServiceLifecycleResult.Applied>(second.startAll(context))
        assertEquals(1, firstStarts)
        assertEquals(1, secondStarts)
    }
}
