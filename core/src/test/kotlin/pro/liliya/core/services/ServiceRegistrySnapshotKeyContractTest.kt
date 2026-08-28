package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceRegistrySnapshotKeyContractTest {
    @Test
    fun snapshot_key_is_exact_descriptor_id() {
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("memory-service")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        })

        assertEquals(setOf("memory-service"), registry.snapshot().keys)
    }
}
