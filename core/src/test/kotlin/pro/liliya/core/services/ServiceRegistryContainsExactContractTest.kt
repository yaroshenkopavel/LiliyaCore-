package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceRegistryContainsExactContractTest {
    @Test
    fun contains_matches_only_exact_registered_id() {
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("memory")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        })

        assertTrue(registry.contains("memory"))
        assertFalse(registry.contains("Memory"))
        assertFalse(registry.contains("memory "))
    }
}
