package pro.liliya.core.services

import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceDescriptorDependencySnapshotContractTest {
    @Test
    fun descriptor_keeps_dependency_value_independent_from_mutable_source_set() {
        val source = mutableSetOf("storage")
        val descriptor = ServiceDescriptor("memory", source)

        source += "model"

        assertEquals(setOf("storage"), descriptor.dependencies)
    }
}
