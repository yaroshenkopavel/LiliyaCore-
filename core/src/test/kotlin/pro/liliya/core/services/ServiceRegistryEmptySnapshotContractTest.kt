package pro.liliya.core.services

import kotlin.test.Test
import kotlin.test.assertTrue

class ServiceRegistryEmptySnapshotContractTest {
    @Test
    fun new_registry_has_empty_stable_snapshot() {
        val registry = ServiceRegistry()

        assertTrue(registry.snapshot().isEmpty())
    }
}
