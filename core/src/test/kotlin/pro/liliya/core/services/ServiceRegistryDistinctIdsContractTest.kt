package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceRegistryDistinctIdsContractTest {
    @Test
    fun distinct_service_ids_can_be_registered_independently() {
        fun service(id: String) = object : CoreService {
            override val descriptor = ServiceDescriptor(id)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val registry = ServiceRegistry()

        assertIs<ServiceRegistrationResult.Registered>(registry.register(service("storage")))
        assertIs<ServiceRegistrationResult.Registered>(registry.register(service("memory")))
        assertEquals(setOf("storage", "memory"), registry.snapshot().keys)
    }
}
