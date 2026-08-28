package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServiceFoundationContractTest {
    private fun context(operation: String = "contract") = LogContextPropagation.root(
        module = "CORE",
        component = "Services",
        operation = operation,
        generator = CorrelationIdGenerator { "services-$operation" }
    )

    private class RecordingService(
        override val descriptor: ServiceDescriptor,
        private val calls: MutableList<String>,
        private val failStart: Boolean = false,
        private val failStop: Boolean = false
    ) : CoreService {
        override fun start(context: LogContext) {
            calls += "start:${descriptor.id}"
            if (failStart) error("start failed: ${descriptor.id}")
        }

        override fun stop(context: LogContext) {
            calls += "stop:${descriptor.id}"
            if (failStop) error("stop failed: ${descriptor.id}")
        }
    }

    private fun manager(registry: ServiceRegistry, sink: InMemoryDiagnosticSink = InMemoryDiagnosticSink()) =
        ServiceManager(
            registry = registry,
            resolver = ServiceDependencyResolver(),
            diagnostics = DiagnosticRecorder(sink)
        )

    @Test
    fun registry_has_single_owner_per_service_id() {
        val registry = ServiceRegistry()
        val calls = mutableListOf<String>()
        val first = RecordingService(ServiceDescriptor("memory"), calls)
        val second = RecordingService(ServiceDescriptor("memory"), calls)

        assertIs<ServiceRegistrationResult.Registered>(registry.register(first))
        assertIs<ServiceRegistrationResult.Rejected>(registry.register(second))
        assertSame(first, registry.find("memory"))
    }

    @Test
    fun dependency_order_is_deterministic_and_reverse_for_stop() {
        val registry = ServiceRegistry()
        val calls = mutableListOf<String>()
        registry.register(RecordingService(ServiceDescriptor("memory", setOf("storage")), calls))
        registry.register(RecordingService(ServiceDescriptor("storage"), calls))
        val manager = manager(registry)

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context("start")))
        assertIs<ServiceLifecycleResult.Applied>(manager.stopAll(context("stop")))

        assertEquals(
            listOf("start:storage", "start:memory", "stop:memory", "stop:storage"),
            calls
        )
    }

    @Test
    fun unresolved_graph_is_rejected_without_execution() {
        val registry = ServiceRegistry()
        val calls = mutableListOf<String>()
        registry.register(RecordingService(ServiceDescriptor("memory", setOf("storage")), calls))

        assertIs<ServiceLifecycleResult.Rejected>(manager(registry).startAll(context()))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun start_failure_rolls_back_owned_services_in_reverse_order() {
        val registry = ServiceRegistry()
        val calls = mutableListOf<String>()
        registry.register(RecordingService(ServiceDescriptor("storage"), calls))
        registry.register(RecordingService(ServiceDescriptor("memory", setOf("storage")), calls, failStart = true))
        val manager = manager(registry)

        assertIs<ServiceLifecycleResult.Failed>(manager.startAll(context()))
        assertEquals(listOf("start:storage", "start:memory", "stop:storage"), calls)
        assertFalse(manager.isStarted("storage"))
        assertFalse(manager.isStarted("memory"))
    }

    @Test
    fun stop_failure_keeps_failed_service_owned() {
        val registry = ServiceRegistry()
        val calls = mutableListOf<String>()
        registry.register(RecordingService(ServiceDescriptor("storage"), calls, failStop = true))
        val manager = manager(registry)

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context("start")))
        assertIs<ServiceLifecycleResult.Failed>(manager.stopAll(context("stop")))
        assertTrue(manager.isStarted("storage"))
    }

    @Test
    fun repeated_start_and_stop_are_idempotent() {
        val registry = ServiceRegistry()
        val calls = mutableListOf<String>()
        registry.register(RecordingService(ServiceDescriptor("storage"), calls))
        val manager = manager(registry)

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context("start-1")))
        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context("start-2")))
        assertIs<ServiceLifecycleResult.Applied>(manager.stopAll(context("stop-1")))
        assertIs<ServiceLifecycleResult.Applied>(manager.stopAll(context("stop-2")))

        assertEquals(listOf("start:storage", "stop:storage"), calls)
    }

    @Test
    fun lifecycle_diagnostics_preserve_correlation_and_service_identity() {
        val sink = InMemoryDiagnosticSink()
        val registry = ServiceRegistry()
        registry.register(RecordingService(ServiceDescriptor("model"), mutableListOf()))
        val manager = manager(registry, sink)

        assertIs<ServiceLifecycleResult.Applied>(manager.startAll(context("diagnostics")))

        val event = sink.snapshot().single { it.code == "SERVICE_STARTED" }
        assertEquals("services-diagnostics", event.context.correlationId)
        assertEquals("model", event.metadata["serviceId"])
    }

    @Test
    fun concurrent_start_calls_start_service_exactly_once() {
        val registry = ServiceRegistry()
        var starts = 0
        val lock = Any()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) { synchronized(lock) { starts++ } }
            override fun stop(context: LogContext) = Unit
        })
        val manager = manager(registry)
        val ready = CountDownLatch(8)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val futures = (1..8).map {
            pool.submit<ServiceLifecycleResult> {
                ready.countDown()
                go.await()
                manager.startAll(context("concurrent"))
            }
        }

        ready.await()
        go.countDown()
        futures.forEach { assertIs<ServiceLifecycleResult.Applied>(it.get()) }
        pool.shutdown()

        assertEquals(1, starts)
    }
}
