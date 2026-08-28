package pro.liliya.core.services

import kotlin.test.Test
import kotlin.test.assertNull

class ServiceRegistryFindUnknownContractTest {
    @Test
    fun unknown_service_lookup_returns_null_without_side_effects() {
        val registry = ServiceRegistry()

        assertNull(registry.find("missing"))
        assertNull(registry.find("missing"))
    }
}
