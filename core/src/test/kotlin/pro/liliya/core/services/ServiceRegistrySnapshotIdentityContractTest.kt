package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertSame

class ServiceRegistrySnapshotIdentityContractTest {
    @Test
    fun snapshot_values_are_exact_registered_instances() {
        val service = object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val registry = ServiceRegistry()
        registry.register(service)

        val snapshot = registry.snapshot()

        assertSame(service, snapshot.getValue("storage"))
    }
}
