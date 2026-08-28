package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceFoundationContractTest {
    private fun context(): LogContext = LogContextPropagation.root(
        module = "CORE",
        component = "Services",
        operation = "contract",
        generator = CorrelationIdGenerator { "services-contract" }
    )

    private class RecordingService(
        override val descriptor: ServiceDescriptor,
        private val calls: MutableList<String>,
        private val failStart: Boolean = false
    ) : CoreService {
        override fun start(context: LogContext) {
            calls += "start:${descriptor.id}"
            if (failStart) error("start failed: ${descriptor.id}")
        }

        override fun stop(context: LogContext) {
            calls += "stop:${descriptor.id}"
        }
    }

    @Test
    fun duplicate_registration_is_rejected_without_replacing_owner() {
        val registry = ServiceRegistry()
        val calls = mutableListOf<String>()
        val first = RecordingService(ServiceDescriptor("memory"), calls)
        val second = RecordingService(ServiceDescriptor("memory"), calls)

        assertIs<ServiceRegistrationResult.Registered>(registry.register(first))
        assertIs<ServiceRegistrationResult.Rejected>(registry.register(second))
        assertTrue(registry.find("memory") === first)
    }

    @Test
    fun dependencies_start_before_dependents_and_stop_in_reverse_order() {
        val registry = ServiceRegistry()
        val calls = mutableListOf<String>()
        val storage = RecordingService(ServiceDescriptor("storage"), calls)
        val memory = RecordingService(ServiceDescriptor("memory", setOf("storage")), calls)
        registry.register(memory)
        registry.register(storage)
        val manager = manager(registry)

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context()))
        assertIs<ServiceLifecycleResult.Applied>(manager.stopAll(context()))

        assertEquals(
            listOf("start:storage", "start:memory", "stop:memory", "stop:storage"),
            calls
        )
    }

    @Test
    fun missing_dependency_rejects_start_without_partial_execution() {
        val registry = ServiceRegistry()
        val calls = mutableListOf<String>()
        registry.register(RecordingService(ServiceDescriptor("memory", setOf("storage")), calls))

        val result = manager(registry).startAll(context())

        assertIs<ServiceLifecycleResult.Rejected>(result)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun dependency_cycle_is_rejected_without_execution() {
        val registry = ServiceRegistry()
        val calls = mutableListOf<String>()
        registry.register(RecordingService(ServiceDescriptor("a", setOf("b")), calls))
        registry.register(RecordingService(ServiceDescriptor("b", setOf("a")), calls))

        assertIs<ServiceLifecycleResult.Rejected>(manager(registry).startAll(context()))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun start_failure_rolls_back_already_started_services() {
        val registry = ServiceRegistry()
        val calls = mutableListOf<String>()
        registry.register(RecordingService(ServiceDescriptor("storage"), calls))
        registry.register(
            RecordingService(
                ServiceDescriptor("memory", setOf("storage")),
                calls,
                failStart = true
            )
        )
        val manager = manager(registry)

        val result = manager.startAll(context())

        assertIs<ServiceLifecycleResult.Failed>(result)
        assertEquals(listOf("start:storage", "start:memory", "stop:storage"), calls)
        assertFalse(manager.isStarted("storage"))
        assertFalse(manager.isStarted("memory"))
    }

    @Test
    fun repeated_start_does_not_start_owned_service_twice() {
        val registry = ServiceRegistry()
        val calls = mutableListOf<String>()
        registry.register(RecordingService(ServiceDescriptor("storage"), calls))
        val manager = manager(registry)

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context()))
        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context()))

        assertEquals(listOf("start:storage"), calls)
        assertTrue(manager.isStarted("storage"))
    }

    private fun manager(registry: ServiceRegistry): ServiceManager = ServiceManager(
        registry = registry,
        resolver = ServiceDependencyResolver(),
        diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
    )
}
