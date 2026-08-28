package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertTrue

class ServiceRegistrySnapshotMapIsolationContractTest {
    @Test
    fun manipulating_a_mutable_copy_of_snapshot_cannot_change_registry() {
        val registry = ServiceRegistry()
        registry.register(object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        })

        val copy = registry.snapshot().toMutableMap()
        copy.clear()

        assertTrue(registry.contains("storage"))
    }
}
