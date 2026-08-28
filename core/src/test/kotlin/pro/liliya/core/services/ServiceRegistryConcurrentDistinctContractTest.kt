package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceRegistryConcurrentDistinctContractTest {
    @Test
    fun concurrent_distinct_registrations_all_become_visible() {
        val registry = ServiceRegistry()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val futures = (1..32).map { index ->
            pool.submit<ServiceRegistrationResult> {
                start.await()
                registry.register(object : CoreService {
                    override val descriptor = ServiceDescriptor("service-$index")
                    override fun start(context: LogContext) = Unit
                    override fun stop(context: LogContext) = Unit
                })
            }
        }
        start.countDown()
        futures.map { it.get() }.forEach { assertIs<ServiceRegistrationResult.Registered>(it) }
        pool.shutdown()

        assertEquals(32, registry.snapshot().size)
    }
}
