package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceRegistryConcurrencyContractTest {
    @Test
    fun concurrent_duplicate_registration_has_exactly_one_owner() {
        val registry = ServiceRegistry()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val services = (1..32).map { index ->
            object : CoreService {
                override val descriptor = ServiceDescriptor("shared")
                override fun start(context: LogContext) = Unit
                override fun stop(context: LogContext) = Unit
                override fun toString(): String = "service-$index"
            }
        }

        val futures = services.map { service ->
            pool.submit<ServiceRegistrationResult> {
                start.await()
                registry.register(service)
            }
        }
        start.countDown()
        val results = futures.map { it.get() }
        pool.shutdown()

        assertEquals(1, results.count { it is ServiceRegistrationResult.Registered })
        assertEquals(31, results.count { it is ServiceRegistrationResult.Rejected })
        assertTrue(registry.find("shared") in services)
    }
}
