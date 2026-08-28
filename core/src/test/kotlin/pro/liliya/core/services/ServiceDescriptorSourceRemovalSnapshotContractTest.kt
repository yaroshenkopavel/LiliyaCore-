package pro.liliya.core.services

import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceDescriptorSourceRemovalSnapshotContractTest {
    @Test
    fun removing_from_mutable_source_does_not_change_descriptor_dependencies() {
        val source = mutableSetOf("storage", "model")
        val descriptor = ServiceDescriptor("memory", source)

        source.clear()

        assertEquals(setOf("storage", "model"), descriptor.dependencies)
    }
}
