package pro.liliya.core.modules

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ModuleRegistryConcurrencyContractTest {
    @Test
    fun concurrent_duplicate_registration_has_exactly_one_owner() {
        fun module() = object : CoreModule {
            override val descriptor = ModuleDescriptor("memory")
            override val services = emptyList<pro.liliya.core.services.CoreService>()
        }
        val registry = ModuleRegistry()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val futures = (1..16).map {
            pool.submit<ModuleRegistrationResult> {
                start.await()
                registry.register(module())
            }
        }
        start.countDown()
        val results = futures.map { it.get() }
        pool.shutdown()

        assertEquals(1, results.count { it is ModuleRegistrationResult.Registered })
        assertEquals(15, results.count { it is ModuleRegistrationResult.Rejected })
    }
}
