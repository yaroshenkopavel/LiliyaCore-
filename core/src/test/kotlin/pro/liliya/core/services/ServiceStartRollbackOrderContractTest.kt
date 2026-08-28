package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceStartRollbackOrderContractTest {
    @Test
    fun failed_start_rolls_back_started_chain_in_reverse_order() {
        val calls = mutableListOf<String>()
        val registry = ServiceRegistry()
        fun service(id: String, dependencies: Set<String> = emptySet(), fail: Boolean = false) =
            object : CoreService {
                override val descriptor = ServiceDescriptor(id, dependencies)
                override fun start(context: LogContext) {
                    calls += "start:$id"
                    if (fail) error("failed:$id")
                }
                override fun stop(context: LogContext) { calls += "stop:$id" }
            }
        registry.register(service("storage"))
        registry.register(service("memory", setOf("storage")))
        registry.register(service("assistant", setOf("memory"), fail = true))
        val manager = ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Services",
            operation = "rollback-order",
            generator = CorrelationIdGenerator { "service-rollback-order" }
        )

        assertIs<ServiceLifecycleResult.Failed>(manager.startAll(context))
        assertEquals(
            listOf(
                "start:storage",
                "start:memory",
                "start:assistant",
                "stop:memory",
                "stop:storage"
            ),
            calls
        )
    }
}
