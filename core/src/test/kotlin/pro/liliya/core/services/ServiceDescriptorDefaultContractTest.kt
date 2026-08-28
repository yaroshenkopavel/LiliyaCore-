package pro.liliya.core.services

import kotlin.test.Test
import kotlin.test.assertTrue

class ServiceDescriptorDefaultContractTest {
    @Test
    fun descriptor_without_dependencies_has_empty_dependency_set() {
        assertTrue(ServiceDescriptor("storage").dependencies.isEmpty())
    }
}
