package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceRegistrySnapshotSizeContractTest {
    @Test
    fun snapshot_contains_one_entry_per_registered_service_id() {
        fun service(id: String) = object : CoreService {
            override val descriptor = ServiceDescriptor(id)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val registry = ServiceRegistry()
        registry.register(service("storage"))
        registry.register(service("memory"))
        registry.register(service("model"))

        assertEquals(3, registry.snapshot().size)
    }
}
