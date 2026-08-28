package pro.liliya.core.services

import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceDescriptorOrderInsensitiveContractTest {
    @Test
    fun dependency_set_order_does_not_change_descriptor_value() {
        val first = ServiceDescriptor("assistant", linkedSetOf("memory", "model"))
        val second = ServiceDescriptor("assistant", linkedSetOf("model", "memory"))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }
}
