package pro.liliya.core.services

import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceDescriptorValueContractTest {
    @Test
    fun equivalent_descriptors_have_value_equality() {
        assertEquals(
            ServiceDescriptor("memory", setOf("storage")),
            ServiceDescriptor("memory", setOf("storage"))
        )
    }
}
