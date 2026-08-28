package pro.liliya.core.services

import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceDescriptorDependencyDuplicateContractTest {
    @Test
    fun dependency_set_contains_each_dependency_identity_once() {
        val dependencies = listOf("storage", "storage").toSet()
        val descriptor = ServiceDescriptor("memory", dependencies)

        assertEquals(setOf("storage"), descriptor.dependencies)
    }
}
