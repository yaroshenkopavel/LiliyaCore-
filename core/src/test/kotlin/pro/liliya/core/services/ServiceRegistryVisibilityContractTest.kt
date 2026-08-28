package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServiceRegistryVisibilityContractTest {
    @Test
    fun registered_service_is_discoverable_by_stable_id() {
        val registry = ServiceRegistry()
        val service = object : CoreService {
            override val descriptor = ServiceDescriptor("memory")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }

        assertFalse(registry.contains("memory"))
        assertNull(registry.find("memory"))
        registry.register(service)
        assertTrue(registry.contains("memory"))
        assertSame(service, registry.find("memory"))
    }
}
