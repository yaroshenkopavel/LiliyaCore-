package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceRegistrationSnapshotContractTest {
    @Test
    fun registry_snapshot_is_not_mutated_by_later_registration() {
        fun service(id: String) = object : CoreService {
            override val descriptor = ServiceDescriptor(id)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }

        val registry = ServiceRegistry()
        registry.register(service("storage"))
        val snapshot = registry.snapshot()
        registry.register(service("memory"))

        assertTrue("storage" in snapshot)
        assertFalse("memory" in snapshot)
        assertTrue(registry.contains("memory"))
    }
}
