package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceConcurrentLifecycleContractTest {
    @Test
    fun concurrent_start_calls_start_service_exactly_once() {
        val starts = AtomicInteger(0)
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) { starts.incrementAndGet() }
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
            operation = "concurrent-start",
            generator = CorrelationIdGenerator { "service-concurrent" }
        )
        val ready = CountDownLatch(8)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val futures = (1..8).map {
            pool.submit<ServiceLifecycleResult> {
                ready.countDown()
                go.await()
                manager.startAll(context)
            }
        }
        ready.await()
        go.countDown()
        val results = futures.map { it.get() }
        pool.shutdown()

        results.forEach { assertIs<ServiceLifecycleResult.Applied>(it) }
        assertEquals(1, starts.get())
    }
}
