package pro.liliya.core.modules

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModuleRegistrySnapshotContractTest {
    @Test
    fun snapshot_is_not_mutated_by_later_registration() {
        fun module(id: String) = object : CoreModule {
            override val descriptor = ModuleDescriptor(id)
            override val services = emptyList<pro.liliya.core.services.CoreService>()
        }

        val registry = ModuleRegistry()
        registry.register(module("memory"))
        val snapshot = registry.snapshot()
        registry.register(module("model"))

        assertTrue("memory" in snapshot)
        assertFalse("model" in snapshot)
        assertTrue(registry.contains("model"))
    }
}
